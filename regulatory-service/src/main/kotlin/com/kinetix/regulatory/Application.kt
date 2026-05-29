package com.kinetix.regulatory

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.regulatory.error.configureErrorHandling
import com.kinetix.regulatory.client.CorrelationServiceClient
import com.kinetix.regulatory.client.PriceServiceClient
import com.kinetix.regulatory.historical.HistoricalReplayService
import com.kinetix.regulatory.historical.HistoricalScenarioRepository
import com.kinetix.regulatory.historical.historicalScenarioPeriodRoutes
import com.kinetix.regulatory.historical.historicalScenarioRoutes
import com.kinetix.regulatory.metrics.RegulatoryGovernanceMetrics
import com.kinetix.regulatory.persistence.BacktestResultRepository
import com.kinetix.regulatory.persistence.DatabaseConfig
import com.kinetix.regulatory.persistence.DatabaseFactory
import com.kinetix.regulatory.persistence.ExposedBacktestResultRepository
import com.kinetix.regulatory.persistence.ExposedFrtbCalculationRepository
import com.kinetix.regulatory.persistence.ExposedHistoricalScenarioRepository
import com.kinetix.regulatory.persistence.ExposedStressScenarioRepository
import com.kinetix.regulatory.persistence.ExposedStressTestResultRepository
import com.kinetix.regulatory.persistence.FrtbCalculationRepository
import com.kinetix.regulatory.routes.backtestRoutes
import com.kinetix.regulatory.service.BacktestComparisonService
import com.kinetix.regulatory.routes.regulatoryRoutes
import com.kinetix.regulatory.seed.DevDataSeeder
import com.kinetix.regulatory.seed.HistoricalScenarioSeeder
import com.kinetix.regulatory.seed.StressScenarioSeeder
import com.kinetix.regulatory.stress.StressScenarioRepository
import com.kinetix.regulatory.stress.StressScenarioService
import com.kinetix.regulatory.stress.StressTestResultRepository
import com.kinetix.regulatory.stress.stressScenarioRoutes
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import com.kinetix.common.observability.OtelHttpClientInterceptor
import com.kinetix.common.observability.OtelHttpServerPlugin
import com.kinetix.common.observability.OtelInit
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import org.slf4j.event.Level
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>): Unit = EngineMain.main(args)

/**
 * Attribute key under which [module] stores the Prometheus registry backing the
 * `/metrics` endpoint, so the route-wiring [module] overload can bind additional
 * meters (e.g. the model-governance metrics) to the same registry that is
 * scraped.
 */
val MicrometerRegistryKey: io.ktor.util.AttributeKey<PrometheusMeterRegistry> =
    io.ktor.util.AttributeKey("regulatory-service-micrometer-registry")

fun Application.module() {
    log.info("Starting regulatory-service")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(MicrometerRegistryKey, appMicrometerRegistry)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) { json() }
    val otel = OtelInit.init(serviceName = "regulatory-service")
    install(OtelHttpServerPlugin) { openTelemetry = otel }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
    }
    install(OpenApi) {
        info {
            title = "Regulatory Service API"
            version = "1.0.0"
            description = "Manages FRTB calculations and regulatory compliance"
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
    repository: FrtbCalculationRepository,
    client: RiskOrchestratorClient,
    backtestRepository: BacktestResultRepository? = null,
    stressScenarioRepository: StressScenarioRepository? = null,
    stressTestResultRepository: StressTestResultRepository? = null,
    historicalScenarioRepository: HistoricalScenarioRepository? = null,
    auditPublisher: GovernanceAuditPublisher? = null,
    priceServiceClient: PriceServiceClient? = null,
    correlationServiceClient: CorrelationServiceClient? = null,
) {
    module()
    val governanceMetrics = RegulatoryGovernanceMetrics(attributes[MicrometerRegistryKey])
    routing {
        regulatoryRoutes(repository, client, auditPublisher)
        if (backtestRepository != null) {
            backtestRoutes(
                backtestRepository,
                BacktestComparisonService(backtestRepository),
                auditPublisher,
                governanceMetrics,
            )
        }
        if (stressScenarioRepository != null) {
            stressScenarioRoutes(
                StressScenarioService(
                    repository = stressScenarioRepository,
                    resultRepository = stressTestResultRepository,
                    riskOrchestratorClient = client,
                    correlationServiceClient = correlationServiceClient,
                ),
                client,
            )
        }
        if (historicalScenarioRepository != null) {
            val replayService = HistoricalReplayService(historicalScenarioRepository, client, priceServiceClient)
            historicalScenarioPeriodRoutes(historicalScenarioRepository, replayService)
            historicalScenarioRoutes(replayService)
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
        ),
    )

    val repository = ExposedFrtbCalculationRepository(db)
    val backtestRepository = ExposedBacktestResultRepository(db)
    val stressScenarioRepository = ExposedStressScenarioRepository(db)
    val stressTestResultRepository = ExposedStressTestResultRepository(db)
    val historicalScenarioRepository = ExposedHistoricalScenarioRepository(db)

    val riskOrchestratorUrl = environment.config
        .config("services.riskOrchestrator")
        .property("url")
        .getString()

    val regulatoryOtel = OtelInit.init(serviceName = "regulatory-service")
    val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }
        install(OtelHttpClientInterceptor) { openTelemetry = regulatoryOtel }
    }

    val client = RiskOrchestratorClient(httpClient, riskOrchestratorUrl)

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        seedComplete = { seedDone.get() },
    )

    module(repository, client, backtestRepository, stressScenarioRepository, stressTestResultRepository, historicalScenarioRepository)

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

    val seedEnabled = environment.config.propertyOrNull("seed.enabled")?.getString()?.toBoolean() ?: true
    if (seedEnabled) {
        launch {
            DevDataSeeder(repository).seed()
            StressScenarioSeeder(stressScenarioRepository).seed()
            HistoricalScenarioSeeder(historicalScenarioRepository).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }
}
