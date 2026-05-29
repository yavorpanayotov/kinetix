package com.kinetix.audit

import com.kinetix.audit.dlq.DlqReplayService
import com.kinetix.audit.dlq.KafkaDlqMessageSource
import com.kinetix.audit.error.configureErrorHandling
import com.kinetix.audit.kafka.AuditEventConsumer
import com.kinetix.audit.metrics.AuditMetrics
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.audit.persistence.DatabaseConfig
import com.kinetix.audit.persistence.DatabaseFactory
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import com.kinetix.audit.persistence.MeteredAuditEventRepository
import com.kinetix.audit.persistence.ExposedVerificationCheckpointRepository
import com.kinetix.audit.persistence.VerificationCheckpointRepository
import com.kinetix.audit.routes.auditRoutes
import com.kinetix.audit.routes.demoResetRoutes
import com.kinetix.audit.routes.dlqReplayRoutes
import com.kinetix.audit.routes.internalRoutes
import com.kinetix.audit.seed.DevDataSeeder
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.kafka.ConsumerLivenessTracker
import com.kinetix.common.kafka.RetryableConsumer
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun main(args: Array<String>): Unit = EngineMain.main(args)

/**
 * Attribute key under which [module] stores the Prometheus registry backing the
 * `/metrics` endpoint, so the route-wiring overloads can bind additional meters
 * (e.g. the audit-trail metrics) to the same registry that is scraped.
 */
val MicrometerRegistryKey: io.ktor.util.AttributeKey<PrometheusMeterRegistry> =
    io.ktor.util.AttributeKey("audit-service-micrometer-registry")

fun Application.module() {
    log.info("Starting audit-service")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(MicrometerRegistryKey, appMicrometerRegistry)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            // Emit defaulted fields on the wire so downstream consumers
            // (the UI in particular) don't have to guess at omitted
            // schema columns. Without this, ``encodeDefaults = false``
            // kicks in and a row whose ``eventType`` equals the column
            // default ("TRADE_BOOKED") is sent with the field missing,
            // which the UI then tried to ``.toUpperCase()`` on.
            encodeDefaults = true
        })
    }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
    }
    install(OpenApi) {
        info {
            title = "Audit Service API"
            version = "1.0.0"
            description = "Records and queries audit events"
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
    repository: AuditEventRepository,
    checkpointRepository: VerificationCheckpointRepository? = null,
    dlqReplayService: DlqReplayService? = null,
) {
    module()
    val auditMetrics = AuditMetrics(attributes[MicrometerRegistryKey])
    routing {
        auditRoutes(repository, checkpointRepository, auditMetrics)
        internalRoutes(repository)
        if (dlqReplayService != null) {
            dlqReplayRoutes(dlqReplayService)
        }
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
    module()

    val auditMetrics = AuditMetrics(attributes[MicrometerRegistryKey])
    val repository: AuditEventRepository =
        MeteredAuditEventRepository(ExposedAuditEventRepository(db), auditMetrics)
    val checkpointRepository = ExposedVerificationCheckpointRepository(db)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()
    val consumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "audit-service-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val dlqProducer = KafkaProducer<String, String>(producerProps)

    val kafkaConsumer = KafkaConsumer<String, String>(consumerProps)
    val auditTracker = ConsumerLivenessTracker(topic = "trades.lifecycle", groupId = "audit-service-group")
    val retryableConsumer = RetryableConsumer(
        topic = "trades.lifecycle",
        dlqProducer = dlqProducer,
        livenessTracker = auditTracker,
    )
    val auditEventConsumer = AuditEventConsumer(kafkaConsumer, repository, retryableConsumer = retryableConsumer)

    val dlqTopic = "trades.lifecycle.dlq"
    val dlqMessageSource = KafkaDlqMessageSource(bootstrapServers, dlqTopic)
    val dlqReplayService = DlqReplayService(repository, messageSource = dlqMessageSource::invoke)

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        consumerTrackers = listOf(auditTracker),
        seedComplete = { seedDone.get() },
    )

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
        auditRoutes(repository, checkpointRepository, auditMetrics)
        internalRoutes(repository)
        dlqReplayRoutes(dlqReplayService)

        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(db, repository, demoResetToken)
        }
    }

    launch {
        auditEventConsumer.start()
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
}
