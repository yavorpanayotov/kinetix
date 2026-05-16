package com.kinetix.risk.persistence

import com.kinetix.common.model.LiquidityRiskResult
import com.kinetix.common.model.LiquidityTier
import com.kinetix.common.model.PositionLiquidityRisk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class ExposedLiquidityRiskSnapshotRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: LiquidityRiskSnapshotRepository = ExposedLiquidityRiskSnapshotRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { LiquidityRiskSnapshotsTable.deleteAll() }
    }

    fun sampleResult(bookId: String = "BOOK-1") = LiquidityRiskResult(
        bookId = bookId,
        portfolioLvar = 316_227.76,
        dataCompleteness = 0.85,
        positionRisks = listOf(
            PositionLiquidityRisk(
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                marketValue = 500_000.0,
                tier = LiquidityTier.HIGH_LIQUID,
                horizonDays = 1,
                adv = 10_000_000.0,
                advMissing = false,
                advStale = false,
                lvarContribution = 316_227.76,
                stressedLiquidationValue = 490_000.0,
                concentrationStatus = "OK",
            ),
        ),
        portfolioConcentrationStatus = "OK",
        calculatedAt = "2026-03-24T10:00:00Z",
    )

    test("saves and retrieves the latest liquidity risk snapshot for a book") {
        val result = sampleResult()
        repository.save(result)

        val found = repository.findLatestByBookId("BOOK-1")

        found!!.bookId shouldBe "BOOK-1"
        found.portfolioLvar.shouldBeWithinPercentageOf(316_227.76, 0.01)
        found.dataCompleteness.shouldBeWithinPercentageOf(0.85, 0.01)
        found.portfolioConcentrationStatus shouldBe "OK"
    }

    test("returns null for unknown book") {
        val found = repository.findLatestByBookId("UNKNOWN")
        found shouldBe null
    }

    test("findLatestByBookId returns the most recent snapshot when multiple exist") {
        val older = sampleResult().copy(portfolioLvar = 100_000.0, calculatedAt = "2026-03-23T10:00:00Z")
        val newer = sampleResult().copy(portfolioLvar = 200_000.0, calculatedAt = "2026-03-24T10:00:00Z")
        repository.save(older)
        repository.save(newer)

        val found = repository.findLatestByBookId("BOOK-1")

        found!!.portfolioLvar.shouldBeWithinPercentageOf(200_000.0, 0.01)
    }

    test("findAllByBookId returns all snapshots in descending order") {
        repository.save(sampleResult().copy(portfolioLvar = 100_000.0, calculatedAt = "2026-03-22T10:00:00Z"))
        repository.save(sampleResult().copy(portfolioLvar = 200_000.0, calculatedAt = "2026-03-23T10:00:00Z"))
        repository.save(sampleResult().copy(portfolioLvar = 300_000.0, calculatedAt = "2026-03-24T10:00:00Z"))

        val results = repository.findAllByBookId("BOOK-1")

        results shouldHaveSize 3
        results[0].portfolioLvar.shouldBeWithinPercentageOf(300_000.0, 0.01)
        results[1].portfolioLvar.shouldBeWithinPercentageOf(200_000.0, 0.01)
        results[2].portfolioLvar.shouldBeWithinPercentageOf(100_000.0, 0.01)
    }

    test("saves and restores position risks from JSON") {
        repository.save(sampleResult())

        val found = repository.findLatestByBookId("BOOK-1")!!

        found.positionRisks shouldHaveSize 1
        val pos = found.positionRisks.first()
        pos.instrumentId shouldBe "AAPL"
        pos.tier shouldBe LiquidityTier.HIGH_LIQUID
        pos.advMissing shouldBe false
        pos.concentrationStatus shouldBe "OK"
    }

    test("round-trips a position with null adv/advPct without throwing on read") {
        val result = sampleResult().copy(
            positionRisks = listOf(
                PositionLiquidityRisk(
                    instrumentId = "ILLIQUID-1",
                    assetClass = "EQUITY",
                    marketValue = 100_000.0,
                    tier = LiquidityTier.ILLIQUID,
                    horizonDays = 30,
                    adv = null,
                    advPct = null,
                    advMissing = true,
                    advStale = false,
                    lvarContribution = 0.0,
                    stressedLiquidationValue = 90_000.0,
                    concentrationStatus = "OK",
                ),
            ),
        )
        repository.save(result)

        val found = repository.findLatestByBookId("BOOK-1")!!
        val pos = found.positionRisks.single()
        pos.adv shouldBe null
        pos.advPct shouldBe null
    }

    test("persists advPct so a non-null value survives the round-trip") {
        val result = sampleResult().copy(
            positionRisks = listOf(
                PositionLiquidityRisk(
                    instrumentId = "AAPL",
                    assetClass = "EQUITY",
                    marketValue = 500_000.0,
                    tier = LiquidityTier.HIGH_LIQUID,
                    horizonDays = 1,
                    adv = 10_000_000.0,
                    advPct = 0.05,
                    advMissing = false,
                    advStale = false,
                    lvarContribution = 316_227.76,
                    stressedLiquidationValue = 490_000.0,
                    concentrationStatus = "OK",
                ),
            ),
        )
        repository.save(result)

        val found = repository.findLatestByBookId("BOOK-1")!!
        found.positionRisks.single().advPct shouldBe 0.05
    }
})
