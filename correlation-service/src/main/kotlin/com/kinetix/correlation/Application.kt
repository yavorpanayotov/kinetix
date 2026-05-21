package com.kinetix.correlation

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.model.CorrelationMatrix
import com.kinetix.common.model.EstimationMethod
import com.kinetix.correlation.cache.RedisCorrelationCache
import com.kinetix.correlation.feed.CorrelationFeedSimulator
import com.kinetix.correlation.kafka.KafkaCorrelationPublisher
import com.kinetix.correlation.metrics.CorrelationMatrixHealthMetrics
import com.kinetix.correlation.persistence.CorrelationMatrixRepository
import com.kinetix.correlation.persistence.DatabaseConfig
import com.kinetix.correlation.routes.demoResetRoutes
import com.kinetix.correlation.seed.DevDataSeeder
import com.kinetix.correlation.persistence.DatabaseFactory
import com.kinetix.correlation.persistence.ExposedCorrelationMatrixRepository
import com.kinetix.correlation.routes.correlationRoutes
import com.kinetix.correlation.service.CorrelationIngestionService
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
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import java.util.concurrent.atomic.AtomicBoolean
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
    io.ktor.util.AttributeKey("correlation-service-micrometer-registry")

fun Application.module() {
    log.info("Starting correlation-service")
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
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
    }
    install(OpenApi) {
        info {
            title = "Correlation Service API"
            version = "1.0.0"
            description = "Manages correlation matrices"
        }
    }
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

@Serializable
private data class ErrorBody(val error: String, val message: String)

fun Application.module(
    correlationMatrixRepository: CorrelationMatrixRepository,
    ingestionService: CorrelationIngestionService,
) {
    module()
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody("bad_request", cause.message ?: "Invalid request"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorBody("internal_error", "An unexpected error occurred"),
            )
        }
    }
    routing {
        correlationRoutes(correlationMatrixRepository, ingestionService)
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

    val correlationMatrixRepository = ExposedCorrelationMatrixRepository(db)

    val redisConfig = environment.config.config("redis")
    val redisUrl = redisConfig.property("url").getString()
    val redisClient = RedisClient.create(redisUrl)
    val redisConnection = redisClient.connect()
    val cache = RedisCorrelationCache(redisConnection)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()
    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val publisher = KafkaCorrelationPublisher(kafkaProducer)

    // Publish the scrape registry under the attribute key before [module] runs
    // so the surface-health meters and the /metrics endpoint share it.
    val micrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(MicrometerRegistryKey, micrometerRegistry)
    val healthMetrics = CorrelationMatrixHealthMetrics(micrometerRegistry)

    val ingestionService = CorrelationIngestionService(correlationMatrixRepository, cache, publisher, healthMetrics)

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        seedComplete = { seedDone.get() },
    )

    module(correlationMatrixRepository, ingestionService)

    routing {
        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(db, correlationMatrixRepository, demoResetToken)
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

    val devDataSeeder = DevDataSeeder(correlationMatrixRepository)

    val seedEnabled = environment.config.propertyOrNull("seed.enabled")?.getString()?.toBoolean() ?: true
    if (seedEnabled) {
        launch {
            devDataSeeder.seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }

    val feedEnabled = environment.config.propertyOrNull("feed.enabled")?.getString()?.toBoolean() ?: true
    if (feedEnabled) {
        val seedMatrix = CorrelationMatrix(
            labels = DevDataSeeder.LABELS,
            values = devDataSeeder.correlationValues(),
            windowDays = DevDataSeeder.WINDOW_DAYS,
            asOfDate = DevDataSeeder.AS_OF,
            method = EstimationMethod.HISTORICAL,
        )
        val simulator = CorrelationFeedSimulator(seedMatrix)
        launch {
            while (isActive) {
                delay(60_000)
                try {
                    val tick = simulator.tick(java.time.Instant.now())
                    ingestionService.ingest(tick)
                } catch (e: Exception) {
                    log.error("Correlation feed simulator tick failed", e)
                }
            }
        }
    }
}
