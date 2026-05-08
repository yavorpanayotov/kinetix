package com.kinetix.position.service

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.common.model.TradeId
import com.kinetix.position.client.InstrumentLiquidityClient
import com.kinetix.position.model.LimitBreachSeverity
import com.kinetix.position.model.LimitCheckStatus
import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.persistence.LimitDefinitionRepository
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TemporaryLimitIncreaseRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

private val USD = Currency.getInstance("USD")
private val BOOK = BookId("book-1")
private val AAPL = InstrumentId("AAPL")
private val MSFT = InstrumentId("MSFT")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)

private fun command(
    tradeId: String = "t-1",
    bookId: BookId = BOOK,
    instrumentId: InstrumentId = AAPL,
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "150.00",
) = BookTradeCommand(
    tradeId = TradeId(tradeId),
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = AssetClass.EQUITY,
    side = side,
    quantity = BigDecimal(quantity),
    price = usd(price),
    tradedAt = Instant.parse("2025-01-15T10:00:00Z"),
    instrumentType = "CASH_EQUITY",
)

private fun position(
    bookId: BookId = BOOK,
    instrumentId: InstrumentId = AAPL,
    quantity: String = "0",
    marketPrice: String = "150.00",
    averageCost: String = "150.00",
) = Position(
    bookId = bookId,
    instrumentId = instrumentId,
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal(quantity),
    averageCost = usd(averageCost),
    marketPrice = usd(marketPrice),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun bookLimit(limitType: LimitType, limitValue: String) = LimitDefinition(
    id = UUID.randomUUID().toString(),
    level = LimitLevel.BOOK,
    entityId = BOOK.value,
    limitType = limitType,
    limitValue = BigDecimal(limitValue),
    intradayLimit = null,
    overnightLimit = null,
    active = true,
)

private fun firmLimit(limitType: LimitType, limitValue: String) = LimitDefinition(
    id = UUID.randomUUID().toString(),
    level = LimitLevel.FIRM,
    entityId = "FIRM",
    limitType = limitType,
    limitValue = BigDecimal(limitValue),
    intradayLimit = null,
    overnightLimit = null,
    active = true,
)

class HierarchyBasedPreTradeCheckServiceTest : FunSpec({

    val positionRepo = mockk<PositionRepository>()
    val limitDefinitionRepo = mockk<LimitDefinitionRepository>()
    val temporaryLimitIncreaseRepo = mockk<TemporaryLimitIncreaseRepository>()
    val liquidityClient = mockk<InstrumentLiquidityClient>()
    val hierarchyService = LimitHierarchyService(limitDefinitionRepo, temporaryLimitIncreaseRepo)

    fun service() = HierarchyBasedPreTradeCheckService(positionRepo, hierarchyService, liquidityClient)

    beforeEach {
        clearMocks(positionRepo, limitDefinitionRepo, temporaryLimitIncreaseRepo, liquidityClient)
        coEvery { temporaryLimitIncreaseRepo.findActiveByLimitId(any(), any()) } returns null
        coEvery { limitDefinitionRepo.findByEntityAndType(any(), any(), any()) } returns null
        // Default: large ADV so existing limit tests are unaffected by the ADV check.
        // ADV-specific tests override this per-test.
        coEvery { liquidityClient.getAdv(any()) } returns BigDecimal("1000000000")
    }

    // ── POSITION limit ────────────────────────────────────────────────────────

    test("blocks trade when new quantity would exceed POSITION limit") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns position(quantity = "400")
        coEvery { positionRepo.findByBookId(BOOK) } returns listOf(position(quantity = "400", marketPrice = "150.00"))
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.POSITION) } returns
            bookLimit(LimitType.POSITION, "500")

        // 400 existing + 200 buy = 600 > 500
        val result = service().check(command(quantity = "200"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "POSITION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }

    test("emits SOFT warning when new quantity approaches POSITION limit") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns position(quantity = "300")
        coEvery { positionRepo.findByBookId(BOOK) } returns listOf(position(quantity = "300", marketPrice = "150.00"))
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.POSITION) } returns
            bookLimit(LimitType.POSITION, "500") // 80% = 400; 300+120 = 420 > 400 but < 500

        val result = service().check(command(quantity = "120"))

        result.blocked shouldBe false
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "POSITION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.SOFT
    }

    test("SELL reduces position for POSITION limit calculation") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns position(quantity = "100")
        coEvery { positionRepo.findByBookId(BOOK) } returns listOf(position(quantity = "100", marketPrice = "150.00"))
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.POSITION) } returns
            bookLimit(LimitType.POSITION, "50") // hard at 50

        // 100 - 80 SELL = 20 < 50 → should pass
        val result = service().check(command(side = Side.SELL, quantity = "80"))

        result.blocked shouldBe false
        result.breaches.shouldBeEmpty()
    }

    // ── NOTIONAL limit ────────────────────────────────────────────────────────

    test("blocks trade when new portfolio notional would exceed NOTIONAL limit") {
        // Existing position: 500 AAPL @ $155 market = $77,500
        // Trade: BUY 200 @ $150 = $30,000 notional
        // New total = $107,500 > $100,000
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns position(quantity = "500", marketPrice = "155.00")
        coEvery { positionRepo.findByBookId(BOOK) } returns listOf(
            position(quantity = "500", marketPrice = "155.00"),
        )
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.NOTIONAL) } returns
            bookLimit(LimitType.NOTIONAL, "100000")

        val result = service().check(command(quantity = "200", price = "150.00"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "NOTIONAL"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }

    // ── CONCENTRATION limit ───────────────────────────────────────────────────

    test("blocks trade when instrument concentration would exceed CONCENTRATION limit") {
        // AAPL 100 @ $155 = $15,500 + MSFT 100 @ $300 = $30,000 = total $45,500
        // Trade: BUY 200 AAPL @ $150 → new AAPL mktVal = 300 * $155 = $46,500
        // new total = $45,500 + $30,000 = $75,500
        // concentration = 46,500 / 75,500 ≈ 61.6% > 25%
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns position(quantity = "100", marketPrice = "155.00")
        coEvery { positionRepo.findByBookId(BOOK) } returns listOf(
            position(instrumentId = AAPL, quantity = "100", marketPrice = "155.00"),
            position(instrumentId = MSFT, quantity = "100", marketPrice = "300.00"),
        )
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.CONCENTRATION) } returns
            bookLimit(LimitType.CONCENTRATION, "0.25")

        val result = service().check(command(quantity = "200", price = "150.00"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "CONCENTRATION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }

    // ── No limits configured ──────────────────────────────────────────────────

    test("passes with no breaches when no limit definitions exist in the database") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()

        val result = service().check(command())

        result.blocked shouldBe false
        result.breaches.shouldBeEmpty()
    }

    // ── FIRM fallback ─────────────────────────────────────────────────────────

    test("falls back to FIRM-level limit when no BOOK-level limit is configured") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()
        // No book-level limit, but firm-level position limit of 50 blocks buy of 100
        coEvery { limitDefinitionRepo.findByEntityAndType("FIRM", LimitLevel.FIRM, LimitType.POSITION) } returns
            firmLimit(LimitType.POSITION, "50")

        val result = service().check(command(quantity = "100"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "POSITION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }

    // ── Multiple breaches ─────────────────────────────────────────────────────

    test("reports multiple breaches when both POSITION and NOTIONAL limits are violated") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns position(quantity = "400", marketPrice = "155.00")
        coEvery { positionRepo.findByBookId(BOOK) } returns listOf(
            position(quantity = "400", marketPrice = "155.00"),
        )
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.POSITION) } returns
            bookLimit(LimitType.POSITION, "500") // 400 + 200 = 600 > 500
        coEvery { limitDefinitionRepo.findByEntityAndType(BOOK.value, LimitLevel.BOOK, LimitType.NOTIONAL) } returns
            bookLimit(LimitType.NOTIONAL, "50000") // existing $62k + $30k = $92k > $50k

        val result = service().check(command(quantity = "200", price = "150.00"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 2
        result.breaches.map { it.limitType }.toSet() shouldBe setOf("POSITION", "NOTIONAL")
    }

    // ── ADV_CONCENTRATION check ───────────────────────────────────────────────

    test("blocks trade when ADV data is unavailable (fail-safe)") {
        // Trade: BUY 100 AAPL @ $150 = $15,000 notional
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()
        coEvery { liquidityClient.getAdv(AAPL) } returns null

        val result = service().check(command(quantity = "100", price = "150.00"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "ADV_CONCENTRATION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }

    test("blocks trade when ADV concentration exceeds 10%") {
        // Trade: BUY 100 AAPL @ $150 = $15,000 notional
        // ADV = $100,000 → adv_pct = 15,000 / 100,000 = 15% > 10%
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()
        coEvery { liquidityClient.getAdv(AAPL) } returns BigDecimal("100000")

        val result = service().check(command(quantity = "100", price = "150.00"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "ADV_CONCENTRATION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }

    test("warns when ADV concentration is between 5% and 10%") {
        // Trade: BUY 100 AAPL @ $150 = $15,000 notional
        // ADV = $200,000 → adv_pct = 15,000 / 200,000 = 7.5% — between 5% and 10%
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()
        coEvery { liquidityClient.getAdv(AAPL) } returns BigDecimal("200000")

        val result = service().check(command(quantity = "100", price = "150.00"))

        result.blocked shouldBe false
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "ADV_CONCENTRATION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.SOFT
    }

    test("passes when ADV concentration is below 5%") {
        // Trade: BUY 100 AAPL @ $150 = $15,000 notional
        // ADV = $1,000,000 → adv_pct = 15,000 / 1,000,000 = 1.5% < 5%
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()
        coEvery { liquidityClient.getAdv(AAPL) } returns BigDecimal("1000000")

        val result = service().check(command(quantity = "100", price = "150.00"))

        result.blocked shouldBe false
        result.breaches.shouldBeEmpty()
    }

    test("handles zero ADV gracefully by treating it as unavailable (fail-safe)") {
        coEvery { positionRepo.findByKey(BOOK, AAPL) } returns null
        coEvery { positionRepo.findByBookId(BOOK) } returns emptyList()
        coEvery { liquidityClient.getAdv(AAPL) } returns BigDecimal.ZERO

        val result = service().check(command(quantity = "100", price = "150.00"))

        result.blocked shouldBe true
        result.breaches shouldHaveSize 1
        result.breaches[0].limitType shouldBe "ADV_CONCENTRATION"
        result.breaches[0].severity shouldBe LimitBreachSeverity.HARD
    }
})
