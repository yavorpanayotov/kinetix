package com.kinetix.risk.persistence

import com.kinetix.risk.model.FactorContribution
import com.kinetix.risk.model.FactorDecompositionSnapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class ExposedFactorDecompositionRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: FactorDecompositionRepository = ExposedFactorDecompositionRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { FactorDecompositionSnapshotsTable.deleteAll() }
    }

    fun sampleSnapshot(
        bookId: String = "BOOK-1",
        calculatedAt: Instant = Instant.parse("2026-03-24T10:00:00Z"),
    ) = FactorDecompositionSnapshot(
        bookId = bookId,
        calculatedAt = calculatedAt,
        totalVar = 50_000.0,
        systematicVar = 38_000.0,
        idiosyncraticVar = 12_000.0,
        rSquared = 0.576,
        concentrationWarning = false,
        factors = listOf(
            FactorContribution(
                factorType = "EQUITY_BETA",
                factorExposure = 250_000.0,
                varContribution = 30_000.0,
                pnlAttribution = 4_200.0,
                pctOfTotal = 0.60,
                loading = 1.2,
                loadingMethod = "OLS_REGRESSION",
            ),
            FactorContribution(
                factorType = "RATES_DURATION",
                factorExposure = -75_000.0,
                varContribution = 8_000.0,
                pnlAttribution = -1_100.0,
                pctOfTotal = 0.16,
                loading = -0.5,
                loadingMethod = "ANALYTICAL",
            ),
        ),
    )

    test("saves and retrieves the latest factor decomposition for a book") {
        repository.save(sampleSnapshot())

        val found = repository.findLatestByBookId("BOOK-1")

        found!!.bookId shouldBe "BOOK-1"
        found.totalVar.shouldBeWithinPercentageOf(50_000.0, 0.01)
        found.systematicVar.shouldBeWithinPercentageOf(38_000.0, 0.01)
        found.idiosyncraticVar.shouldBeWithinPercentageOf(12_000.0, 0.01)
        found.rSquared.shouldBeWithinPercentageOf(0.576, 0.01)
        found.concentrationWarning shouldBe false
    }

    test("returns null for unknown book") {
        repository.findLatestByBookId("UNKNOWN") shouldBe null
    }

    test("findLatestByBookId returns the most recent snapshot when multiple exist") {
        repository.save(sampleSnapshot(calculatedAt = Instant.parse("2026-03-23T10:00:00Z")).copy(totalVar = 40_000.0))
        repository.save(sampleSnapshot(calculatedAt = Instant.parse("2026-03-24T10:00:00Z")).copy(totalVar = 50_000.0))

        val found = repository.findLatestByBookId("BOOK-1")

        found!!.totalVar.shouldBeWithinPercentageOf(50_000.0, 0.01)
    }

    test("findAllByBookId returns snapshots in descending order") {
        repository.save(sampleSnapshot(calculatedAt = Instant.parse("2026-03-22T10:00:00Z")).copy(totalVar = 30_000.0))
        repository.save(sampleSnapshot(calculatedAt = Instant.parse("2026-03-23T10:00:00Z")).copy(totalVar = 40_000.0))
        repository.save(sampleSnapshot(calculatedAt = Instant.parse("2026-03-24T10:00:00Z")).copy(totalVar = 50_000.0))

        val results = repository.findAllByBookId("BOOK-1")

        results shouldHaveSize 3
        results[0].totalVar.shouldBeWithinPercentageOf(50_000.0, 0.01)
        results[1].totalVar.shouldBeWithinPercentageOf(40_000.0, 0.01)
        results[2].totalVar.shouldBeWithinPercentageOf(30_000.0, 0.01)
    }

    test("saves and restores factor contributions from JSON") {
        repository.save(sampleSnapshot())

        val found = repository.findLatestByBookId("BOOK-1")!!

        found.factors shouldHaveSize 2
        val equityFactor = found.factors.first { it.factorType == "EQUITY_BETA" }
        equityFactor.factorExposure.shouldBeWithinPercentageOf(250_000.0, 0.01)
        equityFactor.varContribution.shouldBeWithinPercentageOf(30_000.0, 0.01)
        equityFactor.pnlAttribution.shouldBeWithinPercentageOf(4_200.0, 0.01)
        equityFactor.pctOfTotal.shouldBeWithinPercentageOf(0.60, 0.01)
        equityFactor.loading.shouldBeWithinPercentageOf(1.2, 0.01)
        equityFactor.loadingMethod shouldBe "OLS_REGRESSION"
    }

    test("concentration warning is persisted correctly") {
        repository.save(sampleSnapshot().copy(concentrationWarning = true))

        val found = repository.findLatestByBookId("BOOK-1")!!

        found.concentrationWarning shouldBe true
    }
})
