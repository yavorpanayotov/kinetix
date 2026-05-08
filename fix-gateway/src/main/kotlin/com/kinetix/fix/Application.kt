package com.kinetix.fix

import com.kinetix.common.health.ReadinessResponse
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
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: UUID.randomUUID().toString()
        }
    }
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
    )
    val grpcServer = FixGatewayServer(
        port = grpcPort,
        services = listOf(fixGatewayService),
    ).start()

    monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        grpcServer.stop()
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
