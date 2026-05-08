package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.persistence.PositionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.math.BigDecimal
import java.util.Currency

private val USD = Currency.getInstance("USD")
private val EUR = Currency.getInstance("EUR")
private val AAPL = InstrumentId("AAPL")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)
private fun eur(amount: String) = Money(BigDecimal(amount), EUR)

private fun position(
    bookId: String = "port-1",
    instrumentId: InstrumentId = AAPL,
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    averageCost: Money = usd("150.00"),
    marketPrice: Money = usd("155.00"),
) = Position(
    bookId = BookId(bookId),
    instrumentId = instrumentId,
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = averageCost,
    marketPrice = marketPrice,
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

class PriceUpdateServiceTest : FunSpec({

    val positionRepo = mockk<PositionRepository>()
    val service = PriceUpdateService(positionRepo)

    beforeEach {
        clearMocks(positionRepo)
    }

    test("updates market price for all positions holding the instrument") {
        val pos1 = position(bookId = "port-1")
        val pos2 = position(bookId = "port-2")
        coEvery { positionRepo.findByInstrumentId(AAPL) } returns listOf(pos1, pos2)
        coEvery { positionRepo.saveAll(any()) } just runs

        val count = service.handle(AAPL, usd("160.00"))

        count shouldBe 2
        coVerify(exactly = 1) {
            positionRepo.saveAll(match { positions ->
                positions.size == 2 && positions.all { it.marketPrice == usd("160.00") }
            })
        }
    }

    test("returns zero when no positions exist for instrument") {
        coEvery { positionRepo.findByInstrumentId(AAPL) } returns emptyList()

        val count = service.handle(AAPL, usd("160.00"))

        count shouldBe 0
        coVerify(exactly = 0) { positionRepo.saveAll(any()) }
    }

    test("skips positions with currency mismatch") {
        val usdPosition = position(bookId = "port-1", averageCost = usd("150.00"), marketPrice = usd("155.00"))
        val eurPosition = position(bookId = "port-2", averageCost = eur("130.00"), marketPrice = eur("135.00"))
        coEvery { positionRepo.findByInstrumentId(AAPL) } returns listOf(usdPosition, eurPosition)
        coEvery { positionRepo.saveAll(any()) } just runs

        val count = service.handle(AAPL, usd("160.00"))

        count shouldBe 1
        coVerify(exactly = 1) {
            positionRepo.saveAll(match { positions ->
                positions.size == 1 && positions.single().bookId == BookId("port-1")
            })
        }
    }

    test("saves all updated positions in a single batch call") {
        val pos1 = position(bookId = "port-1", marketPrice = usd("155.00"))
        val pos2 = position(bookId = "port-2", marketPrice = usd("155.00"))
        coEvery { positionRepo.findByInstrumentId(AAPL) } returns listOf(pos1, pos2)
        val batchSlot = slot<List<Position>>()
        coEvery { positionRepo.saveAll(capture(batchSlot)) } just runs

        service.handle(AAPL, usd("170.00"))

        coVerify(exactly = 1) { positionRepo.saveAll(any()) }
        batchSlot.captured.size shouldBe 2
        batchSlot.captured.map { it.marketPrice }.toSet() shouldBe setOf(usd("170.00"))
        batchSlot.captured.map { it.bookId }.toSet() shouldBe setOf(BookId("port-1"), BookId("port-2"))
    }

    test("saves updated position with correct market price and preserved fields") {
        val pos = position(bookId = "port-1", marketPrice = usd("155.00"))
        coEvery { positionRepo.findByInstrumentId(AAPL) } returns listOf(pos)
        val batchSlot = slot<List<Position>>()
        coEvery { positionRepo.saveAll(capture(batchSlot)) } just runs

        service.handle(AAPL, usd("170.00"))

        val saved = batchSlot.captured.single()
        saved.marketPrice shouldBe usd("170.00")
        saved.bookId shouldBe BookId("port-1")
        saved.quantity.compareTo(BigDecimal("100")) shouldBe 0
        saved.averageCost shouldBe usd("150.00")
    }

    test("does not call saveAll when all positions have currency mismatches") {
        val eurPosition = position(bookId = "port-1", averageCost = eur("130.00"), marketPrice = eur("135.00"))
        coEvery { positionRepo.findByInstrumentId(AAPL) } returns listOf(eurPosition)

        val count = service.handle(AAPL, usd("160.00"))

        count shouldBe 0
        coVerify(exactly = 0) { positionRepo.saveAll(any()) }
    }
})
