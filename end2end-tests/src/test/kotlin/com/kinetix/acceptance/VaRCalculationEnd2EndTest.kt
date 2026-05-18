package com.kinetix.acceptance

import com.kinetix.common.model.*
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.service.*
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionServiceClient
import com.kinetix.risk.client.PositionServicePositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.risk.kafka.KafkaRiskResultPublisher
import com.kinetix.risk.model.*
import com.kinetix.risk.service.VaRCalculationService
import com.kinetix.testsupport.containers.TestcontainerCaps
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan as shouldBeGreaterThanComparable
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Currency
import java.util.Properties
import kotlin.math.sqrt

private val USD = Currency.getInstance("USD")

private class RepositoryBackedPositionServiceClient(
    private val repository: com.kinetix.position.persistence.PositionRepository,
) : PositionServiceClient {
    override suspend fun getPositions(bookId: BookId): ClientResponse<List<Position>> =
        ClientResponse.Success(repository.findByBookId(bookId))

    override suspend fun getDistinctBookIds(): ClientResponse<List<BookId>> =
        ClientResponse.Success(repository.findDistinctBookIds())

    override suspend fun getTradesInRange(
        bookId: BookId,
        from: java.time.Instant,
        to: java.time.Instant,
    ): ClientResponse<List<com.kinetix.risk.client.dtos.TradeDto>> =
        ClientResponse.Success(emptyList())

    override suspend fun getNetCollateral(
        counterpartyId: String,
    ): ClientResponse<com.kinetix.risk.client.dtos.NetCollateralDto> =
        ClientResponse.Success(
            com.kinetix.risk.client.dtos.NetCollateralDto(
                collateralReceived = 0.0,
                collateralPosted = 0.0,
            )
        )

    override suspend fun getInstrumentNettingSets(
        counterpartyId: String,
    ): ClientResponse<Map<String, String>> =
        ClientResponse.Success(emptyMap())

    override suspend fun getTradesByCounterparty(
        counterpartyId: String,
    ): ClientResponse<List<com.kinetix.risk.client.dtos.CounterpartyTradeDto>> =
        ClientResponse.Success(emptyList())
}

private class StubRiskEngineClient : RiskEngineClient {

    private val volatilities = mapOf(
        AssetClass.EQUITY to 0.20,
        AssetClass.FIXED_INCOME to 0.05,
        AssetClass.FX to 0.10,
        AssetClass.COMMODITY to 0.25,
        AssetClass.DERIVATIVE to 0.30,
    )

    private fun compute(
        request: VaRCalculationRequest,
        positions: List<Position>,
    ): Pair<Double, List<ComponentBreakdown>> {
        val zScore = when (request.confidenceLevel) {
            ConfidenceLevel.CL_95 -> 1.645
            ConfidenceLevel.CL_975 -> 1.96
            ConfidenceLevel.CL_99 -> 2.326
        }
        val sqrtTime = sqrt(request.timeHorizonDays.toDouble() / 252.0)

        val componentVars = positions.groupBy { it.assetClass }.map { (assetClass, assetPositions) ->
            val exposure = assetPositions.sumOf {
                val mv = it.marketValue.amount.toDouble()
                if (mv > 0.0) mv else (it.averageCost.amount * it.quantity).toDouble()
            }
            val vol = volatilities[assetClass] ?: 0.15
            val varContrib = exposure * vol * zScore * sqrtTime
            assetClass to varContrib
        }

        val totalVar = componentVars.sumOf { it.second }

        val breakdown = componentVars.map { (assetClass, varContrib) ->
            ComponentBreakdown(
                assetClass = assetClass,
                varContribution = varContrib,
                percentageOfTotal = (varContrib / totalVar) * 100.0,
            )
        }
        return totalVar to breakdown
    }

    override suspend fun calculateVaR(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<com.kinetix.risk.model.MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): VaRResult {
        val (totalVar, breakdown) = compute(request, positions)
        return VaRResult(
            bookId = request.bookId,
            calculationType = request.calculationType,
            confidenceLevel = request.confidenceLevel,
            varValue = totalVar,
            expectedShortfall = totalVar * 1.25,
            componentBreakdown = breakdown,
            calculatedAt = Instant.now(),
        )
    }

    override suspend fun valuate(
        request: VaRCalculationRequest,
        positions: List<Position>,
        marketData: List<com.kinetix.risk.model.MarketDataValue>,
        instrumentMap: Map<String, InstrumentDto>,
    ): ValuationResult {
        val (totalVar, breakdown) = compute(request, positions)
        return ValuationResult(
            bookId = request.bookId,
            calculationType = request.calculationType,
            confidenceLevel = request.confidenceLevel,
            varValue = totalVar,
            expectedShortfall = totalVar * 1.25,
            componentBreakdown = breakdown,
            greeks = null,
            calculatedAt = Instant.now(),
            computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
        )
    }

    override suspend fun discoverDependencies(
        positions: List<Position>,
        calculationType: String,
        confidenceLevel: String,
        instrumentMap: Map<String, InstrumentDto>,
    ) = throw UnsupportedOperationException("Not used in VaR acceptance test")

    override suspend fun decomposeFactorRisk(
        bookId: BookId,
        positions: List<Position>,
        marketData: Map<String, TimeSeriesMarketData>,
        totalVar: Double,
    ): FactorDecompositionSnapshot = throw UnsupportedOperationException("Not used in VaR acceptance test")
}

class VaRCalculationEnd2EndTest : BehaviorSpec({

    // --- Infrastructure ---

    val positionDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("position_test")
            .withUsername("test")
            .withPassword("test"),
    )

    val kafka = TestcontainerCaps.tuneKafka(
        org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1"),
    )

    // --- Services (initialized in beforeSpec) ---

    lateinit var bookingService: TradeBookingService
    lateinit var varService: VaRCalculationService
    lateinit var riskResultConsumer: KafkaConsumer<String, String>

    beforeSpec {
        positionDb.start()
        kafka.start()

        // Wire position-service
        val positionDatabase = com.kinetix.position.persistence.DatabaseFactory.init(
            com.kinetix.position.persistence.DatabaseConfig(
                jdbcUrl = positionDb.jdbcUrl,
                username = positionDb.username,
                password = positionDb.password,
                maxPoolSize = 5,
            )
        )
        val tradeEventRepo = ExposedTradeEventRepository(positionDatabase)
        val positionRepo = ExposedPositionRepository(positionDatabase)
        val transactional = ExposedTransactionalRunner(positionDatabase)

        val producerProps = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        val kafkaProducer = KafkaProducer<String, String>(producerProps)
        val tradePublisher = KafkaTradeEventPublisher(kafkaProducer)

        bookingService = TradeBookingService(tradeEventRepo, positionRepo, transactional, tradePublisher)

        // Wire risk-orchestrator
        val positionClient = RepositoryBackedPositionServiceClient(positionRepo)
        val positionProvider = PositionServicePositionProvider(positionClient)
        val stubRiskEngine = StubRiskEngineClient()
        val riskResultPublisher = KafkaRiskResultPublisher(kafkaProducer)

        varService = VaRCalculationService(positionProvider, stubRiskEngine, riskResultPublisher)

        // Kafka consumer for verification
        val consumerProps = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "var-acceptance-test")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        riskResultConsumer = KafkaConsumer(consumerProps)
        riskResultConsumer.subscribe(listOf("risk.results"))
    }

    afterSpec {
        riskResultConsumer.close()
        positionDb.stop()
        kafka.stop()
    }

    given("a portfolio with positions across EQUITY, FIXED_INCOME, and FX") {
        val bookId = BookId("port-var-1")

        bookingService.handle(
            BookTradeCommand(
                tradeId = TradeId("t-var-equity"),
                bookId = bookId,
                instrumentId = InstrumentId("AAPL"),
                assetClass = AssetClass.EQUITY,
                side = Side.BUY,
                quantity = BigDecimal("100"),
                price = Money(BigDecimal("150.00"), USD),
                tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            )
        )

        bookingService.handle(
            BookTradeCommand(
                tradeId = TradeId("t-var-fi"),
                bookId = bookId,
                instrumentId = InstrumentId("UST10Y"),
                assetClass = AssetClass.FIXED_INCOME,
                side = Side.BUY,
                quantity = BigDecimal("50"),
                price = Money(BigDecimal("98.50"), USD),
                tradedAt = Instant.parse("2025-01-15T10:01:00Z"),
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            )
        )

        bookingService.handle(
            BookTradeCommand(
                tradeId = TradeId("t-var-fx"),
                bookId = bookId,
                instrumentId = InstrumentId("EURUSD"),
                assetClass = AssetClass.FX,
                side = Side.BUY,
                quantity = BigDecimal("10000"),
                price = Money(BigDecimal("1.0850"), USD),
                tradedAt = Instant.parse("2025-01-15T10:02:00Z"),
                instrumentType = "CASH_EQUITY",
                traderId = TraderId("tr-test-001"),
            )
        )

        `when`("VaR calculation is requested for the portfolio") {
            val request = VaRCalculationRequest(
                bookId = bookId,
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )

            val result = varService.calculateVaR(request)

            then("a valid VaR result is returned with positive values") {
                result shouldNotBe null
                result!!.varValue!! shouldBeGreaterThan 0.0
                result.expectedShortfall!! shouldBeGreaterThan result.varValue!!
                result.bookId shouldBe bookId
                result.calculationType shouldBe CalculationType.PARAMETRIC
            }

            then("the component breakdown covers all 3 asset classes") {
                result shouldNotBe null
                result!!.componentBreakdown.size shouldBe 3
                result.componentBreakdown.map { it.assetClass } shouldContainExactlyInAnyOrder listOf(
                    AssetClass.EQUITY,
                    AssetClass.FIXED_INCOME,
                    AssetClass.FX,
                )
                result.componentBreakdown.forEach { component ->
                    component.varContribution shouldBeGreaterThan 0.0
                    component.percentageOfTotal shouldBeGreaterThan 0.0
                }
                val totalPercentage = result.componentBreakdown.sumOf { it.percentageOfTotal }
                totalPercentage shouldBeGreaterThan 99.0
                totalPercentage shouldBeLessThan 101.0
            }

            then("the result is published to the Kafka risk.results topic") {
                val records = riskResultConsumer.poll(Duration.ofSeconds(10))
                records.count() shouldBeGreaterThanComparable 0

                val record = records.first()
                val event = Json.decodeFromString<RiskResultEvent>(record.value())
                event.bookId shouldBe bookId.value
                event.varValue.toDouble() shouldBeGreaterThan 0.0
                event.componentBreakdown.size shouldBe 3
            }
        }
    }
})
