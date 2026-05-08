package com.kinetix.price.feed

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.PricePoint
import com.kinetix.price.service.PriceIngestionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

class DemoTapeReplaySweeperTest : FunSpec({

    val seeds = listOf(
        InstrumentSeed(InstrumentId("AAPL"), BigDecimal("190.00"), Currency.getInstance("USD"), AssetClass.EQUITY),
        InstrumentSeed(InstrumentId("MSFT"), BigDecimal("425.00"), Currency.getInstance("USD"), AssetClass.EQUITY),
        InstrumentSeed(InstrumentId("EURUSD"), BigDecimal("1.0850"), Currency.getInstance("USD"), AssetClass.FX),
    )
    val fixedClock: Clock = Clock.fixed(Instant.parse("2026-05-08T14:30:00Z"), ZoneOffset.UTC)

    fun makeSweeper(
        ingestionService: PriceIngestionService,
        ready: Boolean = true,
    ): DemoTapeReplaySweeper = DemoTapeReplaySweeper.fromSeeds(
        seeds = seeds,
        ingestionService = ingestionService,
        readinessGate = { ready },
        clock = fixedClock,
    )

    test("skips ingestion when readiness gate is closed") {
        val ingestion = mockk<PriceIngestionService>()
        val sweeper = makeSweeper(ingestion, ready = false)

        val published = sweeper.replayOnce()

        published shouldBe 0
        coVerify(exactly = 0) { ingestion.ingest(any()) }
    }

    test("publishes one price point per configured instrument per tick") {
        val ingestion = mockk<PriceIngestionService>()
        val captured = mutableListOf<PricePoint>()
        coEvery { ingestion.ingest(capture(captured)) } just runs
        val sweeper = makeSweeper(ingestion)

        val published = sweeper.replayOnce()

        published shouldBe seeds.size
        captured.map { it.instrumentId.value }.toSet() shouldBe seeds.map { it.instrumentId.value }.toSet()
    }

    test("ticks carry the injected clock's timestamp") {
        val ingestion = mockk<PriceIngestionService>()
        val captured = mutableListOf<PricePoint>()
        coEvery { ingestion.ingest(capture(captured)) } just runs
        val sweeper = makeSweeper(ingestion)

        sweeper.replayOnce()

        captured.forEach { it.timestamp shouldBe fixedClock.instant() }
    }

    test("survives a single ingest failure and keeps publishing the remainder") {
        val ingestion = mockk<PriceIngestionService>()
        val seen = slot<PricePoint>()
        val ingested = mutableListOf<PricePoint>()
        coEvery { ingestion.ingest(capture(seen)) } answers {
            val p = seen.captured
            if (p.instrumentId.value == "AAPL") {
                throw RuntimeException("kafka briefly down")
            }
            ingested.add(p)
        }
        val sweeper = makeSweeper(ingestion)

        // Should not throw — sweeper drops the bad point and continues.
        val published = sweeper.replayOnce()

        published shouldBe seeds.size
        // MSFT and EURUSD should have made it through.
        ingested.map { it.instrumentId.value }.toSet() shouldBe setOf("MSFT", "EURUSD")
    }
})
