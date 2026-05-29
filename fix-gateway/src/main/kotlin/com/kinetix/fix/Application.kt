package com.kinetix.fix

import com.kinetix.common.health.ReadinessResponse
import com.kinetix.fix.canary.CanaryGate
import com.kinetix.fix.error.configureErrorHandling
import com.kinetix.fix.canary.MicrometerSliReader
import com.kinetix.fix.canary.PromotionDecision
import com.kinetix.fix.canary.SliThresholds
import com.kinetix.fix.grpc.FixGatewayServer
import com.kinetix.fix.grpc.FixGatewayServiceImpl
import com.kinetix.fix.health.FixGatewayReadiness
import com.kinetix.fix.persistence.DatabaseConfig
import com.kinetix.fix.persistence.DatabaseFactory
import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.NoOpFixSessionSender
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.EngineMain
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module(
    appMicrometerRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) {
    log.info("Starting fix-gateway")
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: UUID.randomUUID().toString()
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
    }
}

fun Application.moduleWithDependencies() {
    val dbConfig = environment.config.config("database")
    DatabaseFactory.init(
        DatabaseConfig(
            jdbcUrl = dbConfig.property("jdbcUrl").getString(),
            username = dbConfig.property("username").getString(),
            password = dbConfig.property("password").getString(),
        )
    )

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()

    val readinessChecker = FixGatewayReadiness.build(
        dataSource = DatabaseFactory.dataSource,
        kafkaBootstrapServers = bootstrapServers,
    )

    val grpcPort = environment.config.propertyOrNull("grpc.port")?.getString()?.toInt() ?: 9105
    val venueSessionRegistry = VenueSessionRegistry()
    val venueCutoffRegistry = VenueCutoffRegistry()
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val pendingNewCorrelator = PendingNewCorrelator(meterRegistry = appMicrometerRegistry)
    val fixGatewayService = FixGatewayServiceImpl(
        venueSessionRegistry = venueSessionRegistry,
        venueCutoffRegistry = venueCutoffRegistry,
        cancelMessageBuilder = CancelMessageBuilder(),
        newOrderSingleBuilder = NewOrderSingleBuilder(),
        pendingNewCorrelator = pendingNewCorrelator,
        sessionSender = NoOpFixSessionSender(),
        // Phase 2 has no fix_message_log entries yet; phase 4 wires the real lookup.
        originalOrderLookup = { _, _ -> null },
        meterRegistry = appMicrometerRegistry,
    )
    val grpcServer = FixGatewayServer(
        port = grpcPort,
        services = listOf(fixGatewayService),
    ).start()

    monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        grpcServer.stop()
    }

    // Canary gate — evaluates the three SLIs every minute and emits a structured log
    // line so the canary operator can observe when all SLIs are healthy for the required
    // consecutive window.  The gate does NOT flip FIX_GATEWAY_PLACE_ORDER itself;
    // promotion requires a deliberate deployment action once the log consistently shows
    // CanaryGate decision=Allowed.  This matches the ADR-0035 phase-4 single-flag model
    // where the flag controls the canary cohort percentage in position-service.
    val canaryGate = CanaryGate(
        thresholds = SliThresholds(),
        sliReader = MicrometerSliReader(appMicrometerRegistry),
    )
    launch {
        while (isActive) {
            delay(60_000L)
            val decision = canaryGate.checkPromotion()
            when (decision) {
                PromotionDecision.Allowed -> log.info(
                    "CanaryGate decision=Allowed: SLIs healthy for required window — " +
                        "canary may be advanced by flipping FIX_GATEWAY_PLACE_ORDER",
                )
                is PromotionDecision.Blocked -> log.info(
                    "CanaryGate decision=Blocked reason={} breachedSli={}",
                    decision.reason, decision.breachedSli,
                )
            }
        }
    }

    module(appMicrometerRegistry)

    routing {
        get("/health/ready") {
            val response = readinessChecker.check()
            val status = if (response.status == "READY") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respondText(
                Json.encodeToString(ReadinessResponse.serializer(), response),
                ContentType.Application.Json,
                status,
            )
        }
    }
}
