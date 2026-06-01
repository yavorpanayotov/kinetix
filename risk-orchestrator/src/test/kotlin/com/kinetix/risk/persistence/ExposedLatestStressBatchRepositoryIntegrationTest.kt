package com.kinetix.risk.persistence

import com.kinetix.common.model.BookId
import com.kinetix.risk.routes.dtos.BatchScenarioFailureDto
import com.kinetix.risk.routes.dtos.BatchScenarioResultDto
import com.kinetix.risk.routes.dtos.BatchStressRunResultResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private val PORTFOLIO = BookId("port-1")

private fun batch(
    worst: String = "GFC_2008",
    worstPnl: String = "-400000.00",
) = BatchStressRunResultResponse(
    results = listOf(
        BatchScenarioResultDto("GFC_2008", "50000.00", "80000.00", "-400000.00"),
        BatchScenarioResultDto("COVID_2020", "50000.00", "70000.00", "-150000.00"),
    ),
    failedScenarios = listOf(
        BatchScenarioFailureDto("BROKEN", "engine unavailable"),
    ),
    worstScenarioName = worst,
    worstPnlImpact = worstPnl,
)

/**
 * Integration test for [ExposedLatestStressBatchRepository] (issue kx-kjse).
 * Exercises the V72 `latest_stress_batches` table and the JSONB round-trip of
 * the full [BatchStressRunResultResponse] against a real Postgres via
 * Testcontainers.
 *
 * NOTE: Requires Docker (Testcontainers) — runs via ./gradlew integrationTest.
 */
class ExposedLatestStressBatchRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: LatestStressBatchRepository = ExposedLatestStressBatchRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            LatestStressBatchesTable.deleteAll()
        }
    }

    test("saves and retrieves the latest batch for a book, preserving the full payload") {
        repository.save(PORTFOLIO, batch())

        val found = repository.findLatestByBookId(PORTFOLIO)
        found.shouldNotBeNull()
        found.results.size shouldBe 2
        found.results[0].scenarioName shouldBe "GFC_2008"
        found.results[0].pnlImpact shouldBe "-400000.00"
        found.failedScenarios.size shouldBe 1
        found.failedScenarios[0].scenarioName shouldBe "BROKEN"
        found.worstScenarioName shouldBe "GFC_2008"
        found.worstPnlImpact shouldBe "-400000.00"
    }

    test("returns null for a book with no persisted batch") {
        repository.findLatestByBookId(BookId("unknown")).shouldBeNull()
    }

    test("upserts on the book id — the most recent save wins") {
        repository.save(PORTFOLIO, batch(worst = "GFC_2008", worstPnl = "-400000.00"))
        repository.save(PORTFOLIO, batch(worst = "COVID_2020", worstPnl = "-999999.99"))

        val found = repository.findLatestByBookId(PORTFOLIO)
        found.shouldNotBeNull()
        found.worstScenarioName shouldBe "COVID_2020"
        found.worstPnlImpact shouldBe "-999999.99"
    }

    test("different books are stored independently") {
        val other = BookId("port-2")
        repository.save(PORTFOLIO, batch(worst = "GFC_2008"))
        repository.save(other, batch(worst = "COVID_2020"))

        repository.findLatestByBookId(PORTFOLIO)!!.worstScenarioName shouldBe "GFC_2008"
        repository.findLatestByBookId(other)!!.worstScenarioName shouldBe "COVID_2020"
    }
})
