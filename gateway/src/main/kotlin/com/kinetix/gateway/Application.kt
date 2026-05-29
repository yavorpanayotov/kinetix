package com.kinetix.gateway

import com.kinetix.common.security.Permission
import com.kinetix.gateway.audit.GovernanceAuditPublisher
import com.kinetix.gateway.auth.BookAccessService
import com.kinetix.gateway.auth.InMemoryBookAccessService
import com.kinetix.gateway.auth.JwtConfig
import com.kinetix.gateway.auth.configureJwtAuth
import com.kinetix.gateway.auth.requireBookAccess
import com.kinetix.gateway.auth.requirePermission
import com.kinetix.gateway.client.InstrumentServiceClient
import com.kinetix.gateway.client.NotificationServiceClient
import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.PriceServiceClient
import com.kinetix.gateway.client.RegulatoryServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.client.VolatilityServiceClient
import com.kinetix.gateway.dtos.*
import com.kinetix.gateway.error.configureErrorHandling
import com.kinetix.gateway.routes.*
import com.kinetix.gateway.websocket.*
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import com.kinetix.common.observability.OtelHttpServerPlugin
import com.kinetix.common.observability.OtelInit
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun main(args: Array<String>): Unit = EngineMain.main(args)

// ---------------------------------------------------------------------------
// Core module: installs plugins and serves /health, /metrics, OpenAPI docs.
// ---------------------------------------------------------------------------

fun Application.module() {
    log.info("Starting gateway")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    val otel = OtelInit.init(serviceName = "gateway")
    install(OtelHttpServerPlugin) { openTelemetry = otel }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") { it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString() }
        mdc("userId") { it.authentication.principal<com.kinetix.gateway.auth.JwtUserPrincipal>()?.user?.userId ?: "anonymous" }
    }
    install(WebSockets) { pingPeriodMillis = 30_000; timeoutMillis = 10_000 }
    install(OpenApi) {
        info { title = "Gateway API"; version = "1.0.0"; description = "API gateway aggregating all Kinetix services" }
    }
    configureErrorHandling()
    routing {
        get("/health") { call.respondText("""{"status":"UP"}""", ContentType.Application.Json) }
        get("/metrics") { call.respondText(appMicrometerRegistry.scrape()) }
        route("openapi.json") { openApi() }
        route("swagger") { swaggerUI("/openapi.json") }
    }
}

// ---------------------------------------------------------------------------
// Single-service test-harness overloads (used by route-level acceptance tests)
// ---------------------------------------------------------------------------

fun Application.module(positionClient: PositionServiceClient, instrumentClient: InstrumentServiceClient? = null) {
    module(); routing { positionRoutes(positionClient, instrumentClient) }
}

fun Application.module(priceClient: PriceServiceClient) {
    module(); routing { priceRoutes(priceClient) }
}

fun Application.module(broadcaster: PriceBroadcaster) {
    module(); routing { priceWebSocket(broadcaster) }
}

fun Application.module(broadcaster: PnlBroadcaster) {
    module(); routing { pnlWebSocket(broadcaster) }
}

fun Application.module(notificationClient: NotificationServiceClient) {
    module(); routing { notificationRoutes(notificationClient) }
}

fun Application.module(regulatoryClient: RegulatoryServiceClient) {
    module(); routing { stressScenarioRoutes(regulatoryClient); backtestProxyRoutes(regulatoryClient) }
}

fun Application.module(riskClient: RiskServiceClient) {
    module(); routing { allRiskRoutes(riskClient) }
}

// ---------------------------------------------------------------------------
// Multi-service test-harness overloads
// ---------------------------------------------------------------------------

/**
 * Wires both position-service and risk-service routes. Used by the Counterparty
 * Exposure consistency acceptance tests (trader-review P0 #6) where the gateway
 * merges the trade-derived counterparty set with the credit-risk snapshot set.
 */
fun Application.module(positionClient: PositionServiceClient, riskClient: RiskServiceClient) {
    module()
    routing {
        positionRoutes(positionClient)
        allRiskRoutes(riskClient, positionClient)
    }
}

fun Application.moduleWithVolSurface(volatilityClient: VolatilityServiceClient) {
    module(); routing { volSurfaceRoutes(volatilityClient) }
}

fun Application.moduleWithDataQuality(positionClient: PositionServiceClient, priceClient: PriceServiceClient, riskClient: RiskServiceClient) {
    module(); routing { positionRoutes(positionClient); priceRoutes(priceClient); varRoutes(riskClient); dataQualityRoutes() }
}

fun Application.moduleWithYieldCurveProxy(httpClient: HttpClient, ratesBaseUrl: String) {
    module(); routing { yieldCurveProxyRoutes(httpClient, ratesBaseUrl) }
}

fun Application.moduleWithAuditProxy(httpClient: HttpClient, auditBaseUrl: String) {
    module(); routing { auditProxyRoutes(httpClient, auditBaseUrl) }
}

fun Application.moduleWithInsightsProxy(httpClient: HttpClient, insightsBaseUrl: String, streamingHttpClient: HttpClient = httpClient) {
    module(); routing { insightsRoutes(httpClient, insightsBaseUrl, streamingHttpClient) }
}

// ---------------------------------------------------------------------------
// JWT-authenticated WebSocket test-harness overloads
// ---------------------------------------------------------------------------

fun Application.module(jwtConfig: JwtConfig, broadcaster: PriceBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module(); configureJwtAuth(jwtConfig, jwkProvider); routing { priceWebSocket(broadcaster, jwtConfig, jwkProvider) }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: PnlBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module(); configureJwtAuth(jwtConfig, jwkProvider); routing { pnlWebSocket(broadcaster, jwtConfig, jwkProvider) }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: AlertBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module(); configureJwtAuth(jwtConfig, jwkProvider); routing { alertWebSocket(broadcaster, jwtConfig, jwkProvider) }
}

fun Application.module(jwtConfig: JwtConfig, broadcaster: CopilotBroadcaster, jwkProvider: com.auth0.jwk.JwkProvider? = null) {
    module(); configureJwtAuth(jwtConfig, jwkProvider); routing { copilotWebSocket(broadcaster, jwtConfig, jwkProvider) }
}

// ---------------------------------------------------------------------------
// Full JWT + RBAC test-harness overload (used by auth/security acceptance tests)
// ---------------------------------------------------------------------------

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
                                val bookId = com.kinetix.common.model.BookId(call.requirePathParam("bookId"))
                                val request = call.receive<BookTradeRequest>()
                                val command = request.toCommand(bookId)
                                val result = positionClient.bookTrade(command)
                                call.respond(HttpStatusCode.Created, result.toResponse())
                            }
                        }
                        requirePermission(Permission.READ_POSITIONS, auditPublisher) {
                            get("/positions") {
                                val bookId = com.kinetix.common.model.BookId(call.requirePathParam("bookId"))
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
                        varRoutes(riskClient); marginRoutes(riskClient); whatIfRoutes(riskClient)
                        preTradeRiskPreviewRoutes(riskClient); positionRiskRoutes(riskClient)
                        dependenciesRoutes(riskClient); sodSnapshotRoutes(riskClient)
                    }
                    crossBookVaRRoutes(riskClient, bookAccessService)
                }
                requirePermission(Permission.READ_RISK, auditPublisher) {
                    requireBookAccess(bookAccessService, auditPublisher) {
                        stressTestRoutes(riskClient); jobHistoryRoutes(riskClient)
                    }
                    marketRegimeRoutes(riskClient)
                }
                requirePermission(Permission.READ_REGULATORY, auditPublisher) {
                    requireBookAccess(bookAccessService, auditPublisher) { regulatoryRoutes(riskClient) }
                }
            }
            if (notificationClient != null) {
                requirePermission(Permission.READ_ALERTS, auditPublisher) { notificationRoutes(notificationClient) }
            }
            if (regulatoryClient != null) {
                requirePermission(Permission.MANAGE_SCENARIOS) {
                    stressScenarioRoutes(regulatoryClient); backtestProxyRoutes(regulatoryClient)
                }
            }
            if (httpClient != null && auditBaseUrl != null) {
                requirePermission(Permission.READ_AUDIT) { auditProxyRoutes(httpClient, auditBaseUrl) }
            }
        }
    }
}

