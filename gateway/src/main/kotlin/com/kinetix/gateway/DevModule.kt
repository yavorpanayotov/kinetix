package com.kinetix.gateway

import com.kinetix.gateway.auth.InMemoryBookAccessService
import com.kinetix.gateway.auth.JwtConfig
import com.kinetix.gateway.auth.configureJwtAuth
import com.kinetix.gateway.auth.requireBookAccess
import com.kinetix.gateway.auth.requirePermission
import com.kinetix.gateway.client.HttpInstrumentServiceClient
import com.kinetix.gateway.client.HttpNotificationServiceClient
import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.client.HttpPriceServiceClient
import com.kinetix.gateway.client.HttpRegulatoryServiceClient
import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.client.HttpVolatilityServiceClient
import com.kinetix.gateway.kafka.KafkaIntradayPnlConsumer
import com.kinetix.gateway.routes.CopilotInternalAuth
import com.kinetix.gateway.routes.auditProxyRoutes
import com.kinetix.gateway.routes.backtestProxyRoutes
import com.kinetix.gateway.routes.benchmarkAttributionRoutes
import com.kinetix.gateway.routes.counterpartyRiskRoutes
import com.kinetix.gateway.routes.crossBookVaRRoutes
import com.kinetix.gateway.routes.croReportRoutes
import com.kinetix.gateway.routes.dataQualityRoutes
import com.kinetix.gateway.routes.demoAdminRoutes
import com.kinetix.gateway.routes.demoScenarioRoutes
import com.kinetix.gateway.routes.demoStressWindowsRoutes
import com.kinetix.gateway.routes.dependenciesRoutes
import com.kinetix.gateway.routes.eodTimelineRoutes
import com.kinetix.gateway.routes.executionProxyRoutes
import com.kinetix.gateway.routes.factorRiskRoutes
import com.kinetix.gateway.routes.hedgeRecommendationRoutes
import com.kinetix.gateway.routes.hierarchyRiskRoutes
import com.kinetix.gateway.routes.hierarchyRoutes
import com.kinetix.gateway.routes.insightsRoutes
import com.kinetix.gateway.routes.instrumentRoutes
import com.kinetix.gateway.routes.intradayPnlProxyRoutes
import com.kinetix.gateway.routes.intradayVaRTimelineProxyRoutes
import com.kinetix.gateway.routes.jobHistoryRoutes
import com.kinetix.gateway.routes.keyRateDurationRoutes
import com.kinetix.gateway.routes.limitsRoutes
import com.kinetix.gateway.routes.liquidityRiskRoutes
import com.kinetix.gateway.routes.marginRoutes
import com.kinetix.gateway.routes.marketRegimeRoutes
import com.kinetix.gateway.routes.notificationRoutes
import com.kinetix.gateway.routes.positionRiskRoutes
import com.kinetix.gateway.routes.positionRoutes
import com.kinetix.gateway.routes.preTradeRiskPreviewRoutes
import com.kinetix.gateway.routes.priceRoutes
import com.kinetix.gateway.routes.regulatoryRoutes
import com.kinetix.gateway.routes.reportProxyRoutes
import com.kinetix.gateway.routes.riskBudgetRoutes
import com.kinetix.gateway.routes.runComparisonRoutes
import com.kinetix.gateway.routes.saCcrRoutes
import com.kinetix.gateway.routes.sodSnapshotRoutes
import com.kinetix.gateway.routes.strategyProxyRoutes
import com.kinetix.gateway.routes.stressScenarioRoutes
import com.kinetix.gateway.routes.stressTestRoutes
import com.kinetix.gateway.routes.systemHealthRoutes
import com.kinetix.gateway.routes.tapeReplayStatusRoutes
import com.kinetix.gateway.routes.traderRoutes
import com.kinetix.gateway.routes.varRoutes
import com.kinetix.gateway.routes.volSurfaceRoutes
import com.kinetix.gateway.routes.whatIfRoutes
import com.kinetix.gateway.routes.yieldCurveProxyRoutes
import com.kinetix.gateway.routes.copilotInternalRoutes
import com.kinetix.gateway.websocket.AlertBroadcaster
import com.kinetix.gateway.websocket.CopilotBroadcaster
import com.kinetix.gateway.websocket.PnlBroadcaster
import com.kinetix.gateway.websocket.PriceBroadcaster
import com.kinetix.gateway.websocket.alertWebSocket
import com.kinetix.gateway.websocket.copilotWebSocket
import com.kinetix.gateway.websocket.pnlWebSocket
import com.kinetix.gateway.websocket.priceWebSocket
import com.kinetix.common.security.Permission
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Properties

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
    val appLog = log

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

    routing {
        priceWebSocket(priceBroadcaster, wsJwtConfig, wsJwkProvider)
        pnlWebSocket(pnlBroadcaster, wsJwtConfig, wsJwkProvider)
        alertWebSocket(alertBroadcaster, wsJwtConfig, wsJwkProvider)
        copilotWebSocket(copilotBroadcaster, wsJwtConfig, wsJwkProvider)

        // System health is public so CI/k8s probes can reach it without a JWT.
        systemHealthRoutes(httpClient, serviceUrls)

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
                    preTradeRiskPreviewRoutes(riskClient)
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
                // P0 #6 — the gateway merges the trade-derived counterparty
                // set so the Risk-tab tile and the dedicated Counterparty
                // Risk tab cannot disagree on names.
                counterpartyRiskRoutes(riskClient, positionClient)
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
                hierarchyRoutes(httpClient, referenceDataUrl, positionClient)
                dataQualityRoutes(httpClient, positionUrl, priceUrl, riskUrl)
            }
            requirePermission(Permission.READ_AUDIT, authEnabled = authEnabled) {
                auditProxyRoutes(httpClient, auditUrl)
            }
        }
        if (authEnabled) {
            authenticate("auth-jwt") { apiRoutes() }
        } else {
            appLog.warn("GATEWAY_AUTH_ENABLED=false — JWT authentication is DISABLED (smoke/dev mode)")
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
        appLog.warn("COPILOT_INTERNAL_TOKEN not set — POST /internal/copilot/push is disabled")
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
