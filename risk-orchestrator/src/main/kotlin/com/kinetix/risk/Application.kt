package com.kinetix.risk

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.common.resilience.CircuitBreaker
import com.kinetix.common.resilience.CircuitBreakerConfig
import com.kinetix.common.resilience.CircuitBreakerOpenException
import com.kinetix.proto.risk.LiquidityRiskServiceGrpcKt
import com.kinetix.proto.risk.MarketDataDependenciesServiceGrpcKt
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt
import com.kinetix.proto.risk.RegulatoryReportingServiceGrpcKt
import com.kinetix.proto.risk.StressTestServiceGrpcKt
import com.kinetix.risk.cache.InMemoryQuantDiffCache
import com.kinetix.risk.cache.InMemoryVaRCache
import com.kinetix.risk.cache.QuantDiffCache
import com.kinetix.risk.cache.RedisQuantDiffCache
import com.kinetix.risk.cache.RedisVaRCache
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.mapper.toValuationResult
import com.kinetix.risk.margin.MarginCalculator
import com.kinetix.risk.client.GrpcLiquidityClient
import com.kinetix.risk.client.GrpcRiskEngineClient
import com.kinetix.risk.client.HttpAuditServiceClient
import com.kinetix.risk.client.HttpHierarchyDataClient
import com.kinetix.risk.client.HttpLimitServiceClient
import com.kinetix.risk.client.HttpPositionServiceClient
import com.kinetix.risk.client.HttpPositionServiceInternalClient
import com.kinetix.risk.client.HttpPriceServiceClient
import com.kinetix.risk.client.HttpRatesServiceClient
import com.kinetix.risk.client.HttpReferenceDataServiceClient
import com.kinetix.risk.client.HttpCorrelationServiceClient
import com.kinetix.risk.client.HttpInstrumentServiceClient
import com.kinetix.risk.client.HttpVolatilityServiceClient
import com.kinetix.risk.client.PositionServicePositionProvider
import com.kinetix.risk.client.ResilientRiskEngineClient
import com.kinetix.risk.kafka.KafkaIntradayPnlPublisher
import com.kinetix.risk.kafka.KafkaRiskResultPublisher
import com.kinetix.risk.kafka.PriceEventConsumer
import com.kinetix.risk.kafka.TradeEventConsumer
import com.kinetix.risk.persistence.ExposedDailyRiskSnapshotRepository
import com.kinetix.risk.persistence.ExposedLiquidityRiskSnapshotRepository
import com.kinetix.risk.persistence.ExposedRiskHierarchySnapshotRepository
import com.kinetix.risk.persistence.ExposedRunManifestRepository
import com.kinetix.risk.persistence.ExposedSodBaselineRepository
import com.kinetix.risk.persistence.ExposedValuationJobRecorder
import com.kinetix.risk.persistence.PostgresMarketDataBlobStore
import com.kinetix.risk.persistence.RiskDatabaseConfig
import com.kinetix.risk.persistence.RiskDatabaseFactory
import com.kinetix.common.health.CheckResult
import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.kafka.ConsumerLivenessTracker
import com.kinetix.risk.routes.benchmarkAttributionRoutes
import com.kinetix.risk.routes.demoResetRoutes
import com.kinetix.risk.routes.crossBookVaRRoutes
import com.kinetix.risk.routes.croReportRoutes
import com.kinetix.risk.routes.hierarchyRiskRoutes
import com.kinetix.risk.routes.riskBudgetRoutes
import com.kinetix.risk.routes.intradayPnlRoutes
import com.kinetix.risk.routes.intradayVaRTimelineRoutes
import com.kinetix.risk.routes.factorRiskRoutes
import com.kinetix.risk.routes.liquidityRiskRoutes
import com.kinetix.risk.routes.marginRoutes
import com.kinetix.risk.routes.riskRoutes
import com.kinetix.risk.routes.jobHistoryRoutes
import com.kinetix.risk.routes.eodPromotionRoutes
import com.kinetix.risk.routes.runComparisonRoutes
import com.kinetix.risk.routes.eodTimelineRoutes
import com.kinetix.risk.routes.hedgeRecommendationRoutes
import com.kinetix.risk.routes.counterpartyRiskRoutes
import com.kinetix.risk.routes.reportRoutes
import com.kinetix.risk.persistence.ExposedReportRepository
import com.kinetix.risk.schedule.RiskPositionsFlatRefresher
import com.kinetix.risk.schedule.ScheduledAutoCloseJob
import com.kinetix.risk.service.JdbcReportQueryExecutor
import com.kinetix.risk.service.ReportService
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import com.kinetix.risk.service.InputChangeDiffer
import com.kinetix.risk.service.MarketDataQuantDiffer
import com.kinetix.risk.service.RunComparisonService
import com.kinetix.risk.service.SnapshotDiffer
import com.kinetix.risk.service.VaRAttributionService
import com.kinetix.risk.persistence.ExposedBlobRetentionRepository
import com.kinetix.risk.persistence.ExposedManifestRetentionRepository
import com.kinetix.risk.reconciliation.TradeAuditReconciliationJob
import com.kinetix.risk.schedule.DistributedLock
import com.kinetix.risk.schedule.NoOpDistributedLock
import com.kinetix.risk.schedule.RedisDistributedLock
import com.kinetix.risk.schedule.ScheduledBlobRetentionJob
import com.kinetix.risk.schedule.ScheduledCrossBookVaRCalculator
import com.kinetix.risk.schedule.ScheduledHedgeExpiryJob
import com.kinetix.risk.schedule.ScheduledManifestRetentionJob
import com.kinetix.risk.schedule.ScheduledSodSnapshotJob
import com.kinetix.risk.schedule.ScheduledVaRCalculator
import com.kinetix.risk.service.DefaultRunManifestCapture
import com.kinetix.risk.service.DependenciesDiscoverer
import com.kinetix.risk.service.IntradayPnlService
import com.kinetix.risk.service.PriceBasedFxRateProvider
import com.kinetix.risk.service.IntradayVaRTimelineService
import com.kinetix.risk.service.PositionServiceTradeProvider
import com.kinetix.risk.persistence.ExposedIntradayVaRTimelineRepository
import com.kinetix.risk.service.MarketDataFetcher
import com.kinetix.risk.service.PnlAttributionService
import com.kinetix.risk.service.PnlComputationService
import com.kinetix.risk.service.SodSnapshotService
import com.kinetix.risk.service.VaRCalculationService
import com.kinetix.risk.service.HierarchyRiskService
import com.kinetix.risk.service.LiquidityRiskService
import com.kinetix.risk.service.BatchStressTestService
import com.kinetix.risk.service.StressLimitCheckService
import com.kinetix.risk.service.WhatIfAnalysisService
import com.kinetix.risk.service.AnalyticalHedgeCalculator
import com.kinetix.risk.service.HedgeRecommendationService
import com.kinetix.risk.persistence.ExposedHedgeRecommendationRepository
import com.kinetix.risk.persistence.ExposedCounterpartyExposureRepository
import com.kinetix.risk.client.GrpcCounterpartyRiskClient
import com.kinetix.risk.client.GrpcSaCcrClient
import com.kinetix.proto.risk.CounterpartyRiskServiceGrpcKt
import com.kinetix.proto.risk.SaCcrServiceGrpcKt
import com.kinetix.proto.risk.AttributionServiceGrpcKt
import com.kinetix.risk.client.GrpcAttributionClient
import com.kinetix.risk.client.HttpBenchmarkServiceClient
import com.kinetix.risk.service.BenchmarkAttributionService
import com.kinetix.risk.service.CounterpartyRiskOrchestrationService
import com.kinetix.risk.service.SaCcrService
import com.kinetix.risk.routes.saCcrRoutes
import com.kinetix.risk.routes.keyRateDurationRoutes
import com.kinetix.risk.client.GrpcRiskEngineKrdClient
import com.kinetix.risk.service.KeyRateDurationService
import com.kinetix.risk.simulation.*
import com.kinetix.risk.resilience.CircuitBreakerMetrics
import io.lettuce.core.RedisClient
import io.grpc.ManagedChannelBuilder
import io.grpc.TlsChannelCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import java.io.File
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level
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

private val MeterRegistryKey = io.ktor.util.AttributeKey<io.micrometer.core.instrument.MeterRegistry>("MeterRegistry")

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    log.info("Starting risk-orchestrator")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(MeterRegistryKey, appMicrometerRegistry)
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
            title = "Risk Orchestrator API"
            version = "1.0.0"
            description = "Orchestrates VaR calculations, stress tests, Greeks and regulatory reporting"
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
internal data class ErrorBody(val error: String, val message: String)

fun Application.moduleWithRoutes() {
    val grpcConfig = environment.config.config("grpc")
    val grpcHost = grpcConfig.property("host").getString()
    val grpcPort = grpcConfig.property("port").getString().toInt()

    val tlsEnabled = grpcConfig.propertyOrNull("tls.enabled")?.getString()?.toBoolean() ?: false
    val maxMessageSize = 50 * 1024 * 1024 // 50 MB
    val channel = if (tlsEnabled) {
        val caPath = grpcConfig.property("tls.caPath").getString()
        val creds = TlsChannelCredentials.newBuilder()
            .trustManager(File(caPath))
            .build()
        io.grpc.Grpc.newChannelBuilder("$grpcHost:$grpcPort", creds)
            .maxInboundMessageSize(maxMessageSize)
            .build()
    } else {
        ManagedChannelBuilder
            .forAddress(grpcHost, grpcPort)
            .usePlaintext()
            .maxInboundMessageSize(maxMessageSize)
            .build()
    }

    val meterRegistry = attributes.getOrNull(MeterRegistryKey) ?: io.micrometer.core.instrument.simple.SimpleMeterRegistry()

    val dependenciesStub = MarketDataDependenciesServiceGrpcKt.MarketDataDependenciesServiceCoroutineStub(channel)
    val grpcRiskEngineClient = GrpcRiskEngineClient(
        RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub(channel),
        dependenciesStub,
    )
    val circuitBreaker = CircuitBreaker(
        CircuitBreakerConfig(failureThreshold = 3, resetTimeoutMs = 15_000, halfOpenMaxCalls = 2, name = "risk-engine"),
        onStateChange = CircuitBreakerMetrics.onStateChange("risk-engine", meterRegistry),
    )
    val riskEngineClient = ResilientRiskEngineClient(grpcRiskEngineClient, circuitBreaker)

    val priceServiceBaseUrl = environment.config
        .propertyOrNull("priceService.baseUrl")?.getString() ?: "http://localhost:8082"
    val priceHttpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 2_000
        }
    }
    val priceServiceClient = HttpPriceServiceClient(priceHttpClient, priceServiceBaseUrl)
    val ratesServiceBaseUrl = environment.config
        .propertyOrNull("ratesService.baseUrl")?.getString() ?: "http://localhost:8088"
    val ratesServiceClient = HttpRatesServiceClient(priceHttpClient, ratesServiceBaseUrl)
    val referenceDataServiceBaseUrl = environment.config
        .propertyOrNull("referenceDataService.baseUrl")?.getString() ?: "http://localhost:8089"
    val referenceDataServiceClient = HttpReferenceDataServiceClient(priceHttpClient, referenceDataServiceBaseUrl)
    val instrumentServiceClient = HttpInstrumentServiceClient(priceHttpClient, referenceDataServiceBaseUrl)
    val volatilityServiceBaseUrl = environment.config
        .propertyOrNull("volatilityService.baseUrl")?.getString() ?: "http://localhost:8090"
    val volatilityServiceClient = HttpVolatilityServiceClient(priceHttpClient, volatilityServiceBaseUrl)
    val correlationServiceBaseUrl = environment.config
        .propertyOrNull("correlationService.baseUrl")?.getString() ?: "http://localhost:8091"
    val correlationServiceClient = HttpCorrelationServiceClient(priceHttpClient, correlationServiceBaseUrl)
    val positionServiceBaseUrl = environment.config
        .propertyOrNull("positionService.baseUrl")?.getString() ?: "http://localhost:8081"
    val positionServiceClient = HttpPositionServiceClient(priceHttpClient, positionServiceBaseUrl)
    val positionProvider = PositionServicePositionProvider(positionServiceClient)
    val limitServiceClient = HttpLimitServiceClient(priceHttpClient, positionServiceBaseUrl)
    val stressLimitCheckService = StressLimitCheckService(limitServiceClient)
    val auditServiceBaseUrl = environment.config
        .propertyOrNull("auditService.baseUrl")?.getString() ?: "http://localhost:8087"
    val positionServiceInternalClient = HttpPositionServiceInternalClient(priceHttpClient, positionServiceBaseUrl)
    val auditServiceClient = HttpAuditServiceClient(priceHttpClient, auditServiceBaseUrl)

    val simulationDelays = SimulationDelays.from(environment.config)
    if (simulationDelays != null) {
        log.info("Simulation delays ENABLED: $simulationDelays")
    }

    val effectivePositionProvider = simulationDelays?.let {
        DelayingPositionProvider(positionProvider, it.fetchPositionsMs)
    } ?: positionProvider

    val effectiveRiskEngineClient = simulationDelays?.let {
        DelayingRiskEngineClient(riskEngineClient, it.discoverDependenciesMs, it.calculateVaRMs)
    } ?: riskEngineClient

    val effectivePriceServiceClient = simulationDelays?.let {
        DelayingPriceServiceClient(priceServiceClient, it.fetchMarketDataPerCallMs)
    } ?: priceServiceClient

    val effectiveRatesServiceClient = simulationDelays?.let {
        DelayingRatesServiceClient(ratesServiceClient, it.fetchMarketDataPerCallMs)
    } ?: ratesServiceClient

    val effectiveReferenceDataServiceClient = simulationDelays?.let {
        DelayingReferenceDataServiceClient(referenceDataServiceClient, it.fetchMarketDataPerCallMs)
    } ?: referenceDataServiceClient

    val effectiveVolatilityServiceClient = simulationDelays?.let {
        DelayingVolatilityServiceClient(volatilityServiceClient, it.fetchMarketDataPerCallMs)
    } ?: volatilityServiceClient

    val effectiveCorrelationServiceClient = simulationDelays?.let {
        DelayingCorrelationServiceClient(correlationServiceClient, it.fetchMarketDataPerCallMs)
    } ?: correlationServiceClient

    val dependenciesDiscoverer = DependenciesDiscoverer(effectiveRiskEngineClient)

    val marketDataCbConfig = CircuitBreakerConfig(failureThreshold = 5, resetTimeoutMs = 30_000, halfOpenMaxCalls = 2)
    val priceServiceCircuitBreaker = CircuitBreaker(
        marketDataCbConfig.copy(name = "price-service"),
        onStateChange = CircuitBreakerMetrics.onStateChange("price-service", meterRegistry),
    )
    val ratesServiceCircuitBreaker = CircuitBreaker(
        marketDataCbConfig.copy(name = "rates-service"),
        onStateChange = CircuitBreakerMetrics.onStateChange("rates-service", meterRegistry),
    )
    val referenceDataCircuitBreaker = CircuitBreaker(
        marketDataCbConfig.copy(name = "reference-data-service"),
        onStateChange = CircuitBreakerMetrics.onStateChange("reference-data-service", meterRegistry),
    )
    val volatilityCircuitBreaker = CircuitBreaker(
        marketDataCbConfig.copy(name = "volatility-service"),
        onStateChange = CircuitBreakerMetrics.onStateChange("volatility-service", meterRegistry),
    )
    val correlationCircuitBreaker = CircuitBreaker(
        marketDataCbConfig.copy(name = "correlation-service"),
        onStateChange = CircuitBreakerMetrics.onStateChange("correlation-service", meterRegistry),
    )

    val marketDataFetcher = MarketDataFetcher(
        effectivePriceServiceClient, effectiveRatesServiceClient, effectiveReferenceDataServiceClient,
        effectiveVolatilityServiceClient, effectiveCorrelationServiceClient,
        priceServiceBaseUrl = priceServiceBaseUrl,
        ratesServiceBaseUrl = ratesServiceBaseUrl,
        referenceDataServiceBaseUrl = referenceDataServiceBaseUrl,
        volatilityServiceBaseUrl = volatilityServiceBaseUrl,
        correlationServiceBaseUrl = correlationServiceBaseUrl,
        priceCircuitBreaker = priceServiceCircuitBreaker,
        ratesCircuitBreaker = ratesServiceCircuitBreaker,
        referenceDataCircuitBreaker = referenceDataCircuitBreaker,
        volatilityCircuitBreaker = volatilityCircuitBreaker,
        correlationCircuitBreaker = correlationCircuitBreaker,
    )

    val riskDbConfig = environment.config.config("riskDatabase")
    val riskDb = RiskDatabaseFactory.init(
        RiskDatabaseConfig(
            jdbcUrl = riskDbConfig.property("jdbcUrl").getString(),
            username = riskDbConfig.property("username").getString(),
            password = riskDbConfig.property("password").getString(),
        )
    )
    val jobRecorder = ExposedValuationJobRecorder(riskDb)
    val manifestRepo = ExposedRunManifestRepository(riskDb)
    val blobStore = PostgresMarketDataBlobStore(riskDb)
    val runManifestCapture = DefaultRunManifestCapture(manifestRepo, blobStore)
    val pnlAttributionRepository = com.kinetix.risk.persistence.ExposedPnlAttributionRepository(riskDb)
    val dailyRiskSnapshotRepository = ExposedDailyRiskSnapshotRepository(riskDb)
    val sodBaselineRepository = ExposedSodBaselineRepository(riskDb)
    val sodGreekSnapshotRepository = com.kinetix.risk.persistence.ExposedSodGreekSnapshotRepository(riskDb)
    val liquidityRiskSnapshotRepository = ExposedLiquidityRiskSnapshotRepository(riskDb)
    val factorDecompositionRepository = com.kinetix.risk.persistence.ExposedFactorDecompositionRepository(riskDb)
    val hierarchySnapshotRepository = ExposedRiskHierarchySnapshotRepository(riskDb)
    val riskBudgetAllocationRepository = com.kinetix.risk.persistence.ExposedRiskBudgetAllocationRepository(riskDb)

    val liquidityRiskServiceCoroutineStub = LiquidityRiskServiceGrpcKt.LiquidityRiskServiceCoroutineStub(channel)
    val grpcLiquidityClient = GrpcLiquidityClient(liquidityRiskServiceCoroutineStub)
    val liquidityRiskService = LiquidityRiskService(
        positionProvider = effectivePositionProvider,
        referenceDataClient = effectiveReferenceDataServiceClient,
        grpcLiquidityClient = grpcLiquidityClient,
        repository = liquidityRiskSnapshotRepository,
    )

    val attributionServiceCoroutineStub = AttributionServiceGrpcKt.AttributionServiceCoroutineStub(channel)
    val grpcAttributionClient = GrpcAttributionClient(attributionServiceCoroutineStub)
    val benchmarkServiceClient = HttpBenchmarkServiceClient(priceHttpClient, referenceDataServiceBaseUrl)
    val benchmarkAttributionService = BenchmarkAttributionService(
        positionProvider = effectivePositionProvider,
        benchmarkServiceClient = benchmarkServiceClient,
        attributionEngineClient = grpcAttributionClient,
    )

    val counterpartyRiskServiceCoroutineStub = CounterpartyRiskServiceGrpcKt.CounterpartyRiskServiceCoroutineStub(channel)
    val grpcCounterpartyRiskClient = GrpcCounterpartyRiskClient(counterpartyRiskServiceCoroutineStub)
    val counterpartyExposureRepository = ExposedCounterpartyExposureRepository(riskDb)
    val counterpartyRiskOrchestrationService = CounterpartyRiskOrchestrationService(
        referenceDataClient = effectiveReferenceDataServiceClient,
        counterpartyRiskClient = grpcCounterpartyRiskClient,
        positionServiceClient = positionServiceClient,
        repository = counterpartyExposureRepository,
    )

    val saCcrStub = SaCcrServiceGrpcKt.SaCcrServiceCoroutineStub(channel)
    val grpcSaCcrClient = GrpcSaCcrClient(saCcrStub)
    val saCcrService = SaCcrService(
        referenceDataClient = effectiveReferenceDataServiceClient,
        saCcrClient = grpcSaCcrClient,
    )

    launch {
        val resetCount = jobRecorder.resetOrphanedRunningJobs()
        if (resetCount > 0) log.warn("Reset {} orphaned RUNNING valuation jobs to FAILED", resetCount)
    }

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()

    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val resultPublisher = KafkaRiskResultPublisher(kafkaProducer)
    val eodEventPublisher = com.kinetix.risk.kafka.KafkaOfficialEodPublisher(kafkaProducer)
    val eodPromotionService = com.kinetix.risk.service.EodPromotionService(
        jobRecorder = jobRecorder,
        eventPublisher = eodEventPublisher,
        meterRegistry = meterRegistry,
        matViewRefresher = {
            newSuspendedTransaction(db = riskDb) {
                exec("REFRESH MATERIALIZED VIEW CONCURRENTLY daily_official_eod_summary")
            }
            RiskPositionsFlatRefresher(riskDb).refresh()
        },
        manifestRepository = manifestRepo,
    )

    val factorConcentrationAlertPublisher = com.kinetix.risk.kafka.KafkaFactorConcentrationAlertPublisher(kafkaProducer)
    val factorRiskService = com.kinetix.risk.service.FactorRiskService(
        riskEngineClient = effectiveRiskEngineClient,
        repository = factorDecompositionRepository,
        positionProvider = effectivePositionProvider,
        priceServiceClient = effectivePriceServiceClient,
        concentrationAlertPublisher = factorConcentrationAlertPublisher,
    )

    val varCalculationService = VaRCalculationService(
        effectivePositionProvider, effectiveRiskEngineClient, resultPublisher,
        dependenciesDiscoverer = dependenciesDiscoverer,
        marketDataFetcher = marketDataFetcher,
        jobRecorder = jobRecorder,
        runManifestCapture = runManifestCapture,
        instrumentServiceClient = instrumentServiceClient,
    )
    val redisUrl = environment.config.propertyOrNull("redis.url")?.getString().orEmpty()
    val redisConnection = if (redisUrl.isNotBlank()) {
        val client = RedisClient.create(redisUrl)
        client.connect()
    } else null

    val distributedLock: DistributedLock = if (redisConnection != null) {
        RedisDistributedLock(redisConnection)
    } else {
        NoOpDistributedLock()
    }

    val varCache: VaRCache = if (redisConnection != null) {
        val ttl = environment.config.propertyOrNull("redis.ttlSeconds")?.getString()?.toLongOrNull() ?: 300L
        log.info("Using RedisVaRCache at {}", redisUrl)
        RedisVaRCache(redisConnection, ttl)
    } else {
        log.info("No REDIS_URL configured, using InMemoryVaRCache")
        InMemoryVaRCache()
    }

    val crossBookResultPublisher = com.kinetix.risk.kafka.KafkaCrossBookRiskResultPublisher(kafkaProducer)
    val crossBookVaRCache = com.kinetix.risk.cache.InMemoryCrossBookVaRCache()
    val crossBookVaRService = com.kinetix.risk.service.CrossBookVaRCalculationService(
        effectivePositionProvider, effectiveRiskEngineClient, crossBookResultPublisher,
        varCache = varCache,
        dependenciesDiscoverer = dependenciesDiscoverer,
        marketDataFetcher = marketDataFetcher,
    )

    val hierarchyDataClient = HttpHierarchyDataClient(
        httpClient = priceHttpClient,
        referenceDataBaseUrl = referenceDataServiceBaseUrl,
        positionServiceBaseUrl = positionServiceBaseUrl,
    )
    val budgetBreachAlertPublisher = com.kinetix.risk.kafka.KafkaBudgetBreachAlertPublisher(kafkaProducer)
    val budgetUtilisationService = com.kinetix.risk.service.BudgetUtilisationService(
        budgetRepository = riskBudgetAllocationRepository,
        alertPublisher = budgetBreachAlertPublisher,
    )
    val hierarchyRiskService = HierarchyRiskService(
        hierarchyDataClient = hierarchyDataClient,
        crossBookVaRService = crossBookVaRService,
        snapshotRepository = hierarchySnapshotRepository,
        varCache = varCache,
        budgetUtilisationService = budgetUtilisationService,
    )

    val quantDiffCache: QuantDiffCache = if (redisConnection != null) {
        log.info("Using RedisQuantDiffCache (24hr TTL)")
        RedisQuantDiffCache(redisConnection)
    } else {
        InMemoryQuantDiffCache()
    }

    val pricingGreeksClient = com.kinetix.risk.client.GrpcPricingGreeksClient(
        RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub(channel),
    )
    val sodSnapshotService = SodSnapshotService(
        sodBaselineRepository = sodBaselineRepository,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        varCache = varCache,
        varCalculationService = varCalculationService,
        positionProvider = effectivePositionProvider,
        jobRecorder = jobRecorder,
        volatilityServiceClient = effectiveVolatilityServiceClient,
        ratesServiceClient = effectiveRatesServiceClient,
        pricingGreeksClient = pricingGreeksClient,
        sodGreekSnapshotRepository = sodGreekSnapshotRepository,
    )
    val pnlAttributionService = PnlAttributionService()
    val pnlComputationService = PnlComputationService(
        sodSnapshotService = sodSnapshotService,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        pnlAttributionService = pnlAttributionService,
        pnlAttributionRepository = pnlAttributionRepository,
        varCache = varCache,
        positionProvider = effectivePositionProvider,
        volatilityServiceClient = effectiveVolatilityServiceClient,
        ratesServiceClient = effectiveRatesServiceClient,
        sodGreekSnapshotRepository = sodGreekSnapshotRepository,
    )

    val intradayPnlRepository = com.kinetix.risk.persistence.ExposedIntradayPnlRepository(riskDb)
    val intradayPnlPublisher = KafkaIntradayPnlPublisher(kafkaProducer)
    val fxRateProvider = PriceBasedFxRateProvider(effectivePriceServiceClient)
    val intradayPnlService = IntradayPnlService(
        sodBaselineRepository = sodBaselineRepository,
        dailyRiskSnapshotRepository = dailyRiskSnapshotRepository,
        intradayPnlRepository = intradayPnlRepository,
        positionProvider = effectivePositionProvider,
        pnlAttributionService = pnlAttributionService,
        publisher = intradayPnlPublisher,
        volatilityServiceClient = effectiveVolatilityServiceClient,
        ratesServiceClient = effectiveRatesServiceClient,
        fxRateProvider = fxRateProvider,
        sodGreekSnapshotRepository = sodGreekSnapshotRepository,
    )

    val stressTestStub = StressTestServiceGrpcKt.StressTestServiceCoroutineStub(channel)
    val regulatoryStub = RegulatoryReportingServiceGrpcKt.RegulatoryReportingServiceCoroutineStub(channel)

    module()

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody("bad_request", cause.message ?: "Invalid request"),
            )
        }
        exception<CircuitBreakerOpenException> { call, _ ->
            call.response.header("Retry-After", "30")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorBody("service_unavailable", "Risk engine temporarily unavailable"),
            )
        }
        exception<io.grpc.StatusRuntimeException> { call, cause ->
            when (cause.status.code) {
                io.grpc.Status.Code.DEADLINE_EXCEEDED -> {
                    call.respond(
                        HttpStatusCode.GatewayTimeout,
                        ErrorBody("gateway_timeout", "Risk calculation timed out"),
                    )
                }
                io.grpc.Status.Code.INVALID_ARGUMENT -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorBody("bad_request", cause.status.description ?: "Invalid argument"),
                    )
                }
                io.grpc.Status.Code.UNAVAILABLE, io.grpc.Status.Code.RESOURCE_EXHAUSTED -> {
                    call.response.header("Retry-After", "5")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorBody("service_unavailable", cause.status.description ?: "Service unavailable"),
                    )
                }
                else -> {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorBody("bad_gateway", cause.status.description ?: "Risk engine error"),
                    )
                }
            }
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorBody("internal_error", "An unexpected error occurred"),
            )
        }
    }

    val snapshotDiffer = SnapshotDiffer()
    val inputChangeDiffer = InputChangeDiffer()
    val marketDataQuantDiffer = MarketDataQuantDiffer()
    val runComparisonService = RunComparisonService(jobRecorder, snapshotDiffer, manifestRepo, inputChangeDiffer)
    val varAttributionService = VaRAttributionService(effectiveRiskEngineClient, effectivePositionProvider)

    val tradeConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "risk-orchestrator-trades-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val tradesLivenessTracker = ConsumerLivenessTracker(topic = "trades.lifecycle", groupId = "risk-orchestrator-trades-group")
    val tradeRetryableConsumer = RetryableConsumer(
        topic = "trades.lifecycle",
        dlqProducer = kafkaProducer,
        livenessTracker = tradesLivenessTracker,
    )
    val tradeEventConsumer = TradeEventConsumer(
        KafkaConsumer<String, String>(tradeConsumerProps),
        varCalculationService,
        varCache = varCache,
        intradayPnlService = intradayPnlService,
        retryableConsumer = tradeRetryableConsumer,
    )

    val priceConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "risk-orchestrator-prices-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val pricesLivenessTracker = ConsumerLivenessTracker(topic = "price.updates", groupId = "risk-orchestrator-prices-group")
    val priceRetryableConsumer = RetryableConsumer(
        topic = "price.updates",
        dlqProducer = kafkaProducer,
        livenessTracker = pricesLivenessTracker,
    )
    val priceEventCooldownMs = environment.config
        .propertyOrNull("priceEventCooldownMs")?.getString()?.toLongOrNull() ?: 60_000L
    val priceEventConsumer = PriceEventConsumer(
        KafkaConsumer<String, String>(priceConsumerProps),
        varCalculationService,
        affectedPortfolios = { when (val r = positionServiceClient.getDistinctBookIds()) {
                is com.kinetix.risk.client.ClientResponse.Success -> r.value
                else -> emptyList()
            } },
        varCache = varCache,
        intradayPnlService = intradayPnlService,
        retryableConsumer = priceRetryableConsumer,
        cooldownMs = priceEventCooldownMs,
    )

    val readinessChecker = ReadinessChecker(
        dataSource = RiskDatabaseFactory.dataSource,
        flywayLocation = RiskDatabaseFactory.FLYWAY_LOCATION,
        consumerTrackers = listOf(tradesLivenessTracker, pricesLivenessTracker),
        extraChecks = mapOf(
            "circuitBreaker" to { CheckResult(status = "OK", details = mapOf("state" to circuitBreaker.currentState.name)) },
            "cache" to { CheckResult(status = "OK", details = mapOf("implementation" to (varCache::class.simpleName ?: "unknown"))) },
        ),
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

    val hedgeRecommendationRepository = ExposedHedgeRecommendationRepository(riskDb)
    val hedgeRecommendationService = HedgeRecommendationService(
        varCache = varCache,
        instrumentServiceClient = instrumentServiceClient,
        priceServiceClient = effectivePriceServiceClient,
        referenceDataClient = effectiveReferenceDataServiceClient,
        volatilityServiceClient = effectiveVolatilityServiceClient,
        calculator = AnalyticalHedgeCalculator(),
        repository = hedgeRecommendationRepository,
    )

    routing {
        val whatIfAnalysisService = WhatIfAnalysisService(effectivePositionProvider, effectiveRiskEngineClient)
        val rebalancingWhatIfService = com.kinetix.risk.service.RebalancingWhatIfService(effectivePositionProvider, effectiveRiskEngineClient, whatIfAnalysisService)
        val batchStressTestService = BatchStressTestService(stressTestStub, effectivePositionProvider)
        riskRoutes(varCalculationService, varCache, effectivePositionProvider, stressTestStub, regulatoryStub, effectiveRiskEngineClient, whatIfAnalysisService = whatIfAnalysisService, rebalancingWhatIfService = rebalancingWhatIfService, pnlAttributionRepository = pnlAttributionRepository, sodSnapshotService = sodSnapshotService, pnlComputationService = pnlComputationService, stressLimitCheckService = stressLimitCheckService, jobRecorder = jobRecorder, batchStressTestService = batchStressTestService)
        crossBookVaRRoutes(crossBookVaRService, crossBookVaRCache)
        hierarchyRiskRoutes(hierarchyRiskService)
        riskBudgetRoutes(riskBudgetAllocationRepository)
        croReportRoutes(hierarchyRiskService)
        intradayPnlRoutes(intradayPnlRepository)
        intradayVaRTimelineRoutes(
            IntradayVaRTimelineService(
                repository = ExposedIntradayVaRTimelineRepository(riskDb),
                tradeProvider = PositionServiceTradeProvider(positionServiceClient),
            )
        )
        liquidityRiskRoutes(liquidityRiskService, liquidityRiskSnapshotRepository)
        marginRoutes(effectivePositionProvider, MarginCalculator())
        factorRiskRoutes(factorDecompositionRepository)
        jobHistoryRoutes(jobRecorder)
        eodPromotionRoutes(eodPromotionService)
        eodTimelineRoutes(jobRecorder)
        runComparisonRoutes(runComparisonService, jobRecorder, varAttributionService, effectiveRiskEngineClient, effectivePositionProvider, manifestRepo, blobStore, marketDataQuantDiffer, quantDiffCache, meterRegistry)
        hedgeRecommendationRoutes(hedgeRecommendationService)
        counterpartyRiskRoutes(counterpartyRiskOrchestrationService)
        saCcrRoutes(saCcrService)
        keyRateDurationRoutes(
            KeyRateDurationService(
                positionProvider = effectivePositionProvider,
                ratesServiceClient = effectiveRatesServiceClient,
                grpcKrdClient = GrpcRiskEngineKrdClient(
                    RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub(channel),
                ),
                instrumentServiceClient = instrumentServiceClient,
            )
        )

        val reportRepository = ExposedReportRepository(riskDb)
        val reportQueryExecutor = JdbcReportQueryExecutor(RiskDatabaseFactory.dataSource)
        val reportService = ReportService(reportRepository, reportQueryExecutor)
        reportRoutes(reportService)
        benchmarkAttributionRoutes(benchmarkAttributionService)

        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(riskDb, jobRecorder, counterpartyExposureRepository, pnlAttributionRepository, demoResetToken)
        }
    }

    launch {
        com.kinetix.risk.seed.DevDataSeeder(jobRecorder, counterpartyExposureRepository, pnlAttributionRepository).seed()
        seedCacheFromDb(varCache, jobRecorder)
    }

    val schedulerEnabled = environment.config
        .propertyOrNull("scheduler.enabled")?.getString()?.toBoolean() ?: true

    launch { tradeEventConsumer.start() }
    if (schedulerEnabled) {
    launch { priceEventConsumer.start() }
    launch {
        ScheduledVaRCalculator(
            varCalculationService = varCalculationService,
            varCache = varCache,
            bookIds = { when (val r = positionServiceClient.getDistinctBookIds()) {
                is com.kinetix.risk.client.ClientResponse.Success -> r.value
                else -> emptyList()
            } },
            factorRiskService = factorRiskService,
            hierarchyRiskService = hierarchyRiskService,
            lock = distributedLock,
        ).start()
    }
    launch {
        ScheduledSodSnapshotJob(
            sodSnapshotService = sodSnapshotService,
            bookIds = { when (val r = positionServiceClient.getDistinctBookIds()) {
                is com.kinetix.risk.client.ClientResponse.Success -> r.value
                else -> emptyList()
            } },
            lock = distributedLock,
        ).start()
    }
    val autoCloseTimeStr = environment.config.propertyOrNull("autoClose.time")?.getString() ?: "17:30"
    launch {
        ScheduledAutoCloseJob(
            varCalculationService = varCalculationService,
            eodPromotionService = eodPromotionService,
            jobRecorder = jobRecorder,
            bookIds = { when (val r = positionServiceClient.getDistinctBookIds()) {
                is com.kinetix.risk.client.ClientResponse.Success -> r.value
                else -> emptyList()
            } },
            closeTime = java.time.LocalTime.parse(autoCloseTimeStr),
            lock = distributedLock,
        ).start()
    }
    launch {
        ScheduledBlobRetentionJob(
            blobRetentionRepository = ExposedBlobRetentionRepository(riskDb),
            lock = distributedLock,
        ).start()
    }
    launch {
        ScheduledManifestRetentionJob(
            manifestRetentionRepository = ExposedManifestRetentionRepository(riskDb),
            lock = distributedLock,
        ).start()
    }
    launch {
        ScheduledHedgeExpiryJob(
            repository = hedgeRecommendationRepository,
            lock = distributedLock,
        ).start()
    }
    launch {
        TradeAuditReconciliationJob(
            positionServiceClient = positionServiceInternalClient,
            auditServiceClient = auditServiceClient,
        ).start()
    }

    // Scheduled cross-book VaR: parse group definitions from config
    // Format: "groupId1:bookA,bookB;groupId2:bookC,bookD"
    val riskGroupsConfig = environment.config.propertyOrNull("riskGroups")?.getString().orEmpty()
    if (riskGroupsConfig.isNotBlank()) {
        val groupDefs = riskGroupsConfig.split(";").filter { it.contains(":") }.associate { entry ->
            val (groupId, booksStr) = entry.split(":", limit = 2)
            groupId.trim() to booksStr.split(",").map { com.kinetix.common.model.BookId(it.trim()) }
        }
        log.info("Configured {} cross-book VaR groups: {}", groupDefs.size, groupDefs.keys)
        launch {
            ScheduledCrossBookVaRCalculator(
                crossBookVaRService = crossBookVaRService,
                crossBookVaRCache = crossBookVaRCache,
                groups = { groupDefs },
                lock = distributedLock,
            ).start()
        }
    }
    } else {
        log.info("Scheduler DISABLED — no scheduled or price-event VaR jobs will run")
    }
}

private suspend fun seedCacheFromDb(
    cache: VaRCache,
    recorder: com.kinetix.risk.service.ValuationJobRecorder,
) {
    val log = org.slf4j.LoggerFactory.getLogger("CacheSeeder")
    try {
        val portfolios = recorder.findDistinctBookIds()
        var seeded = 0
        for (bookId in portfolios) {
            val job = recorder.findLatestCompleted(bookId) ?: continue
            val result = job.toValuationResult() ?: continue
            cache.put(bookId, result)
            seeded++
            log.info("Seeded VaR cache for portfolio {} from job {} (calculated {})", bookId, job.jobId, job.completedAt)
        }
        log.info("Cache seeding complete: {}/{} portfolios seeded", seeded, portfolios.size)
    } catch (e: Exception) {
        log.warn("Cache seeding failed, starting cold: {}", e.message)
    }
}
