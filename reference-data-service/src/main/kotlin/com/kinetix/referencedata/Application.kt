package com.kinetix.referencedata

import com.kinetix.common.health.ReadinessChecker
import com.kinetix.referencedata.cache.RedisReferenceDataCache
import com.kinetix.referencedata.grpc.ReferenceDataGrpcServer
import com.kinetix.referencedata.grpc.TraderLookupServiceImpl
import com.kinetix.referencedata.kafka.KafkaReferenceDataPublisher
import com.kinetix.referencedata.persistence.CreditSpreadRepository
import com.kinetix.referencedata.persistence.DatabaseConfig
import com.kinetix.referencedata.routes.demoResetRoutes
import com.kinetix.referencedata.seed.DevDataSeeder
import com.kinetix.referencedata.persistence.DatabaseFactory
import com.kinetix.referencedata.persistence.DeskRepository
import com.kinetix.referencedata.persistence.DivisionRepository
import com.kinetix.referencedata.persistence.DividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedBenchmarkRepository
import com.kinetix.referencedata.persistence.ExposedCounterpartyRepository
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDeskRepository
import com.kinetix.referencedata.persistence.ExposedDivisionRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentLiquidityRepository
import com.kinetix.referencedata.persistence.ExposedNettingAgreementRepository
import com.kinetix.referencedata.persistence.ExposedTraderRepository
import com.kinetix.referencedata.routes.benchmarkRoutes
import com.kinetix.referencedata.routes.counterpartyRoutes
import com.kinetix.referencedata.routes.deskRoutes
import com.kinetix.referencedata.routes.divisionRoutes
import com.kinetix.referencedata.routes.instrumentRoutes
import com.kinetix.referencedata.routes.liquidityRoutes
import com.kinetix.referencedata.routes.referenceDataRoutes
import com.kinetix.referencedata.routes.traderRoutes
import com.kinetix.referencedata.service.BenchmarkService
import com.kinetix.referencedata.service.CounterpartyService
import com.kinetix.referencedata.service.DeskService
import com.kinetix.referencedata.service.DivisionService
import com.kinetix.referencedata.service.InstrumentLiquidityService
import com.kinetix.referencedata.service.InstrumentService
import com.kinetix.referencedata.service.ReferenceDataIngestionService
import com.kinetix.referencedata.service.TraderService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import kotlinx.coroutines.launch
import io.ktor.server.netty.EngineMain
import com.kinetix.common.observability.CorrelationIdHttpServerPlugin
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import com.kinetix.referencedata.error.configureErrorHandling
import io.ktor.server.request.header
import org.slf4j.event.Level
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    log.info("Starting reference-data-service")
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) { registry = appMicrometerRegistry }
    install(ContentNegotiation) { json() }
    install(CorrelationIdHttpServerPlugin)
    install(CallLogging) {
        level = Level.INFO
        mdc("correlationId") {
            it.request.header("X-Correlation-ID") ?: java.util.UUID.randomUUID().toString()
        }
    }
    install(OpenApi) {
        info {
            title = "Reference Data Service API"
            version = "1.0.0"
            description = "Manages dividend yields and credit spreads"
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
    dividendYieldRepository: DividendYieldRepository,
    creditSpreadRepository: CreditSpreadRepository,
    ingestionService: ReferenceDataIngestionService,
    instrumentService: InstrumentService? = null,
    divisionService: DivisionService? = null,
    deskService: DeskService? = null,
    liquidityService: InstrumentLiquidityService? = null,
    counterpartyService: CounterpartyService? = null,
    benchmarkService: BenchmarkService? = null,
    traderService: TraderService? = null,
) {
    module()
    routing {
        referenceDataRoutes(dividendYieldRepository, creditSpreadRepository, ingestionService)
        if (instrumentService != null) {
            instrumentRoutes(instrumentService)
        }
        if (divisionService != null && deskService != null) {
            divisionRoutes(divisionService, deskService)
            deskRoutes(deskService)
        }
        if (liquidityService != null) {
            liquidityRoutes(liquidityService)
        }
        if (counterpartyService != null) {
            counterpartyRoutes(counterpartyService)
        }
        if (benchmarkService != null) {
            benchmarkRoutes(benchmarkService)
        }
        if (traderService != null) {
            traderRoutes(traderService)
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

    val dividendYieldRepository = ExposedDividendYieldRepository(db)
    val creditSpreadRepository = ExposedCreditSpreadRepository(db)

    val redisConfig = environment.config.config("redis")
    val redisUrl = redisConfig.property("url").getString()
    val redisClient = RedisClient.create(redisUrl)
    val redisConnection = redisClient.connect()
    val cache = RedisReferenceDataCache(redisConnection)

    val kafkaConfig = environment.config.config("kafka")
    val bootstrapServers = kafkaConfig.property("bootstrapServers").getString()
    val producerProps = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
    }
    val kafkaProducer = KafkaProducer<String, String>(producerProps)
    val publisher = KafkaReferenceDataPublisher(kafkaProducer)

    val ingestionService = ReferenceDataIngestionService(
        dividendYieldRepository, creditSpreadRepository, cache, publisher,
    )

    val instrumentRepository = ExposedInstrumentRepository(db)
    val instrumentService = InstrumentService(instrumentRepository)

    val divisionRepository = ExposedDivisionRepository(db)
    val deskRepository = ExposedDeskRepository(db)
    val divisionService = DivisionService(divisionRepository)
    val deskService = DeskService(deskRepository, divisionRepository)

    val liquidityRepository = ExposedInstrumentLiquidityRepository(db)
    val liquidityService = InstrumentLiquidityService(liquidityRepository)

    val counterpartyRepository = ExposedCounterpartyRepository(db)
    val nettingAgreementRepository = ExposedNettingAgreementRepository(db)
    val counterpartyService = CounterpartyService(counterpartyRepository, nettingAgreementRepository)

    val seedDone = AtomicBoolean(false)
    val readinessChecker = ReadinessChecker(
        dataSource = DatabaseFactory.dataSource,
        flywayLocation = DatabaseFactory.FLYWAY_LOCATION,
        seedComplete = { seedDone.get() },
    )

    val benchmarkRepository = ExposedBenchmarkRepository(db)
    val benchmarkService = BenchmarkService(benchmarkRepository)

    val traderRepository = ExposedTraderRepository(db)
    val traderService = TraderService(traderRepository, deskRepository)

    val grpcPort = environment.config.propertyOrNull("grpc.port")?.getString()?.toInt() ?: 9107
    val grpcServer = ReferenceDataGrpcServer(
        port = grpcPort,
        services = listOf(TraderLookupServiceImpl(traderService)),
    ).start()

    monitor.subscribe(io.ktor.server.application.ApplicationStopping) {
        grpcServer.stop()
    }

    module(dividendYieldRepository, creditSpreadRepository, ingestionService, instrumentService, divisionService, deskService, liquidityService, counterpartyService, benchmarkService, traderService)

    routing {
        val demoResetToken = System.getenv("DEMO_RESET_TOKEN")
        if (demoResetToken != null) {
            demoResetRoutes(
                db = db,
                dividendYieldRepository = dividendYieldRepository,
                creditSpreadRepository = creditSpreadRepository,
                instrumentRepository = instrumentRepository,
                divisionRepository = divisionRepository,
                deskRepository = deskRepository,
                liquidityRepository = liquidityRepository,
                counterpartyRepository = counterpartyRepository,
                nettingAgreementRepository = nettingAgreementRepository,
                traderRepository = traderRepository,
                resetToken = demoResetToken,
            )
        }

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
            DevDataSeeder(dividendYieldRepository, creditSpreadRepository, instrumentRepository, divisionRepository, deskRepository, liquidityRepository, counterpartyRepository, nettingAgreementRepository, traderRepository).seed()
            seedDone.set(true)
        }
    } else {
        seedDone.set(true)
    }
}
