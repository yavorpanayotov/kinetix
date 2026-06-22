package com.kinetix.price

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.price.cache.RedisPriceCache
import com.kinetix.price.kafka.KafkaPricePublisher
import com.kinetix.price.metrics.PriceMetrics
import com.kinetix.price.metrics.PriceStalenessTracker
import com.kinetix.price.persistence.DatabaseConfig
import com.kinetix.price.persistence.DatabaseFactory
import com.kinetix.price.persistence.ExposedPriceRepository
import com.kinetix.price.persistence.PriceRepository
import com.kinetix.price.routes.demoResetRoutes
import com.kinetix.price.routes.priceRoutes
import com.kinetix.price.feed.InstrumentSeed
import com.kinetix.price.feed.PriceFeedSimulator
import com.kinetix.price.seed.DevDataSeeder
import com.kinetix.price.service.PriceIngestionService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import com.kinetix.common.observability.OtelHttpServerPlugin
import com.kinetix.common.observability.OtelInit
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import com.kinetix.price.error.configureErrorHandling
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.event.Level
import io.ktor.server.routing.*
import io.lettuce.core.RedisClient
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PriceSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.util.Currency
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module(
    appMicrometerRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) {
    log.info("Starting price-service")
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) { json() }
    val otel = OtelInit.init(serviceName = "price-service")
    install(OtelHttpServerPlugin) { openTelemetry = otel }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("endpoint") { it.request.path() }
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
    }
    install(OpenApi) {
        info {
            title = "Price Service API"
            version = "1.0.0"
            description = "Manages instrument prices, price history and ingestion"
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

fun Application.module(repository: PriceRepository, ingestionService: PriceIngestionService) {
    module()
    routing {
        priceRoutes(repository, ingestionService)
    }
}

fun Application.moduleWithRoutes() {
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val dbConfig = environment.config.config("database")
    val db = DatabaseFactory.init(
        DatabaseConfig(
            jdbcUrl = dbConfig.property("jdbcUrl").getString(),
            username = dbConfig.property("username").getString(),
            password = dbConfig.property("password").getString(),
        )
    )
    val repository = ExposedPriceRepository(db)

    val redisConfig = environment.config.config("redis")
    val redisUrl = redisConfig.property("url").getString()
    val redisClient = RedisClient.create(redisUrl)
    val redisConnection = redisClient.connect()
    val cache = RedisPriceCache(redisConnection)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()
    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val publisher = KafkaPricePublisher(kafkaProducer)

    val stalenessTracker = PriceStalenessTracker(appMicrometerRegistry)
    val priceMetrics = PriceMetrics(appMicrometerRegistry)
    val ingestionService = PriceIngestionService(repository, cache, publisher, stalenessTracker, priceMetrics)

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        seedComplete = { seedDone.get() },
    )

    module(appMicrometerRegistry)

    routing {
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

    routing {
        priceRoutes(repository, ingestionService)

        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(db, repository, demoResetToken)
        }
    }

    val seedEnabled = environment.config.propertyOrNull("seed.enabled")?.getString()?.toBoolean() ?: true
    if (seedEnabled) {
        launch {
            DevDataSeeder(repository).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }

    // Index underlyings (IDX-SPX, IDX-VIX) need a *live* spot so the engine can
    // price index options (SPX-OPT-*, VIX-OPT-*). They are not part of the
    // tape-driven historical seed (IDX-VIX has no tape series at all), so they
    // are added to the live feed here rather than to DevDataSeeder.INSTRUMENTS
    // (kx-3145). Without a current spot these options' greeks render N/A.
    val indexFeedSeeds = listOf(
        InstrumentSeed(InstrumentId("IDX-SPX"), BigDecimal("5000.00"), Currency.getInstance("USD"), AssetClass.EQUITY),
        InstrumentSeed(InstrumentId("IDX-VIX"), BigDecimal("16.00"), Currency.getInstance("USD"), AssetClass.EQUITY),
    )

    val feedEnabled = environment.config.propertyOrNull("feed.enabled")?.getString()?.toBoolean() ?: true
    if (feedEnabled) {
        val seeds = DevDataSeeder.INSTRUMENTS.map { (id, config) ->
            InstrumentSeed(
                instrumentId = id,
                initialPrice = BigDecimal(config.latestPrice.toString()),
                currency = Currency.getInstance(config.currency),
                assetClass = config.assetClass,
            )
        } + indexFeedSeeds
        val simulator = PriceFeedSimulator(seeds, tickIntervalSeconds = 60.0)
        launch {
            while (isActive) {
                delay(60_000)
                try {
                    simulator.tick(Instant.now(), PriceSource.INTERNAL).forEach { point ->
                        ingestionService.ingest(point)
                    }
                } catch (e: Exception) {
                    log.error("Price feed simulator tick failed", e)
                }
            }
        }
    }

    // Phase 1 Gap 7 — intraday demo tape replay. Ticks every few seconds (vs the
    // 60s feed simulator above) so the demo screen visibly moves. Gated on
    // DEMO_TAPE_REPLAY_ENABLED and the local seedDone flag so we never replay
    // onto a half-seeded database.
    val replayEnabled = System.getenv("DEMO_TAPE_REPLAY_ENABLED")?.toBoolean() ?: false
    if (replayEnabled) {
        val replaySeeds = DevDataSeeder.INSTRUMENTS.map { (id, config) ->
            InstrumentSeed(
                instrumentId = id,
                initialPrice = BigDecimal(config.latestPrice.toString()),
                currency = Currency.getInstance(config.currency),
                assetClass = config.assetClass,
            )
        } + indexFeedSeeds
        val replaySweeper = com.kinetix.price.feed.DemoTapeReplaySweeper.fromSeeds(
            seeds = replaySeeds,
            ingestionService = ingestionService,
            readinessGate = { seedDone.get() },
        )
        launch { replaySweeper.start() }
    }
}
