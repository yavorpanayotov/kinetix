package com.kinetix.position.metrics

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.persistence.PositionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.util.Currency

private val USD: Currency = Currency.getInstance("USD")

private fun position(
    bookId: String,
    instrumentId: String,
    quantity: String,
    marketPrice: String,
) = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = AssetClass.EQUITY,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal(marketPrice), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = InstrumentTypeCode.CASH_EQUITY,
)

class PositionExposureGaugeBinderTest : FunSpec({

    val positionRepository = mockk<PositionRepository>()

    beforeEach {
        clearMocks(positionRepository)
    }

    test("registers a position_notional gauge per position tagged by book_id, instrument_id and side") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("book-a"))
        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "AAPL", quantity = "100", marketPrice = "150"),
        )
        val registry = SimpleMeterRegistry()
        val binder = PositionExposureGaugeBinder(positionRepository, registry)

        binder.refresh()

        val gauge = registry.find("position_notional")
            .tag("book_id", "book-a")
            .tag("instrument_id", "AAPL")
            .tag("side", "LONG")
            .gauge()
        gauge.shouldNotBeNull()
        gauge.value() shouldBe 15000.0
    }

    test("tags a short position with side SHORT and reports absolute notional") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("book-a"))
        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "TSLA", quantity = "-40", marketPrice = "200"),
        )
        val registry = SimpleMeterRegistry()
        val binder = PositionExposureGaugeBinder(positionRepository, registry)

        binder.refresh()

        val gauge = registry.find("position_notional")
            .tag("book_id", "book-a")
            .tag("instrument_id", "TSLA")
            .tag("side", "SHORT")
            .gauge()
        gauge.shouldNotBeNull()
        gauge.value() shouldBe 8000.0
    }

    test("registers a position_count gauge per book") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(
            BookId("book-a"),
            BookId("book-b"),
        )
        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "AAPL", quantity = "100", marketPrice = "150"),
            position("book-a", "MSFT", quantity = "50", marketPrice = "300"),
        )
        coEvery { positionRepository.findByBookId(BookId("book-b")) } returns listOf(
            position("book-b", "GOOG", quantity = "10", marketPrice = "120"),
        )
        val registry = SimpleMeterRegistry()
        val binder = PositionExposureGaugeBinder(positionRepository, registry)

        binder.refresh()

        registry.find("position_count").tag("book_id", "book-a").gauge()
            .shouldNotBeNull().value() shouldBe 2.0
        registry.find("position_count").tag("book_id", "book-b").gauge()
            .shouldNotBeNull().value() shouldBe 1.0
    }

    test("excludes flat (zero-quantity) positions from notional and count") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("book-a"))
        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "AAPL", quantity = "100", marketPrice = "150"),
            position("book-a", "FLAT", quantity = "0", marketPrice = "50"),
        )
        val registry = SimpleMeterRegistry()
        val binder = PositionExposureGaugeBinder(positionRepository, registry)

        binder.refresh()

        registry.find("position_notional").tag("instrument_id", "FLAT").gauge() shouldBe null
        registry.find("position_count").tag("book_id", "book-a").gauge()
            .shouldNotBeNull().value() shouldBe 1.0
    }

    test("reflects an updated position notional on the next refresh without re-registering the gauge") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("book-a"))
        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "AAPL", quantity = "100", marketPrice = "150"),
        )
        val registry = SimpleMeterRegistry()
        val binder = PositionExposureGaugeBinder(positionRepository, registry)

        binder.refresh()

        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "AAPL", quantity = "100", marketPrice = "175"),
        )
        binder.refresh()

        registry.find("position_notional")
            .tag("book_id", "book-a")
            .tag("instrument_id", "AAPL")
            .gauges().size shouldBe 1
        registry.find("position_notional")
            .tag("book_id", "book-a")
            .tag("instrument_id", "AAPL")
            .gauge()
            .shouldNotBeNull().value() shouldBe 17500.0
    }

    test("drops the notional gauge to zero when a position is closed between refreshes") {
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("book-a"))
        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns listOf(
            position("book-a", "AAPL", quantity = "100", marketPrice = "150"),
        )
        val registry = SimpleMeterRegistry()
        val binder = PositionExposureGaugeBinder(positionRepository, registry)

        binder.refresh()

        coEvery { positionRepository.findByBookId(BookId("book-a")) } returns emptyList()
        binder.refresh()

        registry.find("position_notional")
            .tag("book_id", "book-a")
            .tag("instrument_id", "AAPL")
            .gauge()
            .shouldNotBeNull().value() shouldBe 0.0
        registry.find("position_count").tag("book_id", "book-a").gauge()
            .shouldNotBeNull().value() shouldBe 0.0
    }
})
