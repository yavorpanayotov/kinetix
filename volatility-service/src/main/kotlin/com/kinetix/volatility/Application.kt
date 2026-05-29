package com.kinetix.volatility

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.VolPoint
import com.kinetix.common.model.VolSurface
import com.kinetix.common.model.VolatilitySource
import com.kinetix.volatility.cache.RedisVolatilityCache
import com.kinetix.volatility.feed.VolSurfaceFeedSimulator
import com.kinetix.volatility.kafka.KafkaVolatilityPublisher
import com.kinetix.volatility.metrics.VolatilitySurfaceHealthMetrics
import com.kinetix.volatility.persistence.DatabaseConfig
import com.kinetix.volatility.routes.demoResetRoutes
import com.kinetix.volatility.seed.DevDataSeeder
import com.kinetix.volatility.persistence.DatabaseFactory
import com.kinetix.volatility.persistence.ExposedVolSurfaceRepository
import com.kinetix.volatility.persistence.VolSurfaceRepository
import com.kinetix.volatility.routes.volatilityRoutes
import com.kinetix.volatility.service.VolatilityIngestionService
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.ktor.server.netty.EngineMain
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import com.kinetix.volatility.error.configureErrorHandling
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
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun main(args: Array<String>): Unit = EngineMain.main(args)

/**
 * Attribute key under which [module] stores the Prometheus registry backing the
 * `/metrics` endpoint, so route-wiring overloads can bind additional meters
 * (e.g. the surface-health metrics) to the same registry that is scraped.
 */
val MicrometerRegistryKey: io.ktor.util.AttributeKey<PrometheusMeterRegistry> =
    io.ktor.util.AttributeKey("volatility-service-micrometer-registry")

fun Application.module() {
    log.info("Starting volatility-service")
    // Reuse a registry a caller has already installed under the attribute key
    // (so surface-health meters bound before [module] runs share the scrape
    // registry); otherwise create and publish one here.
    val appMicrometerRegistry = if (attributes.contains(MicrometerRegistryKey)) {
        attributes[MicrometerRegistryKey]
    } else {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also {
            attributes.put(MicrometerRegistryKey, it)
        }
    }
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
            title = "Volatility Service API"
            version = "1.0.0"
            description = "Manages volatility surfaces"
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
    volSurfaceRepository: VolSurfaceRepository,
    ingestionService: VolatilityIngestionService,
) {
    module()
    routing {
        volatilityRoutes(volSurfaceRepository, ingestionService)
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

    val volSurfaceRepository = ExposedVolSurfaceRepository(db)

    val redisConfig = environment.config.config("redis")
    val redisUrl = redisConfig.property("url").getString()
    val redisClient = RedisClient.create(redisUrl)
    val redisConnection = redisClient.connect()
    val cache = RedisVolatilityCache(redisConnection)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()
    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val publisher = KafkaVolatilityPublisher(kafkaProducer)

    // Publish the scrape registry under the attribute key before [module] runs
    // so the surface-health meters and the /metrics endpoint share it.
    val micrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(MicrometerRegistryKey, micrometerRegistry)
    val healthMetrics = VolatilitySurfaceHealthMetrics(micrometerRegistry)

    val ingestionService = VolatilityIngestionService(volSurfaceRepository, cache, publisher, healthMetrics)

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        seedComplete = { seedDone.get() },
    )

    module(volSurfaceRepository, ingestionService)

    routing {
        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(db, volSurfaceRepository, demoResetToken)
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
            DevDataSeeder(volSurfaceRepository).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }

    val feedEnabled = environment.config.propertyOrNull("feed.enabled")?.getString()?.toBoolean() ?: true
    if (feedEnabled) {
        val seedSurfaces = DevDataSeeder.SURFACE_CONFIGS.map { (underlying, config) ->
            val points = DevDataSeeder.MATURITY_DAYS.flatMap { matDays ->
                DevDataSeeder.STRIKE_PERCENTS.map { pct ->
                    val strike = (config.spotPrice * pct / 100.0).toBigDecimal()
                        .setScale(2, RoundingMode.HALF_UP)
                    val impliedVol = DevDataSeeder.computeImpliedVol(config.atmVol, pct, matDays)
                    VolPoint(strike = strike, maturityDays = matDays, impliedVol = impliedVol)
                }
            }
            VolSurface(
                instrumentId = InstrumentId(underlying),
                asOf = DevDataSeeder.AS_OF,
                points = points,
                source = VolatilitySource.EXCHANGE,
            )
        }
        val simulator = VolSurfaceFeedSimulator(seedSurfaces)
        launch {
            while (isActive) {
                delay(30_000)
                try {
                    val tick = simulator.tick(java.time.Instant.now())
                    tick.forEach { ingestionService.ingest(it) }
                } catch (e: Exception) {
                    log.error("Vol surface feed simulator tick failed", e)
                }
            }
        }
    }
}
