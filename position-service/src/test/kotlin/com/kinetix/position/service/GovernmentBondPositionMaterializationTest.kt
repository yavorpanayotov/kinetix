package com.kinetix.position.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.TraderId
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.kafka.NoOpTradeEventPublisher
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.routes.PositionResponse
import com.kinetix.position.routes.positionRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Integration test for the trader-review P0 #4 bug: `UST-*` rows on the
 * Risk → Position Risk Breakdown were showing `Mkt Value = $0.00`
 * despite a steady stream of `UST-10Y` trades arriving on the
 * trade-event topic.
 *
 * After P0 #3 the simulator tags new Treasury trades as
 * `assetClass = FIXED_INCOME` / `instrumentType = GOVERNMENT_BOND`, so
 * the taxonomy is now correct at booking time. The remaining gap is
 * materialization: a freshly booked Treasury position must surface a
 * non-zero `marketValue` through the position-service's external read
 * API (the one the gateway calls) — otherwise the per-instrument Risk
 * table renders `$0.00` until a price tick happens to arrive.
 *
 * Tests assert that a `UST-10Y` BUY at $97.50 for 1,000 produces:
 *  - `marketValue > 0` on the persisted Position
 *  - `assetClass = FIXED_INCOME` and `instrumentType = GOVERNMENT_BOND`
 *    on both the persisted Position AND the HTTP projection consumed
 *    by the gateway
 *  - the GET /api/v1/books/{book}/positions endpoint (the gateway's
 *    only read path for positions) carries the same non-zero
 *    marketValue
 */
private val USD: Currency = Currency.getInstance("USD")
private val BOOK = BookId("rates-book-1")
private val UST_10Y = InstrumentId("UST-10Y")
private val TRADED_AT: Instant = Instant.parse("2026-05-28T10:14:15Z")

class GovernmentBondPositionMaterializationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val tradeRepo = ExposedTradeEventRepository(db)
    val positionRepo = ExposedPositionRepository(db)
    val transactional = ExposedTransactionalRunner(db)
    val publisher = NoOpTradeEventPublisher()
    val tradeBookingService = TradeBookingService(
        tradeEventRepository = tradeRepo,
        positionRepository = positionRepo,
        transactional = transactional,
        tradeEventPublisher = publisher,
    )
    val positionQueryService = PositionQueryService(positionRepo)
    val tradeLifecycleService = TradeLifecycleService(
        tradeEventRepository = tradeRepo,
        positionRepository = positionRepo,
        transactional = transactional,
        tradeEventPublisher = publisher,
    )
    val portfolioAggregationService = PortfolioAggregationService(
        positionRepository = positionRepo,
        fxRateProvider = StaticFxRateProvider(emptyMap()),
    )

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events, positions RESTART IDENTITY CASCADE")
        }
    }

    test("booking a UST-10Y BUY trade materializes a FIXED_INCOME/GOVERNMENT_BOND position with non-zero market value") {
        val command = BookTradeCommand(
            tradeId = TradeId("ust-10y-buy-1"),
            bookId = BOOK,
            instrumentId = UST_10Y,
            assetClass = AssetClass.FIXED_INCOME,
            side = Side.BUY,
            quantity = BigDecimal("1000"),
            price = Money(BigDecimal("97.50"), USD),
            tradedAt = TRADED_AT,
            instrumentType = InstrumentTypeCode.GOVERNMENT_BOND.name,
            traderId = TraderId("tr-rates-001"),
        )

        tradeBookingService.handle(command)

        val position = positionRepo.findByKey(BOOK, UST_10Y)
        position!!.assetClass shouldBe AssetClass.FIXED_INCOME
        position.instrumentType shouldBe InstrumentTypeCode.GOVERNMENT_BOND
        position.quantity.compareTo(BigDecimal("1000")) shouldBe 0
        // The bug: marketPrice is left at zero after booking because only
        // PriceConsumer / mark-to-market updates it. A freshly booked
        // position must seed marketPrice from the trade so the firm sees a
        // realistic last-trade market value until a price tick lands.
        (position.marketValue.amount > BigDecimal.ZERO) shouldBe true
    }

    test("GET /api/v1/books/{bookId}/positions returns the UST-10Y position as FIXED_INCOME / GOVERNMENT_BOND with non-zero market value") {
        val command = BookTradeCommand(
            tradeId = TradeId("ust-10y-buy-2"),
            bookId = BOOK,
            instrumentId = UST_10Y,
            assetClass = AssetClass.FIXED_INCOME,
            side = Side.BUY,
            quantity = BigDecimal("1000"),
            price = Money(BigDecimal("97.50"), USD),
            tradedAt = TRADED_AT,
            instrumentType = InstrumentTypeCode.GOVERNMENT_BOND.name,
            traderId = TraderId("tr-rates-002"),
        )
        tradeBookingService.handle(command)

        testApplication {
            application {
                configureTestApp(
                    positionQueryService = positionQueryService,
                    positionRepository = positionRepo,
                    tradeBookingService = tradeBookingService,
                    tradeEventRepository = tradeRepo,
                    tradeLifecycleService = tradeLifecycleService,
                    portfolioAggregationService = portfolioAggregationService,
                )
            }

            val httpClient = createClient { install(ClientContentNegotiation) { json() } }
            val response = httpClient.get("/api/v1/books/${BOOK.value}/positions")
            response.status shouldBe HttpStatusCode.OK

            val positions: List<PositionResponse> = response.body()
            val ust10y = positions.single { it.instrumentId == UST_10Y.value }

            // Gateway must see the position under the canonical FIXED_INCOME
            // asset class with GOVERNMENT_BOND as the instrument type — NOT
            // EQUITY / CASH_EQUITY as it used to be before the taxonomy fix.
            ust10y.assetClass shouldBe AssetClass.FIXED_INCOME.name
            ust10y.instrumentType shouldBe InstrumentTypeCode.GOVERNMENT_BOND.name

            // And the market value the gateway forwards must be non-zero so
            // the Risk → Position Risk Breakdown row doesn't render $0.00.
            (BigDecimal(ust10y.marketValue.amount) > BigDecimal.ZERO) shouldBe true
        }
    }
})

private fun Application.configureTestApp(
    positionRepository: com.kinetix.position.persistence.PositionRepository,
    positionQueryService: PositionQueryService,
    tradeBookingService: TradeBookingService,
    tradeEventRepository: com.kinetix.position.persistence.TradeEventRepository,
    tradeLifecycleService: TradeLifecycleService,
    portfolioAggregationService: PortfolioAggregationService,
) {
    install(ContentNegotiation) { json() }
    routing {
        positionRoutes(
            positionRepository,
            positionQueryService,
            tradeBookingService,
            tradeEventRepository,
            tradeLifecycleService,
            portfolioAggregationService,
        )
    }
}
