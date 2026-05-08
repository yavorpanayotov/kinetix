package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.persistence.NettingSetTradeRepository
import com.kinetix.position.persistence.TradeEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val TRADED_AT = Instant.parse("2025-01-15T10:00:00Z")

private fun trade(
    tradeId: String,
    side: Side = Side.BUY,
    quantity: String = "100",
    price: String = "150.00",
    counterpartyId: String = "CP-001",
) = Trade(
    tradeId = TradeId(tradeId),
    bookId = BookId("port-1"),
    instrumentId = InstrumentId("AAPL"),
    assetClass = AssetClass.EQUITY,
    side = side,
    quantity = BigDecimal(quantity),
    price = Money(BigDecimal(price), USD),
    tradedAt = TRADED_AT,
    counterpartyId = counterpartyId,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class NettingSetAwareExposureTest : FunSpec({

    val tradeEventRepo = mockk<TradeEventRepository>()
    val nettingSetRepo = mockk<NettingSetTradeRepository>()
    val service = CounterpartyExposureService(tradeEventRepo, nettingSetRepo)

    context("netting-set-aware exposure calculation") {

        test("trades in the same netting set net against each other") {
            // BUY 100 @ 150 and SELL 60 @ 150 in the same netting set
            // net = 100*150 - 60*150 = 15000 - 9000 = 6000
            val trades = listOf(
                trade("t-1", Side.BUY, "100", "150.00"),
                trade("t-2", Side.SELL, "60", "150.00"),
            )
            coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades
            coEvery { nettingSetRepo.findNettingSetsByTradeIds(listOf("t-1", "t-2")) } returns
                mapOf("t-1" to "NS-001", "t-2" to "NS-001")

            val result = service.getExposures(BookId("port-1"))

            result shouldHaveSize 1
            val exposure = result[0]
            exposure.netExposure.compareTo(BigDecimal("6000.00")) shouldBe 0
            exposure.nettingSetBreakdown shouldHaveSize 1
            exposure.nettingSetBreakdown[0].nettingSetId shouldBe "NS-001"
            exposure.nettingSetBreakdown[0].netExposure.compareTo(BigDecimal("6000.00")) shouldBe 0
        }

        test("trades in different netting sets do not net against each other") {
            // NS-001: BUY 100 @ 150 (15000), SELL 80 @ 150 (12000) -> net = 3000
            // NS-002: SELL 200 @ 150 (30000) -> net = 0 (floored, can't be negative)
            // Overall net = 3000 + 0 = 3000 (not 15000 - 12000 - 30000 = -27000)
            val trades = listOf(
                trade("t-1", Side.BUY, "100", "150.00"),
                trade("t-2", Side.SELL, "80", "150.00"),
                trade("t-3", Side.SELL, "200", "150.00"),
            )
            coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades
            coEvery { nettingSetRepo.findNettingSetsByTradeIds(listOf("t-1", "t-2", "t-3")) } returns
                mapOf("t-1" to "NS-001", "t-2" to "NS-001", "t-3" to "NS-002")

            val result = service.getExposures(BookId("port-1"))

            result shouldHaveSize 1
            val exposure = result[0]
            // NS-001 net = 3000, NS-002 net = max(0, -30000) = 0; total = 3000
            exposure.netExposure.compareTo(BigDecimal("3000.00")) shouldBe 0
            exposure.nettingSetBreakdown shouldHaveSize 2

            val ns1 = exposure.nettingSetBreakdown.first { it.nettingSetId == "NS-001" }
            ns1.netExposure.compareTo(BigDecimal("3000.00")) shouldBe 0

            val ns2 = exposure.nettingSetBreakdown.first { it.nettingSetId == "NS-002" }
            // Floored at zero (we are net short, which means counterparty exposure is zero for us)
            ns2.netExposure.compareTo(BigDecimal.ZERO) shouldBe 0
        }

        test("trades not in any netting set are each treated independently, floored at zero") {
            // No netting: SELL 100 @ 150 — exposure = max(0, -15000) = 0
            val trades = listOf(
                trade("t-1", Side.SELL, "100", "150.00"),
            )
            coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades
            coEvery { nettingSetRepo.findNettingSetsByTradeIds(listOf("t-1")) } returns emptyMap()

            val result = service.getExposures(BookId("port-1"))

            result shouldHaveSize 1
            val exposure = result[0]
            // Un-netted trade floored at zero
            exposure.netExposure.compareTo(BigDecimal.ZERO) shouldBe 0
        }

        test("un-netted trades do not net against trades in a netting set") {
            // NS-001: SELL 100 @ 150 -> net = 0 (floored)
            // No netting set: BUY 200 @ 150 -> treated independently, net = 30000
            // Total = 0 + 30000 = 30000 (BUY in standalone doesn't offset SELL in NS-001)
            val trades = listOf(
                trade("t-1", Side.SELL, "100", "150.00"),
                trade("t-2", Side.BUY, "200", "150.00"),
            )
            coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades
            coEvery { nettingSetRepo.findNettingSetsByTradeIds(listOf("t-1", "t-2")) } returns
                mapOf("t-1" to "NS-001")

            val result = service.getExposures(BookId("port-1"))

            result shouldHaveSize 1
            val exposure = result[0]
            // NS-001 net = max(0, -15000) = 0; standalone t-2 = max(0, 30000) = 30000
            exposure.netExposure.compareTo(BigDecimal("30000.00")) shouldBe 0
        }

        test("gross exposure is always the sum of all notionals regardless of netting") {
            val trades = listOf(
                trade("t-1", Side.BUY, "100", "150.00"),
                trade("t-2", Side.SELL, "200", "150.00"),
            )
            coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades
            coEvery { nettingSetRepo.findNettingSetsByTradeIds(listOf("t-1", "t-2")) } returns
                mapOf("t-1" to "NS-001", "t-2" to "NS-001")

            val result = service.getExposures(BookId("port-1"))

            result shouldHaveSize 1
            // Gross = |15000| + |30000| = 45000 regardless of netting
            result[0].grossExposure.compareTo(BigDecimal("45000.00")) shouldBe 0
        }

        test("multiple counterparties are computed independently") {
            val trades = listOf(
                trade("t-1", Side.BUY, "100", "100.00", "CP-001"),
                trade("t-2", Side.BUY, "50", "100.00", "CP-002"),
            )
            coEvery { tradeEventRepo.findByBookId(BookId("port-1")) } returns trades
            coEvery { nettingSetRepo.findNettingSetsByTradeIds(listOf("t-1", "t-2")) } returns emptyMap()

            val result = service.getExposures(BookId("port-1"))

            result shouldHaveSize 2
            result.first { it.counterpartyId == "CP-001" }.netExposure.compareTo(BigDecimal("10000.00")) shouldBe 0
            result.first { it.counterpartyId == "CP-002" }.netExposure.compareTo(BigDecimal("5000.00")) shouldBe 0
        }
    }
})
