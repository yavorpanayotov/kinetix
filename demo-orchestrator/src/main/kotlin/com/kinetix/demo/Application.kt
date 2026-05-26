package com.kinetix.demo

import com.kinetix.demo.client.PositionServiceHttpClient
import com.kinetix.demo.client.RegulatoryServiceHttpClient
import com.kinetix.demo.client.RiskOrchestratorHttpClient
import com.kinetix.demo.config.DemoConfig
import com.kinetix.demo.kafka.OfficialEodConsumer
import com.kinetix.demo.routes.bootstrapStatusRoutes
import com.kinetix.demo.schedule.BootstrapStateHolder
import com.kinetix.demo.schedule.DefaultStrategyIdResolver
import com.kinetix.demo.schedule.DemoVaRBootstrapJob
import com.kinetix.demo.schedule.EodCycleObserverJob
import com.kinetix.demo.schedule.EodPromotionJob
import com.kinetix.demo.schedule.LimitSeedJob
import com.kinetix.demo.schedule.RiskOrchestratorBacktestInputProvider
import com.kinetix.demo.schedule.SchedulingHelpers
import com.kinetix.demo.schedule.SimulatedPriceBook
import com.kinetix.demo.schedule.SimulatedTraderJob
import com.kinetix.demo.schedule.SodBaselineCaptureJob
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.LocalTime
import java.util.Properties
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PORT = 8094
private val LIMIT_SEED_TARGET_UTC: LocalTime = LocalTime.of(6, 5)

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = DemoConfig.fromEnv()
    val bootstrapStateHolder = BootstrapStateHolder()

    install(ContentNegotiation) { json() }

    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        bootstrapStatusRoutes(bootstrapStateHolder)
    }

    if (!config.demoMode) {
        log.info("demo mode disabled — running as no-op")
        return
    }

    log.info("demo mode enabled")
    wireDemoSchedulers(config, bootstrapStateHolder)
}

/**
 * Wires the demo-only HTTP clients and scheduled jobs. Only invoked when
 * [DemoConfig.demoMode] is true.
 *
 * The Ktor [HttpClient] lifecycle is bound to the application: closed when the
 * server stops so we don't leak the underlying CIO connection pool during
 * tests or graceful shutdowns.
 *
 * The VaR bootstrap job is launched in a non-blocking coroutine so that the
 * HTTP server finishes binding before the sweep starts. State transitions are
 * written to [bootstrapStateHolder] so `GET /demo/bootstrap-status` reflects
 * the current phase.
 */
private fun Application.wireDemoSchedulers(
    config: DemoConfig,
    bootstrapStateHolder: BootstrapStateHolder,
) {
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    }
    monitor.subscribe(ApplicationStopping) { httpClient.close() }

    val riskClient = RiskOrchestratorHttpClient(httpClient, config.riskOrchestratorUrl)
    val limitSeedJob = LimitSeedJob(riskClient)

    launch {
        runLimitSeedSafely(limitSeedJob, reason = "startup")
    }
    launch {
        scheduleDailyLimitSeed(limitSeedJob)
    }

    // VaR bootstrap: runs once on startup, non-blocking. State holder tracks
    // NOT_STARTED → IN_PROGRESS → READY (or FAILED on full failure).
    val sodBaselineJob = SodBaselineCaptureJob(client = riskClient)
    val varBootstrapJob = DemoVaRBootstrapJob(
        riskOrchestratorClient = riskClient,
        sodJob = sodBaselineJob,
    )
    launch {
        runVaRBootstrapSafely(varBootstrapJob, bootstrapStateHolder)
    }

    val positionClient = PositionServiceHttpClient(httpClient, config.positionServiceUrl)
    val simulatedTraderJob = SimulatedTraderJob(
        positionClient = positionClient,
        strategyIdResolver = DefaultStrategyIdResolver(),
        priceBook = SimulatedPriceBook(random = java.util.Random()),
        tradingHoursStart = config.tradingHoursStart,
        tradingHoursEnd = config.tradingHoursEnd,
    )
    launch {
        runSimulatedTraderLoop(simulatedTraderJob, config.tradeCadenceSeconds)
    }

    val regulatoryClient = RegulatoryServiceHttpClient(httpClient, config.regulatoryServiceUrl)
    val eodObserverJob = EodCycleObserverJob(
        regulatoryClient = regulatoryClient,
        backtestInputProvider = RiskOrchestratorBacktestInputProvider(client = riskClient),
    )
    val officialEodConsumer = OfficialEodConsumer(
        consumer = buildOfficialEodKafkaConsumer(config.kafkaBootstrapServers),
        job = eodObserverJob,
    )
    launch {
        runOfficialEodConsumerSafely(officialEodConsumer)
    }

    val eodPromotionJob = EodPromotionJob(client = riskClient)
    launch {
        scheduleDailyEodPromotion(eodPromotionJob, config.tradingHoursEnd)
    }

    launch {
        runSodBaselineSafely(sodBaselineJob, reason = "startup")
    }
    launch {
        scheduleDailySodBaseline(sodBaselineJob, config.tradingHoursStart)
    }
}

private suspend fun Application.runVaRBootstrapSafely(
    job: DemoVaRBootstrapJob,
    stateHolder: BootstrapStateHolder,
) {
    stateHolder.setInProgress()
    try {
        log.info("Running DemoVaRBootstrapJob (startup)")
        val result = job.runOnce()
        if (result.failureCount == 0 || result.successCount > 0) {
            stateHolder.setReady(result)
            log.info(
                "DemoVaRBootstrapJob completed — successCount={} sodSuccessCount={}",
                result.successCount,
                result.sodSuccessCount,
            )
        } else {
            stateHolder.setFailed(result)
            log.warn(
                "DemoVaRBootstrapJob fully failed — all {} books failed",
                result.failureCount,
            )
        }
    } catch (cancellation: CancellationException) {
        stateHolder.setFailed(null)
        log.info("DemoVaRBootstrapJob cancelled — exiting")
        throw cancellation
    } catch (failure: Exception) {
        stateHolder.setFailed(null)
        log.error("DemoVaRBootstrapJob threw unexpectedly — bootstrap state set to FAILED", failure)
    }
}

private const val OFFICIAL_EOD_CONSUMER_GROUP_ID = "demo-orchestrator-eod-observer"

private fun buildOfficialEodKafkaConsumer(bootstrapServers: String): KafkaConsumer<String, String> {
    val props = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, OFFICIAL_EOD_CONSUMER_GROUP_ID)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(
            ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
        )
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    return KafkaConsumer(props)
}

private suspend fun Application.runOfficialEodConsumerSafely(consumer: OfficialEodConsumer) {
    try {
        consumer.start()
    } catch (cancellation: CancellationException) {
        log.info("OfficialEodConsumer cancelled — exiting")
        throw cancellation
    } catch (failure: Throwable) {
        log.error("OfficialEodConsumer terminated unexpectedly", failure)
    }
}

private suspend fun Application.runSimulatedTraderLoop(
    job: SimulatedTraderJob,
    cadenceSeconds: Long,
) {
    log.info(
        "SimulatedTraderJob loop starting — cadence {}s",
        cadenceSeconds,
    )
    while (coroutineContext.isActive) {
        try {
            val posted = job.runTick()
            log.debug("SimulatedTraderJob tick posted {} trade(s)", posted)
        } catch (cancellation: CancellationException) {
            log.info("SimulatedTraderJob loop cancelled — exiting")
            throw cancellation
        } catch (failure: Throwable) {
            log.warn("SimulatedTraderJob tick failed — continuing", failure)
        }
        try {
            delay(cadenceSeconds.seconds)
        } catch (cancellation: CancellationException) {
            log.info("SimulatedTraderJob loop cancelled during delay — exiting")
            throw cancellation
        }
    }
}

private suspend fun Application.scheduleDailyLimitSeed(job: LimitSeedJob) {
    while (true) {
        val wait = SchedulingHelpers.durationUntilNext(LIMIT_SEED_TARGET_UTC)
        log.info(
            "Next LimitSeedJob run scheduled in {}s (target {} UTC)",
            wait.seconds,
            LIMIT_SEED_TARGET_UTC,
        )
        try {
            delay(wait.toMillis())
        } catch (cancellation: CancellationException) {
            log.info("LimitSeedJob scheduling loop cancelled — exiting")
            throw cancellation
        }
        runLimitSeedSafely(job, reason = "06:05 UTC schedule")
    }
}

private suspend fun Application.runLimitSeedSafely(job: LimitSeedJob, reason: String) {
    try {
        log.info("Running LimitSeedJob ({})", reason)
        job.runOnce()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn("LimitSeedJob run ({}) failed — continuing", reason, failure)
    }
}

private suspend fun Application.scheduleDailyEodPromotion(
    job: EodPromotionJob,
    closeTimeUtc: LocalTime,
) {
    while (true) {
        val wait = SchedulingHelpers.durationUntilNext(closeTimeUtc)
        log.info(
            "Next EodPromotionJob run scheduled in {}s (target {} UTC)",
            wait.seconds,
            closeTimeUtc,
        )
        try {
            delay(wait.toMillis())
        } catch (cancellation: CancellationException) {
            log.info("EodPromotionJob scheduling loop cancelled — exiting")
            throw cancellation
        }
        runEodPromotionSafely(job, reason = "$closeTimeUtc UTC schedule")
    }
}

private suspend fun Application.runEodPromotionSafely(job: EodPromotionJob, reason: String) {
    try {
        log.info("Running EodPromotionJob ({})", reason)
        job.runOnce()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn("EodPromotionJob run ({}) failed — continuing", reason, failure)
    }
}

private suspend fun Application.scheduleDailySodBaseline(
    job: SodBaselineCaptureJob,
    openTimeUtc: LocalTime,
) {
    while (true) {
        val wait = SchedulingHelpers.durationUntilNext(openTimeUtc)
        log.info(
            "Next SodBaselineCaptureJob run scheduled in {}s (target {} UTC)",
            wait.seconds,
            openTimeUtc,
        )
        try {
            delay(wait.toMillis())
        } catch (cancellation: CancellationException) {
            log.info("SodBaselineCaptureJob scheduling loop cancelled — exiting")
            throw cancellation
        }
        runSodBaselineSafely(job, reason = "$openTimeUtc UTC schedule")
    }
}

private suspend fun Application.runSodBaselineSafely(job: SodBaselineCaptureJob, reason: String) {
    try {
        log.info("Running SodBaselineCaptureJob ({})", reason)
        job.runOnce()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn("SodBaselineCaptureJob run ({}) failed — continuing", reason, failure)
    }
}
