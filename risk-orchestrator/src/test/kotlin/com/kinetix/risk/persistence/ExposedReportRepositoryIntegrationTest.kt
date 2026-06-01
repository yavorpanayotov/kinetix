package com.kinetix.risk.persistence

import com.kinetix.risk.model.report.ReportFormat
import com.kinetix.risk.model.report.ReportOutput
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private fun output(
    id: String,
    generatedAt: Instant,
    templateId: String = "tpl-risk-summary",
    rowCount: Int = 1,
) = ReportOutput(
    outputId = id,
    templateId = templateId,
    generatedAt = generatedAt,
    outputFormat = ReportFormat.JSON,
    rowCount = rowCount,
    outputData = buildJsonArray { add(buildJsonObject { put("book_id", "BOOK-1") }) },
)

class ExposedReportRepositoryIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: ReportRepository = ExposedReportRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { ReportOutputsTable.deleteAll() }
    }

    test("listRecentOutputs returns outputs newest-first") {
        repository.saveOutput(output("out-old", Instant.parse("2025-01-15T09:00:00Z")))
        repository.saveOutput(output("out-new", Instant.parse("2025-01-16T12:00:00Z")))
        repository.saveOutput(output("out-mid", Instant.parse("2025-01-15T18:00:00Z")))

        val recent = repository.listRecentOutputs(10)

        recent.map { it.outputId } shouldBe listOf("out-new", "out-mid", "out-old")
    }

    test("listRecentOutputs caps the result at the requested limit") {
        repeat(5) { i ->
            repository.saveOutput(output("out-$i", Instant.parse("2025-01-1${i}T00:00:00Z")))
        }

        repository.listRecentOutputs(3) shouldHaveSize 3
    }

    test("listRecentOutputs returns an empty list when no outputs exist") {
        repository.listRecentOutputs(10) shouldHaveSize 0
    }
})
