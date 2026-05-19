package com.kinetix.position

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.common.kafka.ConsumerLivenessTracker
import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.kafka.PriceConsumer
import com.kinetix.position.persistence.DatabaseConfig
import com.kinetix.position.persistence.DatabaseFactory
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedPositionNotesRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.persistence.ExposedLimitDefinitionRepository
import com.kinetix.position.persistence.ExposedTemporaryLimitIncreaseRepository
import com.kinetix.position.fix.ExposedExecutionCostRepository
import com.kinetix.position.fix.ExposedExecutionFillRepository
import com.kinetix.position.fix.ExposedExecutionOrderRepository
import com.kinetix.position.fix.ExposedFIXSessionRepository
import com.kinetix.position.fix.ExposedPrimeBrokerReconciliationRepository
import com.kinetix.position.fix.FIXExecutionReportProcessor
import com.kinetix.position.fix.LoggingFIXOrderSender
import com.kinetix.position.fix.OrderSubmissionService
import com.kinetix.position.fix.PrimeBrokerReconciliationService
import com.kinetix.position.reconciliation.ExposedReconciliationRepository
import com.kinetix.position.reconciliation.PositionReconciliationJob
import com.kinetix.position.routes.bookHierarchyRoutes
import com.kinetix.position.routes.collateralRoutes
import com.kinetix.position.routes.counterpartyRoutes
import com.kinetix.position.routes.executionRoutes
import com.kinetix.position.routes.fixSessionRoutes
import com.kinetix.position.routes.demoResetRoutes
import com.kinetix.position.routes.internalRoutes
import com.kinetix.position.routes.limitRoutes
import com.kinetix.position.routes.orderRoutes
import com.kinetix.position.persistence.ExposedTradeStrategyRepository
import com.kinetix.position.routes.positionNotesRoutes
import com.kinetix.position.routes.positionRoutes
import com.kinetix.position.routes.strategyRoutes
import com.kinetix.position.service.TradeStrategyService
import com.kinetix.position.persistence.ExposedCollateralBalanceRepository
import com.kinetix.position.persistence.ExposedNettingSetTradeRepository
import com.kinetix.position.service.CollateralTrackingService
import com.kinetix.position.routes.preTradeCheckRoutes
import com.kinetix.position.service.CounterpartyExposureService
import com.kinetix.position.seed.DevDataSeeder
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.HierarchyBasedPreTradeCheckService
import com.kinetix.position.service.LimitHierarchyService
import com.kinetix.position.service.PositionNotesService
import com.kinetix.position.service.PositionQueryService
import com.kinetix.position.service.PriceUpdateService
import com.kinetix.position.service.TradeBookingService
import com.kinetix.position.service.PortfolioAggregationService
import com.kinetix.position.service.LiveFxRateProvider
import com.kinetix.position.service.StaticFxRateProvider
import com.kinetix.position.service.TradeLifecycleService
import com.kinetix.position.client.HttpReferenceDataServiceClient
import com.kinetix.position.service.NettingSetAssigner
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import java.math.BigDecimal
import java.util.Currency
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
import org.slf4j.event.Level
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    log.info("Starting position-service")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
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
            title = "Position Service API"
            version = "1.0.0"
            description = "Manages portfolios, positions and trade booking"
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

fun Application.moduleWithRoutes() {
    val dbConfig = environment.config.config("database")
    val db = DatabaseFactory.init(
        DatabaseConfig(
            jdbcUrl = dbConfig.property("jdbcUrl").getString(),
            username = dbConfig.property("username").getString(),
            password = dbConfig.property("password").getString(),
        )
    )

    val positionRepository = ExposedPositionRepository(db)
    val tradeEventRepository = ExposedTradeEventRepository(db)
    val transactionalRunner = ExposedTransactionalRunner(db)
    val bookHierarchyRepository = com.kinetix.position.persistence.ExposedBookHierarchyRepository(db)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()

    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val tradeEventPublisher = KafkaTradeEventPublisher(kafkaProducer)

    val referenceDataBaseUrl = environment.config.config("referenceData").property("baseUrl").getString()
    val referenceDataHttpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    val referenceDataClient = HttpReferenceDataServiceClient(referenceDataHttpClient, referenceDataBaseUrl)

    val limitDefinitionRepo = ExposedLimitDefinitionRepository(db)
    val temporaryLimitIncreaseRepo = ExposedTemporaryLimitIncreaseRepository(db)
    val limitHierarchyService = LimitHierarchyService(limitDefinitionRepo, temporaryLimitIncreaseRepo)
    val preTradeCheckService = HierarchyBasedPreTradeCheckService(positionRepository, limitHierarchyService)

    val nettingSetTradeRepository = ExposedNettingSetTradeRepository(db)
    val nettingSetAssigner = NettingSetAssigner(referenceDataClient, nettingSetTradeRepository)

    // Trader lookup (demo Phase 2 Gap 6). When enabled, booking validates
    // the traderId on every BookTradeCommand against reference-data-service
    // and rejects unknown ids with HTTP 422. Disable for tests / local-dev
    // without reference-data via REFERENCE_DATA_GRPC_ENABLED=false.
    val referenceDataGrpcEnabled =
        System.getenv("REFERENCE_DATA_GRPC_ENABLED")?.toBooleanStrictOrNull() ?: true
    val referenceDataGrpcChannel: io.grpc.ManagedChannel? = if (referenceDataGrpcEnabled) {
        val target = System.getenv("REFERENCE_DATA_GRPC_TARGET") ?: "reference-data-service:9107"
        io.grpc.ManagedChannelBuilder.forTarget(target).usePlaintext().build()
    } else null
    val traderValidator: com.kinetix.position.trader.TraderValidator? = referenceDataGrpcChannel?.let {
        com.kinetix.position.trader.TraderValidator(
            com.kinetix.position.trader.GrpcTraderLookupClient(channel = it),
        )
    }

    val tradeBookingService = TradeBookingService(
        tradeEventRepository = tradeEventRepository,
        positionRepository = positionRepository,
        transactional = transactionalRunner,
        tradeEventPublisher = tradeEventPublisher,
        limitCheckService = preTradeCheckService,
        nettingSetAssigner = nettingSetAssigner,
        traderValidator = traderValidator,
    )
    val tradeStrategyRepository = ExposedTradeStrategyRepository(db)
    val tradeStrategyService = TradeStrategyService(tradeStrategyRepository)

    val positionNotesRepository = ExposedPositionNotesRepository(db)
    val positionNotesService = PositionNotesService(positionNotesRepository)

    val positionQueryService = PositionQueryService(positionRepository)
    val tradeLifecycleService = TradeLifecycleService(
        tradeEventRepository = tradeEventRepository,
        positionRepository = positionRepository,
        transactional = transactionalRunner,
        tradeEventPublisher = tradeEventPublisher,
        nettingSetAssigner = nettingSetAssigner,
    )

    val staticFxRateProvider = StaticFxRateProvider(
        mapOf(
            Currency.getInstance("EUR") to Currency.getInstance("USD") to BigDecimal("1.08"),
            Currency.getInstance("GBP") to Currency.getInstance("USD") to BigDecimal("1.27"),
            Currency.getInstance("JPY") to Currency.getInstance("USD") to BigDecimal("0.0067"),
            Currency.getInstance("USD") to Currency.getInstance("EUR") to BigDecimal("0.93"),
            Currency.getInstance("USD") to Currency.getInstance("GBP") to BigDecimal("0.79"),
            Currency.getInstance("USD") to Currency.getInstance("JPY") to BigDecimal("149.25"),
        )
    )
    val liveFxRateProvider = LiveFxRateProvider(delegate = staticFxRateProvider)
    val counterpartyExposureService = CounterpartyExposureService(tradeEventRepository, nettingSetTradeRepository)
    val collateralBalanceRepository = ExposedCollateralBalanceRepository(db)
    val collateralTrackingService = CollateralTrackingService(collateralBalanceRepository)
    val portfolioAggregationService = PortfolioAggregationService(positionRepository, liveFxRateProvider)

    val reconciliationRepository = ExposedReconciliationRepository(db)
    val reconciliationJob = PositionReconciliationJob(reconciliationRepository)

    // Day/GTD-order expiry sweeper (audit A-13, ADR-0035). The default path
    // routes the cancel through fix-gateway via gRPC (phase 2 commit 3); set
    // FIX_GATEWAY_ENABLED=false to fall back to the in-process logging stub
    // for local-dev / CI without a running fix-gateway.
    val fixGatewayEnabled = System.getenv("FIX_GATEWAY_ENABLED")?.toBooleanStrictOrNull() ?: true
    val fixGatewayChannel: io.grpc.ManagedChannel? = if (fixGatewayEnabled) {
        val target = System.getenv("FIX_GATEWAY_TARGET") ?: "fix-gateway:9105"
        io.grpc.ManagedChannelBuilder.forTarget(target).usePlaintext().build()
    } else null

    val cancelAttemptRecorder = com.kinetix.position.fix.ExposedCancelAttemptRecorder(db)
    val orderCancelEmitter: com.kinetix.common.execution.OrderCancelEmitter = if (fixGatewayChannel != null) {
        com.kinetix.position.fix.GrpcOrderCancelEmitter(
            channel = fixGatewayChannel,
            cancelAttemptRecorder = cancelAttemptRecorder,
        )
    } else {
        com.kinetix.position.fix.LoggingOrderCancelEmitter()
    }
    val venueOpenChecker: com.kinetix.common.execution.VenueOpenChecker = if (fixGatewayChannel != null) {
        com.kinetix.position.fix.GrpcVenueOpenChecker(
            channel = fixGatewayChannel,
            onRpcFailure = { venue, error ->
                log.warn("IsVenueOpen RPC failed venue={} error={}", venue, error.message)
            },
        )
    } else {
        // Local-dev fallback: assume venues are always open. Sweeper still
        // performs state transitions on GTD; DAY orders are never expired.
        com.kinetix.common.execution.VenueOpenChecker { _, _ -> true }
    }

    val executionCostRepository = ExposedExecutionCostRepository(db)
    val primeBrokerReconciliationRepository = ExposedPrimeBrokerReconciliationRepository(db)
    val primeBrokerReconciliationService = PrimeBrokerReconciliationService()
    val fixSessionRepository = ExposedFIXSessionRepository(db)
    val executionOrderRepository = ExposedExecutionOrderRepository(db)
    val executionFillRepository = ExposedExecutionFillRepository(db)

    // FIX ORDER ROUTING — SIMULATION MODE
    //
    // LoggingFIXOrderSender is intentionally the only FIXOrderSender implementation wired here.
    // It logs outbound NewOrderSingle messages to standard output but does NOT open a real FIX
    // session or transmit orders to a broker. This is deliberate: the FIX scaffolding exists to
    // prove the domain model (execution fills, cost analysis, prime-broker reconciliation) end-to-end
    // without requiring live broker connectivity in development and demo environments.
    //
    // The domain model, persistence layer, and execution cost/reconciliation logic are
    // production-ready and fully tested. Only the transport layer is intentionally absent.
    //
    // TO CONNECT TO A REAL BROKER:
    //   1. Add a QuickFIX/J dependency to position-service/build.gradle.kts.
    //   2. Implement FIXOrderSender against the QuickFIX/J Application interface.
    //   3. Replace LoggingFIXOrderSender with your implementation here.
    //   4. Provide a FIX session config file (quickfix.cfg) via an environment variable.
    //
    // See: https://www.quickfixj.org/usermanual/2.3.0/usage/application.html
    val fixOrderSender = LoggingFIXOrderSender()
    // ADR-0035 phase 4: route order placement through fix-gateway when the canary
    // flag is on. Default off until the canary plan (1% → 5% → 10%) reaches each
    // stage; flag flips per cohort. When unset, OrderSubmissionService falls back
    // to the legacy in-process LoggingFIXOrderSender path above.
    val fixGatewayPlaceOrderEnabled =
        System.getenv("FIX_GATEWAY_PLACE_ORDER")?.toBooleanStrictOrNull() ?: false
    val fixGatewayClient: com.kinetix.common.execution.FixGatewayClient? =
        if (fixGatewayChannel != null && fixGatewayPlaceOrderEnabled) {
            com.kinetix.position.fix.GrpcFixGatewayClient(channel = fixGatewayChannel)
        } else null
    val orderSubmissionService = OrderSubmissionService(
        orderRepository = executionOrderRepository,
        sessionRepository = fixSessionRepository,
        fixOrderSender = fixOrderSender,
        preTradeCheckService = preTradeCheckService,
        fixGatewayClient = fixGatewayClient,
    )
    val executionCostService = com.kinetix.position.fix.ExecutionCostService()
    val ghostFillRepository = com.kinetix.position.fix.ExposedGhostFillRepository(db)
    val riskBreakPublisher: com.kinetix.position.kafka.RiskBreakPublisher =
        com.kinetix.position.kafka.NoOpRiskBreakPublisher()
    val executionReportsMeterRegistry =
        io.micrometer.core.instrument.simple.SimpleMeterRegistry()
    val fixExecutionReportProcessor = FIXExecutionReportProcessor(
        orderRepository = executionOrderRepository,
        fillRepository = executionFillRepository,
        tradeBookingService = tradeBookingService,
        executionCostService = executionCostService,
        executionCostRepository = executionCostRepository,
        ghostFillRepository = ghostFillRepository,
        riskBreakPublisher = riskBreakPublisher,
        meterRegistry = executionReportsMeterRegistry,
    )

    val priceUpdateService = PriceUpdateService(positionRepository)
    val consumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "position-service-group")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val priceKafkaConsumer = KafkaConsumer<String, String>(consumerProps)
    val priceTracker = ConsumerLivenessTracker(topic = "price.updates", groupId = "position-service-group")
    val retryableConsumer = RetryableConsumer(
        topic = "price.updates",
        dlqProducer = kafkaProducer,
        livenessTracker = priceTracker,
    )
    val priceConsumer = PriceConsumer(
        priceKafkaConsumer, priceUpdateService,
        retryableConsumer = retryableConsumer,
        liveFxRateProvider = liveFxRateProvider,
    )

    // ADR-0035 phase 3 commit 4: fix-gateway is the sole inbound source for
    // 35=8/9/j events. Position-service consumes them from `execution.reports`
    // and dispatches to FIXExecutionReportProcessor. The legacy in-process
    // wiring and the EXECUTION_REPORTS_VIA_KAFKA dual-path flag have been
    // removed; on outage the Kafka consumer absorbs the gap via offset replay.
    val executionReportsDispatcher = com.kinetix.position.kafka.ExecutionReportDispatcher(
        processor = fixExecutionReportProcessor,
        meterRegistry = executionReportsMeterRegistry,
    )
    val executionReportConsumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, "position-service-execution-reports")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }
    val executionReportsTracker = ConsumerLivenessTracker(
        topic = "execution.reports",
        groupId = "position-service-execution-reports",
    )
    val executionReportConsumer = com.kinetix.position.kafka.ExecutionReportConsumer(
        consumer = KafkaConsumer<String, String>(executionReportConsumerProps),
        dispatcher = executionReportsDispatcher,
        livenessTracker = executionReportsTracker,
        meterRegistry = executionReportsMeterRegistry,
    )

    val seedDone = AtomicBoolean(false)
    val readinessTrackers = listOf(priceTracker, executionReportsTracker)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        consumerTrackers = readinessTrackers,
        seedComplete = { seedDone.get() },
    )

    module()

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

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody("bad_request", cause.message ?: "Invalid request"),
            )
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorBody("conflict", cause.message ?: "Invalid state"),
            )
        }
        exception<com.kinetix.position.service.TradeNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorBody("trade_not_found", cause.message ?: "Trade not found"),
            )
        }
        exception<com.kinetix.position.service.InvalidTradeStateException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorBody("invalid_trade_state", cause.message ?: "Invalid trade state"),
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

    val seederBookingService = TradeBookingService(
        tradeEventRepository = tradeEventRepository,
        positionRepository = positionRepository,
        transactional = transactionalRunner,
        tradeEventPublisher = tradeEventPublisher,
        limitCheckService = null,
    )

    routing {
        positionRoutes(positionRepository, positionQueryService, tradeBookingService, tradeEventRepository, tradeLifecycleService, portfolioAggregationService)
        positionNotesRoutes(positionNotesService)
        strategyRoutes(tradeStrategyService, tradeBookingService)
        limitRoutes(limitDefinitionRepo, temporaryLimitIncreaseRepo)
        counterpartyRoutes(counterpartyExposureService)
        collateralRoutes(collateralTrackingService)
        internalRoutes(tradeEventRepository)
        bookHierarchyRoutes(bookHierarchyRepository)
        preTradeCheckRoutes(preTradeCheckService)
        executionRoutes(executionCostRepository, primeBrokerReconciliationRepository, primeBrokerReconciliationService, positionRepository)
        fixSessionRoutes(fixSessionRepository)
        orderRoutes(orderSubmissionService, ghostFillRepository)

        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(
                db = db,
                tradeBookingService = seederBookingService,
                positionRepository = positionRepository,
                limitDefinitionRepo = limitDefinitionRepo,
                executionCostRepo = executionCostRepository,
                tradeEventRepository = tradeEventRepository,
                tradeLifecycleService = tradeLifecycleService,
                bookHierarchyRepository = bookHierarchyRepository,
                resetToken = demoResetToken,
            )
        }
    }

    launch {
        priceConsumer.start()
    }

    launch { executionReportConsumer.start() }

    launch {
        reconciliationJob.start()
    }

    val orderExpirySweeper = com.kinetix.position.fix.ScheduledOrderExpirySweeper(
        orderRepository = executionOrderRepository,
        venueOpenChecker = venueOpenChecker,
        cancelEmitter = orderCancelEmitter,
    )
    launch {
        orderExpirySweeper.start()
    }

    launch {
        seedDefaultFirmLimits(limitDefinitionRepo)
    }

    val seedEnabled = environment.config.propertyOrNull("seed.enabled")?.getString()?.toBoolean() ?: true
    if (seedEnabled) {
        launch {
            DevDataSeeder(
                tradeBookingService = seederBookingService,
                positionRepository = positionRepository,
                limitDefinitionRepo = limitDefinitionRepo,
                executionCostRepo = executionCostRepository,
                tradeEventRepository = tradeEventRepository,
                bookHierarchyRepository = bookHierarchyRepository,
            ).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }

    // Phase 1 Gap 7 — intraday tape replay. Generates 1–3 trades/min through the
    // production booking path so the demo blotter scrolls and limit-breach
    // notifications fire periodically. Gated by DEMO_TAPE_REPLAY_ENABLED so the
    // sweeper stays off by default outside demo environments.
    val replayEnabled = System.getenv("DEMO_TAPE_REPLAY_ENABLED")?.toBoolean() ?: false
    if (replayEnabled) {
        val replaySweeper = com.kinetix.position.seed.DemoTapeReplaySweeper(
            tradeBookingService = seederBookingService,
            instrumentsByBook = com.kinetix.position.seed.DevDataSeeder.INSTRUMENTS_BY_BOOK_FOR_TAPE,
            readinessGate = { seedDone.get() },
        )
        launch { replaySweeper.start() }
    }
}

private suspend fun seedDefaultFirmLimits(
    limitDefinitionRepo: com.kinetix.position.persistence.LimitDefinitionRepository,
) {
    val defaults = listOf(
        LimitDefinition(
            id = "firm-default-position",
            level = LimitLevel.FIRM,
            entityId = "FIRM",
            limitType = LimitType.POSITION,
            limitValue = BigDecimal("1000000"),
            intradayLimit = null,
            overnightLimit = null,
            active = true,
        ),
        LimitDefinition(
            id = "firm-default-notional",
            level = LimitLevel.FIRM,
            entityId = "FIRM",
            limitType = LimitType.NOTIONAL,
            limitValue = BigDecimal("10000000"),
            intradayLimit = null,
            overnightLimit = null,
            active = true,
        ),
        LimitDefinition(
            id = "firm-default-concentration",
            level = LimitLevel.FIRM,
            entityId = "FIRM",
            limitType = LimitType.CONCENTRATION,
            limitValue = BigDecimal("0.25"),
            intradayLimit = null,
            overnightLimit = null,
            active = true,
        ),
    )
    defaults.forEach { limitDefinitionRepo.save(it) }
}
