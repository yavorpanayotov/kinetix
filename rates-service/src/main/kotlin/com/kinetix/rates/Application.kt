package com.kinetix.rates

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.model.CurvePoint
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.RateSource
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.Tenor
import com.kinetix.common.model.YieldCurve
import com.kinetix.rates.cache.RedisRatesCache
import com.kinetix.rates.feed.RatesFeedSimulator
import com.kinetix.rates.kafka.KafkaRatesPublisher
import com.kinetix.rates.persistence.DatabaseConfig
import com.kinetix.rates.routes.demoResetRoutes
import com.kinetix.rates.seed.DevDataSeeder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import com.kinetix.rates.persistence.DatabaseFactory
import com.kinetix.rates.persistence.ExposedForwardCurveRepository
import com.kinetix.rates.persistence.ExposedRiskFreeRateRepository
import com.kinetix.rates.persistence.ExposedYieldCurveRepository
import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import com.kinetix.rates.routes.ratesRoutes
import com.kinetix.rates.service.RateIngestionService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import kotlinx.coroutines.launch
import io.ktor.server.netty.EngineMain
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import com.kinetix.rates.error.configureErrorHandling
import io.ktor.server.request.header
import org.slf4j.event.Level
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    log.info("Starting rates-service")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) { json() }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
    }
    install(OpenApi) {
        info {
            title = "Rates Service API"
            version = "1.0.0"
            description = "Manages yield curves, risk-free rates and forward curves"
        }
    }
    configureErrorHandling()
    routing {
        get("/health") {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        }
        get("/metrics") {
            call.respondText(appMicrometerRegistry.scrape())
        }
        route("openapi.json") { openApi() }
        route("swagger") { swaggerUI("/openapi.json") }
    }
}

fun Application.module(
    yieldCurveRepository: YieldCurveRepository,
    riskFreeRateRepository: RiskFreeRateRepository,
    forwardCurveRepository: ForwardCurveRepository,
    ingestionService: RateIngestionService,
) {
    module()
    routing {
        ratesRoutes(yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository, ingestionService)
    }
}

fun Application.moduleWithRoutes() {
    val dbConfig = environment.config.config("database")
    val db = DatabaseFactory.init(
        DatabaseConfig(
            jdbcUrl = dbConfig.property("jdbcUrl").getString(),
            username = dbConfig.property("username").getString(),
            password = dbConfig.property("password").getString(),
        )
    )

    val yieldCurveRepository = ExposedYieldCurveRepository(db)
    val riskFreeRateRepository = ExposedRiskFreeRateRepository(db)
    val forwardCurveRepository = ExposedForwardCurveRepository(db)

    val redisConfig = environment.config.config("redis")
    val redisUrl = redisConfig.property("url").getString()
    val redisClient = RedisClient.create(redisUrl)
    val redisConnection = redisClient.connect()
    val cache = RedisRatesCache(redisConnection)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()
    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val publisher = KafkaRatesPublisher(kafkaProducer)

    val ingestionService = RateIngestionService(
        yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository, cache, publisher,
    )

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        seedComplete = { seedDone.get() },
    )

    module(yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository, ingestionService)

    routing {
        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(db, yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository, demoResetToken)
        }

        get("/health/ready") {
            val response = readinessChecker.check()
            val status = if (response.status == "READY") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respondText(
                Json.encodeToString(com.kinetix.common.health.ReadinessResponse.serializer(), response),
                ContentType.Application.Json,
                status,
            )
        }
    }

    val seedEnabled = environment.config.propertyOrNull("seed.enabled")?.getString()?.toBoolean() ?: true
    if (seedEnabled) {
        launch {
            DevDataSeeder(yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }

    val feedEnabled = environment.config.propertyOrNull("feed.enabled")?.getString()?.toBoolean() ?: true
    if (feedEnabled) {
        val seedYieldCurves = DevDataSeeder.YIELD_CURVE_DATA.map { (currency, rates) ->
            val tenors = DevDataSeeder.YIELD_CURVE_TENORS.zip(rates).map { (tenorFn, rate) -> tenorFn(rate) }
            YieldCurve(
                currency = Currency.getInstance(currency),
                asOf = DevDataSeeder.AS_OF,
                tenors = tenors,
                curveId = currency,
                source = RateSource.CENTRAL_BANK,
            )
        }
        val seedRiskFreeRates = DevDataSeeder.RISK_FREE_RATE_DATA.map { (config, rate) ->
            RiskFreeRate(
                currency = Currency.getInstance(config.first),
                tenor = config.second,
                rate = rate,
                asOfDate = DevDataSeeder.AS_OF,
                source = RateSource.CENTRAL_BANK,
            )
        }
        val seedForwardCurves = DevDataSeeder.FORWARD_CURVE_DATA.map { (instrumentId, config) ->
            val points = DevDataSeeder.FORWARD_CURVE_TENORS.zip(config.values).map { (tenor, value) ->
                CurvePoint(tenor = tenor, value = value)
            }
            ForwardCurve(
                instrumentId = InstrumentId(instrumentId),
                assetClass = config.assetClass,
                points = points,
                asOfDate = DevDataSeeder.AS_OF,
                source = RateSource.INTERNAL,
            )
        }
        val simulator = RatesFeedSimulator(seedYieldCurves, seedRiskFreeRates, seedForwardCurves)
        launch {
            while (isActive) {
                delay(30_000)
                try {
                    val tick = simulator.tick(Instant.now())
                    tick.yieldCurves.forEach { ingestionService.ingest(it) }
                    tick.riskFreeRates.forEach { ingestionService.ingest(it) }
                    tick.forwardCurves.forEach { ingestionService.ingest(it) }
                } catch (e: Exception) {
                    log.error("Rates feed simulator tick failed", e)
                }
            }
        }
    }
}
