package com.kinetix.gateway

import com.kinetix.common.model.BookId
import com.kinetix.common.security.Permission
import com.kinetix.gateway.auth.BookAccessService
import com.kinetix.gateway.auth.InMemoryBookAccessService
import com.kinetix.gateway.auth.JwtConfig
import com.kinetix.gateway.auth.checkBookAccess
import com.kinetix.gateway.auth.checkMultiBookAccess
import com.kinetix.gateway.auth.configureJwtAuth
import com.kinetix.gateway.auth.requireBookAccess
import com.kinetix.gateway.auth.requirePermission
import com.kinetix.gateway.client.HttpNotificationServiceClient
import com.kinetix.gateway.client.HttpInstrumentServiceClient
import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.client.InstrumentServiceClient
import com.kinetix.gateway.client.HttpPriceServiceClient
import com.kinetix.gateway.client.HttpRegulatoryServiceClient
import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.client.HttpVolatilityServiceClient
import com.kinetix.gateway.client.NotificationServiceClient
import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.PriceServiceClient
import com.kinetix.gateway.client.RegulatoryServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.client.VolatilityServiceClient
import com.kinetix.gateway.dtos.*
import com.kinetix.gateway.routes.backtestProxyRoutes
import com.kinetix.gateway.routes.dataQualityRoutes
import com.kinetix.gateway.routes.demoAdminRoutes
import com.kinetix.gateway.routes.intradayPnlProxyRoutes
import com.kinetix.gateway.routes.intradayVaRTimelineProxyRoutes
import com.kinetix.gateway.routes.volSurfaceRoutes
import com.kinetix.gateway.routes.yieldCurveProxyRoutes
import com.kinetix.gateway.routes.dependenciesRoutes
import com.kinetix.gateway.routes.eodTimelineRoutes
import com.kinetix.gateway.routes.jobHistoryRoutes
import com.kinetix.gateway.routes.priceRoutes
import com.kinetix.gateway.routes.auditProxyRoutes
import com.kinetix.gateway.routes.instrumentRoutes
import com.kinetix.gateway.routes.insightsRoutes
import com.kinetix.gateway.routes.hierarchyRoutes
import com.kinetix.gateway.routes.traderRoutes
import com.kinetix.gateway.routes.notificationRoutes
import com.kinetix.gateway.routes.positionRoutes
import com.kinetix.gateway.routes.strategyProxyRoutes
import com.kinetix.gateway.routes.regulatoryRoutes
import com.kinetix.gateway.routes.runComparisonRoutes
import com.kinetix.gateway.routes.sodSnapshotRoutes
import com.kinetix.gateway.routes.stressScenarioRoutes
import com.kinetix.gateway.routes.stressTestRoutes
import com.kinetix.gateway.routes.whatIfRoutes
import com.kinetix.gateway.routes.positionRiskRoutes
import com.kinetix.gateway.routes.requirePathParam
import com.kinetix.gateway.routes.crossBookVaRRoutes
import com.kinetix.gateway.routes.factorRiskRoutes
import com.kinetix.gateway.routes.croReportRoutes
import com.kinetix.gateway.routes.hierarchyRiskRoutes
import com.kinetix.gateway.routes.riskBudgetRoutes
import com.kinetix.gateway.routes.executionProxyRoutes
import com.kinetix.gateway.routes.liquidityRiskRoutes
import com.kinetix.gateway.routes.limitsRoutes
import com.kinetix.gateway.routes.marginRoutes
import com.kinetix.gateway.routes.demoScenarioRoutes
import com.kinetix.gateway.routes.demoStressWindowsRoutes
import com.kinetix.gateway.routes.tapeReplayStatusRoutes
import com.kinetix.gateway.routes.marketRegimeRoutes
import com.kinetix.gateway.routes.varRoutes
import com.kinetix.gateway.routes.hedgeRecommendationRoutes
import com.kinetix.gateway.routes.benchmarkAttributionRoutes
import com.kinetix.gateway.routes.counterpartyRiskRoutes
import com.kinetix.gateway.routes.keyRateDurationRoutes
import com.kinetix.gateway.routes.saCcrRoutes
import com.kinetix.gateway.audit.GovernanceAuditPublisher
import com.kinetix.gateway.routes.reportProxyRoutes
import com.kinetix.gateway.routes.CopilotInternalAuth
import com.kinetix.gateway.routes.copilotInternalRoutes
import com.kinetix.gateway.kafka.KafkaIntradayPnlConsumer
import com.kinetix.gateway.websocket.AlertBroadcaster
import com.kinetix.gateway.websocket.CopilotBroadcaster
import com.kinetix.gateway.websocket.PnlBroadcaster
import com.kinetix.gateway.websocket.PriceBroadcaster
import com.kinetix.gateway.websocket.alertWebSocket
import com.kinetix.gateway.websocket.copilotWebSocket
import com.kinetix.gateway.websocket.pnlWebSocket
import com.kinetix.gateway.websocket.priceWebSocket
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Properties
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.plugins.statuspages.*
import org.slf4j.event.Level
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    log.info("Starting gateway")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
        mdc("userId") {
            it.authentication.principal<com.kinetix.gateway.auth.JwtUserPrincipal>()?.user?.userId ?: "anonymous"
        }
    }
    install(WebSockets) {
        pingPeriodMillis = 30_000
        timeoutMillis = 10_000
    }
    install(OpenApi) {
        info {
            title = "Gateway API"
            version = "1.0.0"
            description = "API gateway aggregating all Kinetix services"
        }
    }
    install(StatusPages) {
        exception<com.kinetix.gateway.client.ServiceUnavailableException> { call, cause ->
            cause.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("service_unavailable", cause.message ?: "Service unavailable"),
            )
        }
        exception<com.kinetix.gateway.client.GatewayTimeoutException> { call, cause ->
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorResponse("gateway_timeout", cause.message ?: "Gateway timeout"),
            )
        }
        exception<com.kinetix.gateway.client.UpstreamErrorException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ErrorResponse("upstream_error", cause.message ?: "Upstream error"),
            )
        }
        // Client supplied a JSON body whose shape doesn't match the route's
        // DTO (wrong field type, missing required field, malformed JSON).
        // Ktor's content-negotiation surfaces these as JsonConvertException,
        // wrapping the underlying kotlinx.serialization throwable whose
        // message names the offending field — pass that message through so
        // caller engineers can diagnose without needing server logs. These
        // are caller faults, not server faults: do NOT log at ERROR.
        exception<io.ktor.serialization.JsonConvertException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request_body", cause.message ?: "Invalid JSON body"),
            )
        }
        exception<kotlinx.serialization.SerializationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request_body", cause.message ?: "Invalid JSON body"),
            )
        }
        // call.receive<T>() wraps deserialisation failures in
        // BadRequestException, so the wrapper is what actually propagates
        // to StatusPages. Walk the cause chain to surface the underlying
        // serialisation throwable's field-naming message and the richer
        // `invalid_request_body` error code instead of an opaque
        // `bad_request`.
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            val serializationCause = generateSequence<Throwable>(cause) { it.cause }
                .firstOrNull {
                    it is kotlinx.serialization.SerializationException ||
                        it is io.ktor.serialization.JsonConvertException
                }
            if (serializationCause != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "invalid_request_body",
                        serializationCause.message ?: cause.message ?: "Invalid JSON body",
                    ),
                )
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_request", cause.message ?: "Bad request"),
                )
            }
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("bad_request", cause.message ?: "Invalid request"),
            )
        }
        // Transport-level failures: upstream is unreachable (connection refused or
        // connect timeout). Both java.net.ConnectException and Ktor's
        // ConnectTimeoutException (which extends ConnectException) are caught here.
        // We return 503 with a Retry-After hint so callers know to back off.
        exception<java.net.ConnectException> { call, cause ->
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("upstream_unavailable", cause.message ?: "Upstream connection refused"),
            )
        }
        // Ktor's HttpTimeout plugin fires this when the total request time (including
        // read) exceeds requestTimeoutMillis. Return 504 so callers can distinguish
        // from a gateway internal error.
        exception<io.ktor.client.plugins.HttpRequestTimeoutException> { call, cause ->
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorResponse("upstream_timeout", cause.message ?: "Upstream request timed out"),
            )
        }
        // Socket-level read/write timeout (java.net.SocketTimeoutException, also the
        // concrete type behind io.ktor.client.network.sockets.SocketTimeoutException
        // which is a JVM typealias). Map to 504 for the same reason as above.
        exception<java.net.SocketTimeoutException> { call, cause ->
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorResponse("upstream_timeout", cause.message ?: "Upstream socket timed out"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "An unexpected error occurred"),
            )
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

fun Application.module(positionClient: PositionServiceClient, instrumentClient: InstrumentServiceClient? = null) {
    module()
    routing {
        positionRoutes(positionClient, instrumentClient)
    }
}

fun Application.module(priceClient: PriceServiceClient) {
    module()
    routing {
        priceRoutes(priceClient)
    }
}

fun Application.module(broadcaster: PriceBroadcaster) {
    module()
    routing {
        priceWebSocket(broadcaster)
    }
}

fun Application.module(broadcaster: PnlBroadcaster) {
    module()
    routing {
        pnlWebSocket(broadcaster)
    }
}

fun Application.module(riskClient: RiskServiceClient) {
    module()
    routing {
        varRoutes(riskClient)
        crossBookVaRRoutes(riskClient)
        hierarchyRiskRoutes(riskClient)
        riskBudgetRoutes(riskClient)
        croReportRoutes(riskClient)
        liquidityRiskRoutes(riskClient)
        marginRoutes(riskClient)
        factorRiskRoutes(riskClient)
        stressTestRoutes(riskClient)
        whatIfRoutes(riskClient)
        positionRiskRoutes(riskClient)
        regulatoryRoutes(riskClient)
        dependenciesRoutes(riskClient)
        jobHistoryRoutes(riskClient)
        eodTimelineRoutes(riskClient)
        sodSnapshotRoutes(riskClient)
        runComparisonRoutes(riskClient)
        marketRegimeRoutes(riskClient)
        hedgeRecommendationRoutes(riskClient)
        counterpartyRiskRoutes(riskClient)
        keyRateDurationRoutes(riskClient)
        saCcrRoutes(riskClient)
        reportProxyRoutes(riskClient)
        benchmarkAttributionRoutes(riskClient)
    }
}

fun Application.module(
    positionClient: PositionServiceClient,
    priceClient: PriceServiceClient,
    broadcaster: PriceBroadcaster,
) {
    module()
    routing {
        positionRoutes(positionClient)
        priceRoutes(priceClient)
        priceWebSocket(broadcaster)
    }
}

fun Application.module(
    positionClient: PositionServiceClient,
    priceClient: PriceServiceClient,
    broadcaster: PriceBroadcaster,
    riskClient: RiskServiceClient,
) {
    module()
    routing {
        positionRoutes(positionClient)
        priceRoutes(priceClient)
        priceWebSocket(broadcaster)
        varRoutes(riskClient)
        crossBookVaRRoutes(riskClient)
        hierarchyRiskRoutes(riskClient)
        riskBudgetRoutes(riskClient)
        croReportRoutes(riskClient)
        liquidityRiskRoutes(riskClient)
        marginRoutes(riskClient)
        factorRiskRoutes(riskClient)
        stressTestRoutes(riskClient)
        whatIfRoutes(riskClient)
        positionRiskRoutes(riskClient)
        regulatoryRoutes(riskClient)
        dependenciesRoutes(riskClient)
        jobHistoryRoutes(riskClient)
        eodTimelineRoutes(riskClient)
        sodSnapshotRoutes(riskClient)
        runComparisonRoutes(riskClient)
        marketRegimeRoutes(riskClient)
        hedgeRecommendationRoutes(riskClient)
        counterpartyRiskRoutes(riskClient)
        keyRateDurationRoutes(riskClient)
        saCcrRoutes(riskClient)
        benchmarkAttributionRoutes(riskClient)
    }
}

fun Application.module(notificationClient: NotificationServiceClient) {
    module()
    routing {
        notificationRoutes(notificationClient)
    }
}

fun Application.moduleWithVolSurface(volatilityClient: VolatilityServiceClient) {
    module()
    routing {
        volSurfaceRoutes(volatilityClient)
    }
}

fun Application.module(regulatoryClient: RegulatoryServiceClient) {
    module()
    routing {
        stressScenarioRoutes(regulatoryClient)
        backtestProxyRoutes(regulatoryClient)
    }
}

fun Application.moduleWithDataQuality(
    positionClient: PositionServiceClient,
    priceClient: PriceServiceClient,
    riskClient: RiskServiceClient,
) {
    module()
    routing {
        positionRoutes(positionClient)
        priceRoutes(priceClient)
        varRoutes(riskClient)
        dataQualityRoutes()
    }
}

fun Application.moduleWithYieldCurveProxy(httpClient: HttpClient, ratesBaseUrl: String) {
    module()
    routing {
        yieldCurveProxyRoutes(httpClient, ratesBaseUrl)
    }
}

fun Application.moduleWithAuditProxy(httpClient: HttpClient, auditBaseUrl: String) {
    module()
    routing {
        auditProxyRoutes(httpClient, auditBaseUrl)
    }
}

fun Application.moduleWithInsightsProxy(
    httpClient: HttpClient,
    insightsBaseUrl: String,
    streamingHttpClient: HttpClient = httpClient,
) {
    module()
    routing {
        insightsRoutes(httpClient, insightsBaseUrl, streamingHttpClient)
    }
}

fun Application.devModule() {
    val authEnabled = System.getenv("GATEWAY_AUTH_ENABLED")?.toBoolean() ?: true
    val servicesConfig = environment.config.config("services")
    val positionUrl = servicesConfig.property("position.url").getString()
    val priceUrl = servicesConfig.property("price.url").getString()
    val riskUrl = servicesConfig.property("risk.url").getString()
    val notificationUrl = servicesConfig.property("notification.url").getString()
    val ratesUrl = servicesConfig.property("rates.url").getString()
    val referenceDataUrl = servicesConfig.property("referenceData.url").getString()
    val volatilityUrl = servicesConfig.property("volatility.url").getString()
    val correlationUrl = servicesConfig.property("correlation.url").getString()
    val regulatoryUrl = servicesConfig.property("regulatory.url").getString()
    val auditUrl = servicesConfig.property("audit.url").getString()
    val insightsUrl = servicesConfig.property("insights.url").getString()

    val jwtCfg = environment.config.config("jwt")
    val jwtConfig = JwtConfig(
        issuer = jwtCfg.property("issuer").getString(),
        audience = jwtCfg.property("audience").getString(),
        realm = jwtCfg.property("realm").getString(),
        jwksUrl = jwtCfg.property("jwksUrl").getString(),
    )

    val jsonConfig = Json { ignoreUnknownKeys = true }
    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 2_000
        }
    }

    // Dedicated client for long-lived SSE / streaming proxies (e.g. the
    // Copilot chat route). The shared `httpClient` above caps every request
    // at 5 s, which would kill an open chat stream mid-conversation; this
    // client disables the request timeout entirely AND the socket idle
    // timeout, because the live LLM client can sit silent for >10 s
    // between the upstream connect and the first delta (Opus
    // "thinking" before emission). Without an explicit
    // `socketTimeoutMillis = Long.MAX_VALUE` the CIO engine's default
    // socket read timeout (~10 s) kills the upstream connection during
    // that idle gap, producing the spurious 500/ChannelWriteException
    // we used to see on the chat route. Connect timeout stays
    // bounded so the gateway still fails fast when the upstream is down.
    val streamingHttpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 2_000
        }
    }

    // Dedicated client for the buffered insights endpoints
    // (``/explain/var``, ``/explain/report``, ``/brief/today``). The shared
    // ``httpClient`` above caps every request at 5 s; an Opus-backed
    // explainer routinely takes 8–12 s end-to-end (LLM thinking +
    // emission), so a 5 s budget surfaces a misleading 504 even though the
    // upstream eventually answers fine. A 30 s budget gives the AI
    // headroom while still bounding gateway connection lifetime when
    // ai-insights-service truly hangs.
    val insightsBufferedHttpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
            connectTimeoutMillis = 2_000
        }
    }

    val kafkaBootstrapServers = environment.config
        .propertyOrNull("kafka.bootstrapServers")?.getString() ?: "localhost:9092"

    val positionClient = HttpPositionServiceClient(httpClient, positionUrl)
    val instrumentClient = HttpInstrumentServiceClient(httpClient, referenceDataUrl)
    val priceClient = HttpPriceServiceClient(httpClient, priceUrl)
    val riskClient = HttpRiskServiceClient(httpClient, riskUrl)
    val notificationClient = HttpNotificationServiceClient(httpClient, notificationUrl)
    val regulatoryClient = HttpRegulatoryServiceClient(httpClient, regulatoryUrl)
    val volatilityClient = HttpVolatilityServiceClient(httpClient, volatilityUrl)
    val priceBroadcaster = PriceBroadcaster()
    val pnlBroadcaster = PnlBroadcaster()
    val alertBroadcaster = AlertBroadcaster()
    val copilotBroadcaster = CopilotBroadcaster()

    // Dev user book assignments matching Keycloak realm-export.json users
    val bookAccessService = InMemoryBookAccessService(
        traderBooks = mapOf("a0000000-0000-0000-0000-000000000002" to setOf("port-1", "port-2"))
    )

    module()
    val sharedJwkProvider = configureJwtAuth(jwtConfig)
    // When auth is disabled, skip WebSocket token validation too
    val wsJwtConfig = if (authEnabled) jwtConfig else null
    val wsJwkProvider = if (authEnabled) sharedJwkProvider else null
    routing {
        priceWebSocket(priceBroadcaster, wsJwtConfig, wsJwkProvider)
        pnlWebSocket(pnlBroadcaster, wsJwtConfig, wsJwkProvider)
        alertWebSocket(alertBroadcaster, wsJwtConfig, wsJwkProvider)
        copilotWebSocket(copilotBroadcaster, wsJwtConfig, wsJwkProvider)

        // System health is public so CI/k8s probes can reach it without a JWT.
        get("/api/v1/system/health") {
            val serviceUrls = mapOf(
                "position-service" to positionUrl,
                "price-service" to priceUrl,
                "risk-orchestrator" to riskUrl,
                "notification-service" to notificationUrl,
                "rates-service" to ratesUrl,
                "reference-data-service" to referenceDataUrl,
                "volatility-service" to volatilityUrl,
                "correlation-service" to correlationUrl,
                "regulatory-service" to regulatoryUrl,
                "audit-service" to auditUrl,
            )
            val results = coroutineScope {
                serviceUrls.map { (name, url) ->
                    name to async {
                        try {
                            val resp = withTimeoutOrNull(5000L) {
                                httpClient.get("$url/health/ready")
                            }
                            if (resp != null && resp.status == HttpStatusCode.OK) "READY" else "NOT_READY"
                        } catch (_: Exception) {
                            "DOWN"
                        }
                    }
                }.map { (name, deferred) -> name to deferred.await() }
            }
            val overall = if (results.all { it.second == "READY" }) "UP" else "DEGRADED"
            val response = buildJsonObject {
                put("status", overall)
                putJsonObject("services") {
                    putJsonObject("gateway") { put("status", "READY") }
                    for ((name, status) in results) {
                        putJsonObject(name) { put("status", status) }
                    }
                }
            }
            call.respond(response)
        }

        // All HTTP API routes require a valid JWT (unless auth is disabled for smoke tests).
        // authEnabled is threaded into requirePermission so that when auth is off the plugin
        // is completely skipped — no AuthenticationChecked hook is installed, no principal
        // extraction is attempted, and the request passes directly to the route handler.
        val apiRoutes: Route.() -> Unit = {
            requirePermission(Permission.READ_PORTFOLIOS, authEnabled = authEnabled) {
                requireBookAccess(bookAccessService) {
                    positionRoutes(positionClient, instrumentClient)
                    strategyProxyRoutes(httpClient, positionUrl)
                }
                priceRoutes(priceClient)
            }
            requirePermission(Permission.CALCULATE_RISK, authEnabled = authEnabled) {
                requireBookAccess(bookAccessService) {
                    varRoutes(riskClient)
                    liquidityRiskRoutes(riskClient)
                    marginRoutes(riskClient)
                    factorRiskRoutes(riskClient)
                    whatIfRoutes(riskClient)
                    positionRiskRoutes(riskClient)
                    dependenciesRoutes(riskClient)
                    sodSnapshotRoutes(riskClient)
                    runComparisonRoutes(riskClient)
                    intradayPnlProxyRoutes(riskClient)
                    intradayVaRTimelineProxyRoutes(riskClient)
                    reportProxyRoutes(riskClient)
                    benchmarkAttributionRoutes(riskClient)
                    keyRateDurationRoutes(riskClient)
                }
                // Cross-book routes use checkMultiBookAccess() inside handler (bookIds in body)
                crossBookVaRRoutes(riskClient, bookAccessService)
                // Non-bookId routes pass through without book access check
                hierarchyRiskRoutes(riskClient)
                riskBudgetRoutes(riskClient)
                croReportRoutes(riskClient)
            }
            requirePermission(Permission.READ_RISK, authEnabled = authEnabled) {
                requireBookAccess(bookAccessService) {
                    stressTestRoutes(riskClient)
                    jobHistoryRoutes(riskClient)
                    hedgeRecommendationRoutes(riskClient)
                }
                // Non-bookId routes
                limitsRoutes(httpClient, positionUrl)
                marketRegimeRoutes(riskClient)
                counterpartyRiskRoutes(riskClient)
                saCcrRoutes(riskClient)
                volSurfaceRoutes(volatilityClient)
                yieldCurveProxyRoutes(httpClient, ratesUrl)
                insightsRoutes(insightsBufferedHttpClient, insightsUrl, streamingHttpClient)
                demoStressWindowsRoutes()
                demoScenarioRoutes()
                tapeReplayStatusRoutes()
            }
            requirePermission(Permission.READ_REGULATORY, authEnabled = authEnabled) {
                requireBookAccess(bookAccessService) {
                    regulatoryRoutes(riskClient)
                    eodTimelineRoutes(riskClient)
                }
            }
            requirePermission(Permission.READ_ALERTS, authEnabled = authEnabled) {
                notificationRoutes(notificationClient)
            }
            requirePermission(Permission.MANAGE_SCENARIOS, authEnabled = authEnabled) {
                stressScenarioRoutes(regulatoryClient)
                backtestProxyRoutes(regulatoryClient)
            }
            requirePermission(Permission.READ_POSITIONS, authEnabled = authEnabled) {
                requireBookAccess(bookAccessService) {
                    executionProxyRoutes(httpClient, positionUrl)
                }
                instrumentRoutes(httpClient, referenceDataUrl)
                traderRoutes(httpClient, referenceDataUrl)
                hierarchyRoutes(httpClient, referenceDataUrl)
                dataQualityRoutes(httpClient, positionUrl, priceUrl, riskUrl)
            }
            requirePermission(Permission.READ_AUDIT, authEnabled = authEnabled) {
                auditProxyRoutes(httpClient, auditUrl)
            }
        }
        if (authEnabled) {
            authenticate("auth-jwt") { apiRoutes() }
        } else {
            log.warn("GATEWAY_AUTH_ENABLED=false — JWT authentication is DISABLED (smoke/dev mode)")
            apiRoutes()
        }
    }

    val demoAdminKey = System.getenv("DEMO_ADMIN_KEY")
    val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
    if (demoAdminKey != null && demoResetToken != null) {
        routing {
            demoAdminRoutes(
                httpClient = httpClient,
                positionUrl = positionUrl,
                auditUrl = auditUrl,
                riskUrl = riskUrl,
                priceUrl = priceUrl,
                ratesUrl = ratesUrl,
                volatilityUrl = volatilityUrl,
                correlationUrl = correlationUrl,
                referenceDataUrl = referenceDataUrl,
                adminKey = demoAdminKey,
                resetToken = demoResetToken,
            )
        }
    }

    // Cluster-internal intraday-push intake (PR 7 / ADR-0036). ai-insights-service
    // POSTs composed push payloads to POST /internal/copilot/push; the gateway
    // enqueues them on the CopilotBroadcaster for /ws/copilot fan-out. The route
    // is internal-only — no JWT challenge — and is guarded by a shared-secret
    // header (X-Internal-Token), the same pattern demoAdminRoutes uses. It is
    // only registered when COPILOT_INTERNAL_TOKEN is configured, so the
    // unauthenticated route never exists without a guard.
    val copilotInternalToken = System.getenv("COPILOT_INTERNAL_TOKEN")
    if (copilotInternalToken != null) {
        routing {
            copilotInternalRoutes(copilotBroadcaster, CopilotInternalAuth(copilotInternalToken))
        }
    } else {
        log.warn("COPILOT_INTERNAL_TOKEN not set — POST /internal/copilot/push is disabled")
    }

    val pnlConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "gateway-pnl-broadcaster")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val pnlKafkaConsumer = KafkaConsumer<String, String>(pnlConsumerProps)
    launch { KafkaIntradayPnlConsumer(pnlKafkaConsumer, pnlBroadcaster).start() }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: PriceBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module()
    configureJwtAuth(jwtConfig, jwkProvider)
    routing {
        priceWebSocket(broadcaster, jwtConfig, jwkProvider)
    }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: PnlBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module()
    configureJwtAuth(jwtConfig, jwkProvider)
    routing {
        pnlWebSocket(broadcaster, jwtConfig, jwkProvider)
    }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: AlertBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module()
    configureJwtAuth(jwtConfig, jwkProvider)
    routing {
        alertWebSocket(broadcaster, jwtConfig, jwkProvider)
    }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: CopilotBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module()
    configureJwtAuth(jwtConfig, jwkProvider)
    routing {
        copilotWebSocket(broadcaster, jwtConfig, jwkProvider)
    }
}

fun Application.module(
    jwtConfig: JwtConfig,
    positionClient: PositionServiceClient? = null,
    riskClient: RiskServiceClient? = null,
    notificationClient: NotificationServiceClient? = null,
    regulatoryClient: RegulatoryServiceClient? = null,
    httpClient: io.ktor.client.HttpClient? = null,
    auditBaseUrl: String? = null,
    bookAccessService: BookAccessService = InMemoryBookAccessService(),
    auditPublisher: GovernanceAuditPublisher? = null,
    jwkProvider: com.auth0.jwk.JwkProvider? = null,
) {
    module()
    configureJwtAuth(jwtConfig, jwkProvider)
    routing {
        authenticate("auth-jwt") {
            if (positionClient != null) {
                requirePermission(Permission.READ_PORTFOLIOS, auditPublisher) {
                    get("/api/v1/books") {
                        val portfolios = positionClient.listPortfolios()
                        call.respond(portfolios.map { it.toResponse() })
                    }
                }
                route("/api/v1/books/{bookId}") {
                    requireBookAccess(bookAccessService, auditPublisher) {
                        requirePermission(Permission.WRITE_TRADES, auditPublisher) {
                            post("/trades") {
                                val rawBookId = call.requirePathParam("bookId")
                                val bookId = BookId(rawBookId)
                                val request = call.receive<BookTradeRequest>()
                                val command = request.toCommand(bookId)
                                val result = positionClient.bookTrade(command)
                                call.respond(HttpStatusCode.Created, result.toResponse())
                            }
                        }
                        requirePermission(Permission.READ_POSITIONS, auditPublisher) {
                            get("/positions") {
                                val rawBookId = call.requirePathParam("bookId")
                                val bookId = BookId(rawBookId)
                                val positions = positionClient.getPositions(bookId)
                                call.respond(positions.map { it.toResponse() })
                            }
                        }
                    }
                }
            }
            if (riskClient != null) {
                requirePermission(Permission.CALCULATE_RISK, auditPublisher) {
                    requireBookAccess(bookAccessService, auditPublisher) {
                        varRoutes(riskClient)
                        marginRoutes(riskClient)
                        whatIfRoutes(riskClient)
                        positionRiskRoutes(riskClient)
                        dependenciesRoutes(riskClient)
                        sodSnapshotRoutes(riskClient)
                    }
                    crossBookVaRRoutes(riskClient, bookAccessService)
                }
                requirePermission(Permission.READ_RISK, auditPublisher) {
                    requireBookAccess(bookAccessService, auditPublisher) {
                        stressTestRoutes(riskClient)
                        jobHistoryRoutes(riskClient)
                    }
                    marketRegimeRoutes(riskClient)
                }
                requirePermission(Permission.READ_REGULATORY, auditPublisher) {
                    requireBookAccess(bookAccessService, auditPublisher) {
                        regulatoryRoutes(riskClient)
                    }
                }
            }
            if (notificationClient != null) {
                requirePermission(Permission.READ_ALERTS, auditPublisher) {
                    notificationRoutes(notificationClient)
                }
            }
            if (regulatoryClient != null) {
                requirePermission(Permission.MANAGE_SCENARIOS) {
                    stressScenarioRoutes(regulatoryClient)
                    backtestProxyRoutes(regulatoryClient)
                }
            }
            if (httpClient != null && auditBaseUrl != null) {
                requirePermission(Permission.READ_AUDIT) {
                    auditProxyRoutes(httpClient, auditBaseUrl)
                }
            }
        }
    }
}
