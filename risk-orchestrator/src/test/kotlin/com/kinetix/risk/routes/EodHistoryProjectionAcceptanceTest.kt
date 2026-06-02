package com.kinetix.risk.routes

import com.kinetix.risk.persistence.DatabaseTestSetup
import com.kinetix.risk.persistence.ExposedValuationJobRecorder
import com.kinetix.risk.persistence.OfficialEodDesignationsTable
import com.kinetix.risk.persistence.ValuationJobsTable
import com.kinetix.risk.routes.dtos.EodTimelineResponse
import com.kinetix.risk.seed.DevDataSeeder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Regression test for the live-demo bug surfaced by the trader review:
 *
 *   `EOD History` tab showed `PV = —` on every promoted row, even though VaR
 *   and ES were populated. Trace: the SEED ValuationJob factory in
 *   [DevDataSeeder.Companion.buildSeedJobs] never set `pvValue`, so the entire
 *   projection (`valuation_jobs.pv_value` → `findOfficialEodRange` →
 *   `EodTimelineRoutes.computeTimeline` → gateway → UI) carried `null`.
 *
 * The acceptance pinned by `docs/plans/ui-trader-review.md` is "PV is non-null on
 * every promoted EOD row". This spec asserts that contract end-to-end against
 * the real Postgres-backed [ExposedValuationJobRecorder] and the real
 * EOD-timeline Ktor route — the same components the UI hits in production.
 */
private val testJson = Json { ignoreUnknownKeys = true }

class EodHistoryProjectionAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val jobRecorder = ExposedValuationJobRecorder(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            OfficialEodDesignationsTable.deleteAll()
            ValuationJobsTable.deleteAll()
        }
    }

    fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            install(ContentNegotiation) { json(testJson) }
            routing { eodTimelineRoutes(jobRecorder) }
            block()
        }
    }

    test("every SEED-promoted EOD row in the timeline projection carries a non-null positive pv_value") {
        DevDataSeeder(jobRecorder).seed()

        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays(30).toString()
        val to = today.minusDays(1).toString()

        // The 8 demo books used by DevDataSeeder.BOOK_VAR_PROFILES.
        val demoBooks = listOf(
            "equity-growth", "tech-momentum", "emerging-markets", "fixed-income",
            "multi-asset", "macro-hedge", "balanced-income", "derivatives-book",
        )
        val expectedWeekdayCount = (1..30L)
            .map { today.minusDays(it) }
            .count { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

        testApp {
            demoBooks.forEach { bookId ->
                val response = client.get("/api/v1/risk/eod-timeline/$bookId?from=$from&to=$to")
                response.status shouldBe HttpStatusCode.OK

                val body = testJson.decodeFromString<EodTimelineResponse>(response.bodyAsText())

                val promotedRows = body.entries.filter { it.promotedBy != null }
                // Sanity: SEED promotes every historical weekday in the range.
                promotedRows.size shouldBe expectedWeekdayCount

                // Surface the offending rows in the failure message so we know
                // which date(s) carry the null PV.
                val nullPvDates = promotedRows.filter { it.pvValue == null }.map { it.valuationDate }
                nullPvDates shouldBe emptyList()

                val nonPositive = promotedRows.filter { (it.pvValue ?: 0.0) <= 0.0 }.map { it.valuationDate }
                nonPositive shouldBe emptyList()
            }
        }
    }

    test("pv_value persisted on a SEED job round-trips through the EOD timeline route") {
        DevDataSeeder(jobRecorder).seed()

        val today = LocalDate.now(ZoneOffset.UTC)
        // Pick the most recent weekday strictly before today — SEED always promotes that one.
        val targetDate = generateSequence(today.minusDays(1)) { it.minusDays(1) }
            .first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

        // Read the persisted job back out and confirm pv_value made it to disk.
        val persistedJob = jobRecorder.findOfficialEodRange(
            bookId = "equity-growth",
            from = targetDate,
            to = targetDate,
        ).singleOrNull()
        persistedJob shouldNotBe null
        persistedJob!!.pvValue shouldNotBe null

        testApp {
            val response = client.get(
                "/api/v1/risk/eod-timeline/equity-growth?from=$targetDate&to=$targetDate"
            )
            response.status shouldBe HttpStatusCode.OK

            val body = testJson.decodeFromString<EodTimelineResponse>(response.bodyAsText())
            val row = body.entries.single { it.valuationDate == targetDate.toString() }
            row.pvValue shouldBe persistedJob.pvValue
        }
    }
})
