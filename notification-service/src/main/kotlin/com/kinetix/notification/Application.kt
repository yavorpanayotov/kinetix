package com.kinetix.notification

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.kafka.ConsumerLivenessTracker
import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.notification.audit.KafkaGovernanceAuditPublisher
import com.kinetix.notification.error.configureErrorHandling
import com.kinetix.notification.delivery.DeliveryRouter
import com.kinetix.notification.delivery.EmailDeliveryService
import com.kinetix.notification.delivery.InAppDeliveryMetrics
import com.kinetix.notification.delivery.InAppDeliveryService
import com.kinetix.notification.delivery.PagerDutyDeliveryService
import com.kinetix.notification.delivery.WebhookDeliveryService
import com.kinetix.notification.engine.AlertEscalationService
import com.kinetix.notification.engine.RulesEngine
import com.kinetix.notification.engine.ScheduledAlertEscalation
import com.kinetix.notification.persistence.AlertAcknowledgement
import com.kinetix.notification.persistence.AlertAcknowledgementRepository
import com.kinetix.notification.persistence.ExposedAlertAcknowledgementRepository
import com.kinetix.notification.persistence.InMemoryAlertAcknowledgementRepository
import com.kinetix.notification.kafka.AnomalyEventConsumer
import com.kinetix.notification.kafka.LimitBreachEventConsumer
import com.kinetix.notification.kafka.MarketRegimeEventConsumer
import com.kinetix.notification.kafka.RiskResultConsumer
import com.kinetix.notification.model.AlertRule
import com.kinetix.notification.model.AlertStatus
import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.ComparisonOperator
import com.kinetix.notification.model.DeliveryChannel
import com.kinetix.notification.model.Severity
import com.kinetix.notification.persistence.DatabaseConfig
import com.kinetix.notification.persistence.DatabaseFactory
import com.kinetix.notification.persistence.ExposedAlertEventRepository
import com.kinetix.notification.persistence.ExposedAlertRuleRepository
import com.kinetix.notification.routes.dtos.EscalateAlertRequest
import com.kinetix.notification.routes.dtos.ResolveAlertRequest
import com.kinetix.notification.routes.dtos.SnoozeAlertRequest
import com.kinetix.notification.seed.DevDataSeeder
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.post
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
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import org.slf4j.MDC
import org.slf4j.event.Level
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import java.util.UUID

fun main(args: Array<String>): Unit = EngineMain.main(args)

/**
 * Attribute key under which [module] stores the Prometheus registry backing the
 * `/metrics` endpoint, so the route-wiring overloads can bind additional meters
 * (e.g. the in-app delivery metrics) to the same registry that is scraped.
 */
val MicrometerRegistryKey: io.ktor.util.AttributeKey<PrometheusMeterRegistry> =
    io.ktor.util.AttributeKey("notification-service-micrometer-registry")

fun Application.module() {
    log.info("Starting notification-service")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(MicrometerRegistryKey, appMicrometerRegistry)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) { json() }
    val otel = OtelInit.init(serviceName = "notification-service")
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
            title = "Notification Service API"
            version = "1.0.0"
            description = "Manages alert rules and notification delivery"
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
    rulesEngine: RulesEngine,
    inAppDelivery: InAppDeliveryService,
    ackRepository: AlertAcknowledgementRepository = InMemoryAlertAcknowledgementRepository(),
    auditPublisher: com.kinetix.notification.audit.GovernanceAuditPublisher? = null,
) {
    module()
    routing {
        notificationRoutes(rulesEngine, inAppDelivery, ackRepository, auditPublisher)
    }
}

fun Application.moduleWithRoutes() {
    module()

    val dbConfig = environment.config.config("database")
    val db = DatabaseFactory.init(
        DatabaseConfig(
            jdbcUrl = dbConfig.property("jdbcUrl").getString(),
            username = dbConfig.property("username").getString(),
            password = dbConfig.property("password").getString(),
        ),
    )

    val ruleRepository = ExposedAlertRuleRepository(db)
    val eventRepository = ExposedAlertEventRepository(db)
    val rulesEngine = RulesEngine(ruleRepository)
    val inAppDeliveryMetrics = InAppDeliveryMetrics(attributes[MicrometerRegistryKey])
    val inAppDelivery = InAppDeliveryService(eventRepository, inAppDeliveryMetrics)
    val emailDelivery = EmailDeliveryService()
    val webhookDelivery = WebhookDeliveryService()
    val pagerDutyDelivery = PagerDutyDeliveryService()
    val deliveryRouter = DeliveryRouter(listOf(inAppDelivery, emailDelivery, webhookDelivery, pagerDutyDelivery))

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()

    val dlqProducerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val dlqProducer = KafkaProducer<String, String>(dlqProducerProps)

    val riskConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service-risk-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val riskResultTracker = ConsumerLivenessTracker(topic = "risk.results", groupId = "notification-service-risk-group")
    val riskResultConsumer = RiskResultConsumer(
        KafkaConsumer<String, String>(riskConsumerProps),
        rulesEngine,
        deliveryRouter,
        retryableConsumer = RetryableConsumer(topic = "risk.results", dlqProducer = dlqProducer, livenessTracker = riskResultTracker),
    )

    val anomalyConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service-anomaly-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val anomalyTracker = ConsumerLivenessTracker(topic = "risk.anomalies", groupId = "notification-service-anomaly-group")
    val anomalyEventConsumer = AnomalyEventConsumer(
        KafkaConsumer<String, String>(anomalyConsumerProps),
        retryableConsumer = RetryableConsumer(topic = "risk.anomalies", dlqProducer = dlqProducer, livenessTracker = anomalyTracker),
    )

    val limitBreachConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service-limit-breach-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val limitBreachTracker = ConsumerLivenessTracker(topic = "limits.breaches", groupId = "notification-service-limit-breach-group")
    val limitBreachEventConsumer = LimitBreachEventConsumer(
        consumer = KafkaConsumer<String, String>(limitBreachConsumerProps),
        deliveryService = inAppDelivery,
        eventRepository = inAppDelivery.repository,
        retryableConsumer = RetryableConsumer(topic = "limits.breaches", dlqProducer = dlqProducer, livenessTracker = limitBreachTracker),
    )

    val regimeConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service-regime-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val regimeTracker = ConsumerLivenessTracker(topic = "risk.regime.changes", groupId = "notification-service-regime-group")
    val regimeEventConsumer = MarketRegimeEventConsumer(
        consumer = KafkaConsumer<String, String>(regimeConsumerProps),
        deliveryService = inAppDelivery,
        eventRepository = inAppDelivery.repository,
        retryableConsumer = RetryableConsumer(topic = "risk.regime.changes", dlqProducer = dlqProducer, livenessTracker = regimeTracker),
    )

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        consumerTrackers = listOf(riskResultTracker, anomalyTracker, limitBreachTracker, regimeTracker),
        seedComplete = { seedDone.get() },
    )

    val ackRepository = ExposedAlertAcknowledgementRepository(db)

    val ackAuditProducerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val ackAuditPublisher = KafkaGovernanceAuditPublisher(KafkaProducer(ackAuditProducerProps))

    routing {
        notificationRoutes(rulesEngine, inAppDelivery, ackRepository, ackAuditPublisher)
    }

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

    launch { riskResultConsumer.start() }
    launch { anomalyEventConsumer.start() }
    launch { limitBreachEventConsumer.start() }
    launch { regimeEventConsumer.start() }

    val auditProducerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val auditPublisher = KafkaGovernanceAuditPublisher(KafkaProducer(auditProducerProps))

    val escalationTimeoutMinutes = environment.config.propertyOrNull("escalation.timeoutMinutes")
        ?.getString()?.toLongOrNull() ?: 30L
    val escalationService = AlertEscalationService(eventRepository, deliveryRouter, escalationTimeoutMinutes, auditPublisher)
    val escalationScheduler = ScheduledAlertEscalation(escalationService)
    launch {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            escalationScheduler.tick()
        }
    }

    val seedEnabled = environment.config.propertyOrNull("seed.enabled")?.getString()?.toBoolean() ?: true
    if (seedEnabled) {
        launch {
            DevDataSeeder(rulesEngine, eventRepository).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }
}

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)

@Serializable
data class AcknowledgeAlertRequest(
    val acknowledgedBy: String,
    val notes: String? = null,
)

@Serializable
data class CreateAlertRuleRequest(
    val name: String,
    val type: String,
    val threshold: Double,
    val operator: String,
    val severity: String,
    val channels: List<String>,
)

@Serializable
data class AlertRuleResponse(
    val id: String,
    val name: String,
    val type: String,
    val threshold: Double,
    val operator: String,
    val severity: String,
    val channels: List<String>,
    val enabled: Boolean,
)

@Serializable
data class AlertEventResponse(
    val id: String,
    val ruleId: String,
    val ruleName: String,
    val type: String,
    val severity: String,
    val message: String,
    val currentValue: Double,
    val threshold: Double,
    val bookId: String,
    val triggeredAt: String,
    val status: String = "TRIGGERED",
    val resolvedAt: String? = null,
    val resolvedReason: String? = null,
    val escalatedAt: String? = null,
    val escalatedTo: String? = null,
    val correlationId: String? = null,
    val suggestedAction: String? = null,
    val snoozedUntil: String? = null,
)

fun Route.notificationRoutes(
    rulesEngine: RulesEngine,
    inAppDelivery: InAppDeliveryService,
    ackRepository: AlertAcknowledgementRepository = InMemoryAlertAcknowledgementRepository(),
    auditPublisher: com.kinetix.notification.audit.GovernanceAuditPublisher? = null,
) {
    route("/api/v1/notifications") {
        get("/rules", {
            summary = "List alert rules"
            tags = listOf("Alert Rules")
        }) {
            val rules = rulesEngine.listRules().map { it.toResponse() }
            call.respond(rules)
        }

        post("/rules", {
            summary = "Create an alert rule"
            tags = listOf("Alert Rules")
            request {
                body<CreateAlertRuleRequest>()
            }
            response {
                code(HttpStatusCode.Created) { body<AlertRuleResponse>() }
            }
        }) {
            val request = call.receive<CreateAlertRuleRequest>()
            require(request.name.isNotBlank()) { "Rule name must not be blank" }
            require(request.channels.isNotEmpty()) { "At least one delivery channel is required" }
            val rule = AlertRule(
                id = UUID.randomUUID().toString(),
                name = request.name,
                type = AlertType.valueOf(request.type),
                threshold = request.threshold,
                operator = ComparisonOperator.valueOf(request.operator),
                severity = Severity.valueOf(request.severity),
                channels = request.channels.map { DeliveryChannel.valueOf(it) },
            )
            rulesEngine.addRule(rule)
            call.respond(HttpStatusCode.Created, rule.toResponse())
        }

        delete("/rules/{ruleId}", {
            summary = "Delete an alert rule"
            tags = listOf("Alert Rules")
            request {
                pathParameter<String>("ruleId")
            }
        }) {
            val ruleId = call.parameters["ruleId"]
                ?: throw IllegalArgumentException("Missing required path parameter: ruleId")
            val exists = rulesEngine.listRules().any { it.id == ruleId }
            if (exists) {
                rulesEngine.removeRule(ruleId)
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/alerts", {
            summary = "List recent alerts"
            tags = listOf("Alerts")
            request {
                queryParameter<Int>("limit") {
                    description = "Maximum number of alerts to return"
                    required = false
                }
                queryParameter<String>("status") {
                    description = "Filter by alert status (TRIGGERED, ACKNOWLEDGED, ESCALATED, RESOLVED)"
                    required = false
                }
            }
        }) {
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50
            val statusParam = call.queryParameters["status"]
            val statusFilter = statusParam?.let {
                try {
                    com.kinetix.notification.model.AlertStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            val alerts = inAppDelivery.getRecentAlerts(limit, statusFilter).map { it.toEventResponse() }
            call.respond(alerts)
        }

        get("/alerts/escalated", {
            summary = "List escalated alerts"
            tags = listOf("Alerts")
        }) {
            val alerts = inAppDelivery.getRecentAlerts(200, AlertStatus.ESCALATED).map { it.toEventResponse() }
            call.respond(alerts)
        }

        post("/alerts/{alertId}/acknowledge", {
            summary = "Acknowledge an alert"
            tags = listOf("Alerts")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<AcknowledgeAlertRequest>()
            }
        }) {
            val alertId = call.parameters["alertId"]
                ?: throw IllegalArgumentException("Missing required path parameter: alertId")
            val request = call.receive<AcknowledgeAlertRequest>()
            val eventRepo = inAppDelivery.repository

            val alert = eventRepo.findById(alertId)
            if (alert == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Alert not found"))
                return@post
            }
            if (!alert.status.canTransitionTo(AlertStatus.ACKNOWLEDGED)) {
                val reason = when (alert.status) {
                    AlertStatus.RESOLVED -> "Alert is already resolved"
                    AlertStatus.ACKNOWLEDGED -> "Alert is already acknowledged"
                    AlertStatus.ESCALATED -> "Escalated alerts cannot be re-acknowledged; resolve them instead"
                    else -> "Cannot acknowledge alert in status ${alert.status}"
                }
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Conflict", reason))
                return@post
            }

            val now = java.time.Instant.now()
            eventRepo.acknowledge(alertId, now)
            ackRepository.save(
                AlertAcknowledgement(
                    id = UUID.randomUUID().toString(),
                    alertEventId = alertId,
                    alertTriggeredAt = alert.triggeredAt,
                    acknowledgedBy = request.acknowledgedBy,
                    acknowledgedAt = now,
                    notes = request.notes,
                ),
            )

            auditPublisher?.publish(
                com.kinetix.common.audit.GovernanceAuditEvent(
                    eventType = com.kinetix.common.audit.AuditEventType.ALERT_ACKNOWLEDGED,
                    userId = request.acknowledgedBy,
                    userRole = "UNKNOWN",
                    alertId = alertId,
                    details = "Alert $alertId acknowledged: ${request.notes ?: "no notes"}",
                    correlationId = MDC.get("correlationId"),
                ),
            )

            val fetched = eventRepo.findById(alertId)!!
            call.respond(fetched.toEventResponse())
        }

        post("/alerts/{alertId}/escalate", {
            summary = "Manually escalate an alert"
            tags = listOf("Alerts")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<EscalateAlertRequest>()
            }
        }) {
            val alertId = call.parameters["alertId"]
                ?: throw IllegalArgumentException("Missing required path parameter: alertId")
            val request = call.receive<EscalateAlertRequest>()
            if (request.reason.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "reason must not be blank"))
                return@post
            }
            val eventRepo = inAppDelivery.repository

            val alert = eventRepo.findById(alertId)
            if (alert == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Alert not found"))
                return@post
            }
            if (!alert.status.canTransitionTo(AlertStatus.ESCALATED)) {
                val reason = when (alert.status) {
                    AlertStatus.RESOLVED -> "Alert is already resolved"
                    AlertStatus.ESCALATED -> "Alert is already escalated"
                    else -> "Cannot escalate alert in status ${alert.status}"
                }
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Conflict", reason))
                return@post
            }

            val now = java.time.Instant.now()
            val assignee = request.assignee?.takeIf { it.isNotBlank() } ?: defaultEscalationAssignee(alert.severity)
            eventRepo.escalate(alertId, now, assignee, promotedSeverity = null)

            auditPublisher?.publish(
                com.kinetix.common.audit.GovernanceAuditEvent(
                    eventType = com.kinetix.common.audit.AuditEventType.ALERT_ESCALATED,
                    userId = "manual",
                    userRole = "UNKNOWN",
                    alertId = alertId,
                    bookId = alert.bookId,
                    details = "Alert $alertId manually escalated to $assignee: ${request.reason}",
                    correlationId = MDC.get("correlationId"),
                ),
            )

            val fetched = eventRepo.findById(alertId)!!
            call.respond(fetched.toEventResponse())
        }

        post("/alerts/{alertId}/resolve", {
            summary = "Resolve an alert"
            tags = listOf("Alerts")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<ResolveAlertRequest>()
            }
        }) {
            val alertId = call.parameters["alertId"]
                ?: throw IllegalArgumentException("Missing required path parameter: alertId")
            val request = call.receive<ResolveAlertRequest>()
            if (request.resolutionText.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", "resolutionText must not be blank"))
                return@post
            }
            val eventRepo = inAppDelivery.repository

            val alert = eventRepo.findById(alertId)
            if (alert == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Alert not found"))
                return@post
            }
            if (!alert.status.canTransitionTo(AlertStatus.RESOLVED)) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Conflict", "Alert is already resolved"),
                )
                return@post
            }

            val now = java.time.Instant.now()
            eventRepo.updateStatus(alertId, AlertStatus.RESOLVED, resolvedAt = now, resolvedReason = request.resolutionText)

            auditPublisher?.publish(
                com.kinetix.common.audit.GovernanceAuditEvent(
                    eventType = com.kinetix.common.audit.AuditEventType.ALERT_RESOLVED,
                    userId = "manual",
                    userRole = "UNKNOWN",
                    alertId = alertId,
                    bookId = alert.bookId,
                    details = "Alert $alertId resolved: ${request.resolutionText}",
                    correlationId = MDC.get("correlationId"),
                ),
            )

            val fetched = eventRepo.findById(alertId)!!
            call.respond(fetched.toEventResponse())
        }

        post("/alerts/{alertId}/snooze", {
            summary = "Snooze an alert until a future timestamp"
            tags = listOf("Alerts")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
                body<SnoozeAlertRequest>()
            }
        }) {
            val alertId = call.parameters["alertId"]
                ?: throw IllegalArgumentException("Missing required path parameter: alertId")
            val request = call.receive<SnoozeAlertRequest>()
            val snoozedUntil = try {
                java.time.Instant.parse(request.snoozedUntil)
            } catch (_: java.time.format.DateTimeParseException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Bad Request", "snoozedUntil must be a valid ISO-8601 timestamp"),
                )
                return@post
            }
            val now = java.time.Instant.now()
            if (!snoozedUntil.isAfter(now)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Bad Request", "snoozedUntil must be in the future"),
                )
                return@post
            }
            val eventRepo = inAppDelivery.repository

            val alert = eventRepo.findById(alertId)
            if (alert == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Alert not found"))
                return@post
            }
            if (alert.status == AlertStatus.RESOLVED) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Conflict", "Cannot snooze a RESOLVED alert"),
                )
                return@post
            }

            val updated = eventRepo.snooze(alertId, snoozedUntil)
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Alert not found"))
                return@post
            }
            call.respond(updated.toEventResponse())
        }

        get("/alerts/{alertId}/contributors", {
            summary = "Get alert contributors (position breakdown at breach time)"
            tags = listOf("Alerts")
            request {
                pathParameter<String>("alertId") { description = "Alert event identifier" }
            }
        }) {
            val alertId = call.parameters["alertId"]
                ?: throw IllegalArgumentException("Missing required path parameter: alertId")
            val alert = inAppDelivery.repository.findById(alertId)
            if (alert == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Alert not found"))
                return@get
            }
            val contributorsJson = alert.contributors
            if (contributorsJson == null) {
                call.respond(emptyList<String>())
                return@get
            }
            call.respondText(contributorsJson, ContentType.Application.Json)
        }
    }
}

private fun AlertRule.toResponse() = AlertRuleResponse(
    id = id,
    name = name,
    type = type.name,
    threshold = threshold,
    operator = operator.name,
    severity = severity.name,
    channels = channels.map { it.name },
    enabled = enabled,
)

private fun defaultEscalationAssignee(severity: Severity): String = when (severity) {
    Severity.CRITICAL -> "risk-manager,cro"
    Severity.WARNING, Severity.INFO -> "desk-head"
}

private fun com.kinetix.notification.model.AlertEvent.toEventResponse() = AlertEventResponse(
    id = id,
    ruleId = ruleId,
    ruleName = ruleName,
    type = type.name,
    severity = severity.name,
    message = message,
    currentValue = currentValue,
    threshold = threshold,
    bookId = bookId,
    triggeredAt = triggeredAt.toString(),
    status = status.name,
    resolvedAt = resolvedAt?.toString(),
    resolvedReason = resolvedReason,
    escalatedAt = escalatedAt?.toString(),
    escalatedTo = escalatedTo,
    correlationId = correlationId,
    suggestedAction = suggestedAction,
    snoozedUntil = snoozedUntil?.toString(),
)
