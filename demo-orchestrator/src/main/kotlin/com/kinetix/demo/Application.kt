package com.kinetix.demo

import com.kinetix.demo.client.RiskOrchestratorHttpClient
import com.kinetix.demo.config.DemoConfig
import com.kinetix.demo.schedule.LimitSeedJob
import com.kinetix.demo.schedule.SchedulingHelpers
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalTime

private const val DEFAULT_PORT = 8094
private val LIMIT_SEED_TARGET_UTC: LocalTime = LocalTime.of(6, 5)

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = DemoConfig.fromEnv()

    install(ContentNegotiation) { json() }

    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }

    if (!config.demoMode) {
        log.info("demo mode disabled — running as no-op")
        return
    }

    log.info("demo mode enabled")
    wireDemoSchedulers(config)
}

/**
 * Wires the demo-only HTTP clients and scheduled jobs. Only invoked when
 * [DemoConfig.demoMode] is true.
 *
 * The Ktor [HttpClient] lifecycle is bound to the application: closed when the
 * server stops so we don't leak the underlying CIO connection pool during
 * tests or graceful shutdowns.
 */
private fun Application.wireDemoSchedulers(config: DemoConfig) {
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
