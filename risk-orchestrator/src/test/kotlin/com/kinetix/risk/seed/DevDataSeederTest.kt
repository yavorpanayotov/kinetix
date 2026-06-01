package com.kinetix.risk.seed

import com.kinetix.risk.model.ChartBucketRow
import com.kinetix.risk.model.CounterpartyExposureSnapshot
import com.kinetix.risk.model.EodPromotionException
import com.kinetix.risk.model.JobPhaseName
import com.kinetix.risk.model.RunLabel
import com.kinetix.risk.model.RunStatus
import com.kinetix.risk.model.ValuationJob
import com.kinetix.risk.persistence.CounterpartyExposureRepository
import com.kinetix.risk.service.ValuationJobRecorder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

class DevDataSeederTest : FunSpec({

    test("seed deletes stale SEED data and re-creates with fresh timestamps") {
        val recorder = InMemoryValuationJobRecorder()

        // First seed
        val seeder = DevDataSeeder(recorder)
        seeder.seed()
        val firstSeedCount = recorder.jobs.size
        firstSeedCount shouldBeGreaterThan 0

        // Second seed should delete and recreate (not skip)
        seeder.seed()
        recorder.deleteCount shouldBeGreaterThan 0
        recorder.jobs.size shouldBe firstSeedCount
    }

    test("buildSeedJobs produces intraday entries within last 24 hours") {
        val jobs = DevDataSeeder.buildSeedJobs()
        val cutoff = Instant.now().minus(24, ChronoUnit.HOURS)
        val recentJobs = jobs.filter { it.startedAt.isAfter(cutoff) }

        recentJobs.shouldNotBeEmpty()
    }

    test("all seed jobs have triggeredBy set to SEED") {
        val jobs = DevDataSeeder.buildSeedJobs()
        jobs.forEach { it.triggeredBy shouldBe "SEED" }
    }

    test("seed jobs cover all 8 book profiles") {
        val jobs = DevDataSeeder.buildSeedJobs()
        val bookIds = jobs.map { it.bookId }.toSet()
        bookIds.size shouldBe 8
    }

    test("seeds counterparty exposure snapshots for all demo counterparties") {
        val recorder = InMemoryValuationJobRecorder()
        val exposureRepo = InMemoryCounterpartyExposureRepository()

        val seeder = DevDataSeeder(recorder, exposureRepo)
        seeder.seed()

        // Every reference-data counterparty (across all tiers) carries an
        // exposure snapshot so the Counterparty Risk tab isn't padded with
        // zero-exposure rows (kx-8kqk).
        val counterpartyIds = exposureRepo.snapshots.map { it.counterpartyId }.toSet()
        counterpartyIds shouldBe com.kinetix.common.demo.CounterpartyTiers.ALL_IDS.toSet()

        exposureRepo.snapshots.forEach { snapshot ->
            snapshot.currentNetExposure shouldBeGreaterThan 0.0
            snapshot.peakPfe shouldBeGreaterThan 0.0
            snapshot.pfeProfile shouldHaveAtLeastSize 1
            snapshot.currency shouldBe "USD"
        }
    }

    test("skips counterparty exposure seeding when data already exists") {
        val recorder = InMemoryValuationJobRecorder()
        val exposureRepo = InMemoryCounterpartyExposureRepository()
        // Pre-populate so seeder detects existing data
        exposureRepo.snapshots.add(DevDataSeeder.buildCounterpartyExposureSnapshots().first())

        val seeder = DevDataSeeder(recorder, exposureRepo)
        seeder.seed()

        // Should still have only the 1 pre-existing snapshot, not 6+1
        exposureRepo.snapshots.size shouldBe 1
    }

    test("DEMO_COUNTERPARTIES exposes every demo counterparty with id, name and creditRating (kx-i72, kx-8kqk)") {
        val demo = DevDataSeeder.DEMO_COUNTERPARTIES

        demo.map { it.id }.toSet() shouldBe com.kinetix.common.demo.CounterpartyTiers.ALL_IDS.toSet()

        // Each counterparty has a non-empty, human-readable name (i.e. not just the raw id).
        demo.forEach { cp ->
            cp.name.isNotBlank() shouldBe true
            cp.name shouldBe cp.name.trim()
            cp.id shouldBe cp.id.trim()
            // S&P-style ratings: AAA, AA+, AA, AA-, A+, A, A-, BBB+, BBB, BBB-, BB+ …
            cp.creditRating.matches(Regex("[ABC]{1,3}[+-]?")) shouldBe true
        }
    }

    test("each counterparty has netting set exposures and realistic PFE profiles") {
        val snapshots = DevDataSeeder.buildCounterpartyExposureSnapshots()

        snapshots.forEach { snapshot ->
            val nettingSets = snapshot.nettingSetExposures!!
            nettingSets shouldHaveAtLeastSize 1
            nettingSets.forEach { ns ->
                ns.nettingSetId shouldStartWith "NS-"
                ns.netExposure shouldBeGreaterThan 0.0
                ns.peakPfe shouldBeGreaterThan 0.0
            }

            snapshot.pfeProfile.forEach { tenor ->
                tenor.pfe95 shouldBeGreaterThan tenor.expectedExposure
                tenor.pfe99 shouldBeGreaterThan tenor.pfe95
            }
        }
    }

    // ── Phase 3 / PR 1 — derive P&L from positions × tape moves ──────────
    // These tests validate the per-book attribution rows that DevDataSeeder
    // now stages via PnLAttributionDeriver instead of the hand-tuned constants
    // BOOK_PNL_PROFILES (deleted). The deriver tests in PnLAttributionDeriverTest
    // and PnLAttributionAcceptanceTest cover the math in depth; here we sanity-
    // check the seeder contract.

    test("P&L attribution seed covers all 8 books") {
        val books = PnLAttributionDeriver().derive().map { it.bookId.value }.toSet()
        books shouldBe setOf(
            "balanced-income", "derivatives-book", "emerging-markets",
            "equity-growth", "fixed-income", "macro-hedge",
            "multi-asset", "tech-momentum",
        )
    }

    test("each book P&L ties out: total = delta + gamma + vega + theta + rho + cross-Greeks + unexplained") {
        val attributions = PnLAttributionDeriver().derive()
        attributions.forEach { attribution ->
            val sumOfParts = attribution.deltaPnl
                .add(attribution.gammaPnl)
                .add(attribution.vegaPnl)
                .add(attribution.thetaPnl)
                .add(attribution.rhoPnl)
                .add(attribution.vannaPnl)
                .add(attribution.volgaPnl)
                .add(attribution.charmPnl)
                .add(attribution.crossGammaPnl)
                .add(attribution.unexplainedPnl)
            attribution.totalPnl.compareTo(sumOfParts) shouldBe 0
        }
    }

    test("each position attribution ties out per row") {
        val attributions = PnLAttributionDeriver().derive()
        attributions.flatMap { it.positionAttributions }.forEach { p ->
            val sumOfParts = p.deltaPnl
                .add(p.gammaPnl)
                .add(p.vegaPnl)
                .add(p.thetaPnl)
                .add(p.rhoPnl)
                .add(p.vannaPnl)
                .add(p.volgaPnl)
                .add(p.charmPnl)
                .add(p.crossGammaPnl)
                .add(p.unexplainedPnl)
            p.totalPnl.compareTo(sumOfParts) shouldBe 0
        }
    }

    test("book total P&L equals sum of position totals (book = sum of positions)") {
        val attributions = PnLAttributionDeriver().derive()
        attributions.forEach { attribution ->
            val summedFromPositions = attribution.positionAttributions
                .map { it.totalPnl }
                .fold(java.math.BigDecimal.ZERO.setScale(8)) { acc, v -> acc.add(v) }
            attribution.totalPnl.compareTo(summedFromPositions) shouldBe 0
        }
    }

    // ── EOD designation backfill ────────────────────────────────────────
    // Seed data needs to populate the OFFICIAL_EOD designations table so the
    // EOD timeline view has history immediately after deploy. Going forward,
    // ScheduledAutoCloseJob promotes today's EOD at 17:30 UTC.

    test("seed promotes historical weekday jobs to OFFICIAL_EOD") {
        val recorder = InMemoryValuationJobRecorder()
        DevDataSeeder(recorder).seed()

        val today = LocalDate.now(ZoneOffset.UTC)
        val historicalWeekdayJobs = recorder.jobs.filter { job ->
            job.valuationDate < today &&
                job.valuationDate.dayOfWeek != DayOfWeek.SATURDAY &&
                job.valuationDate.dayOfWeek != DayOfWeek.SUNDAY
        }
        historicalWeekdayJobs.shouldNotBeEmpty()

        historicalWeekdayJobs.forEach { job ->
            val designated = recorder.findOfficialEodByDate(job.bookId, job.valuationDate)
            designated.shouldNotBeNull()
            designated.jobId shouldBe job.jobId
            designated.runLabel shouldBe RunLabel.OFFICIAL_EOD
            designated.promotedBy shouldBe "SEED"
        }
    }

    test("seed does NOT promote weekend historical jobs") {
        val recorder = InMemoryValuationJobRecorder()
        DevDataSeeder(recorder).seed()

        val weekendJobs = recorder.jobs.filter { job ->
            job.valuationDate.dayOfWeek == DayOfWeek.SATURDAY ||
                job.valuationDate.dayOfWeek == DayOfWeek.SUNDAY
        }

        weekendJobs.forEach { job ->
            recorder.findOfficialEodByDate(job.bookId, job.valuationDate) shouldBe null
        }
    }

    test("seed does NOT promote intraday today jobs (production schedule owns today)") {
        val recorder = InMemoryValuationJobRecorder()
        DevDataSeeder(recorder).seed()

        val today = LocalDate.now(ZoneOffset.UTC)
        val todayJobs = recorder.jobs.filter { it.valuationDate == today }
        todayJobs.shouldNotBeEmpty()

        // Today must not be designated by the seeder — ScheduledAutoCloseJob
        // promotes today at 17:30 UTC.
        recorder.jobs.map { it.bookId }.toSet().forEach { bookId ->
            recorder.findOfficialEodByDate(bookId, today) shouldBe null
        }
    }

    test("re-seed clears prior SEED designations before re-promoting") {
        val recorder = InMemoryValuationJobRecorder()
        val seeder = DevDataSeeder(recorder)

        seeder.seed()
        val firstDesignationCount = recorder.designations.size
        firstDesignationCount shouldBeGreaterThan 0

        seeder.seed()
        // The deleteOfficialEodDesignationsByPromotedBy call must purge the
        // prior set so we don't end up with stale designations pointing at
        // jobIds that were just deleted.
        recorder.designations.size shouldBe firstDesignationCount
        recorder.deleteDesignationCount shouldBe firstDesignationCount
    }
})

private class InMemoryValuationJobRecorder : ValuationJobRecorder {
    val jobs = mutableListOf<ValuationJob>()
    val designations = mutableListOf<Designation>()
    var deleteCount = 0
    var deleteDesignationCount = 0

    data class Designation(
        val bookId: String,
        val valuationDate: LocalDate,
        val jobId: UUID,
        val promotedAt: Instant,
        val promotedBy: String,
    )

    override suspend fun save(job: ValuationJob) { jobs.add(job) }
    override suspend fun update(job: ValuationJob) {}
    override suspend fun updateCurrentPhase(jobId: UUID, phase: JobPhaseName) {}
    override suspend fun findByBookId(bookId: String, limit: Int, offset: Int, from: Instant?, to: Instant?, valuationDate: LocalDate?, runLabel: RunLabel?) = emptyList<ValuationJob>()
    override suspend fun countByBookId(bookId: String, from: Instant?, to: Instant?, valuationDate: LocalDate?, runLabel: RunLabel?) = 0L
    override suspend fun findByJobId(jobId: UUID): ValuationJob? = jobs.firstOrNull { it.jobId == jobId }
    override suspend fun findDistinctBookIds() = jobs.map { it.bookId }.distinct()
    override suspend fun findLatestCompletedByDate(bookId: String, valuationDate: LocalDate): ValuationJob? = null
    override suspend fun findLatestCompleted(bookId: String): ValuationJob? = null
    override suspend fun findLatestCompletedBeforeDate(bookId: String, beforeDate: LocalDate): ValuationJob? = null
    override suspend fun findOfficialEodByDate(bookId: String, valuationDate: LocalDate): ValuationJob? {
        val designation = designations.firstOrNull {
            it.bookId == bookId && it.valuationDate == valuationDate
        } ?: return null
        val job = jobs.firstOrNull { it.jobId == designation.jobId } ?: return null
        return job.copy(
            runLabel = RunLabel.OFFICIAL_EOD,
            promotedAt = designation.promotedAt,
            promotedBy = designation.promotedBy,
        )
    }
    override suspend fun findOfficialEodRange(bookId: String, from: LocalDate, to: LocalDate): List<ValuationJob> =
        designations
            .filter { it.bookId == bookId && !it.valuationDate.isBefore(from) && !it.valuationDate.isAfter(to) }
            .mapNotNull { d -> findOfficialEodByDate(d.bookId, d.valuationDate) }
    override suspend fun promoteToOfficialEod(jobId: UUID, promotedBy: String, promotedAt: Instant): ValuationJob {
        val job = jobs.firstOrNull { it.jobId == jobId }
            ?: throw EodPromotionException.JobNotFound(jobId)
        val exists = designations.any { it.bookId == job.bookId && it.valuationDate == job.valuationDate }
        if (exists) throw EodPromotionException.ConflictingOfficialEod(job.bookId, job.valuationDate.toString())
        designations.add(Designation(job.bookId, job.valuationDate, jobId, promotedAt, promotedBy))
        val idx = jobs.indexOf(job)
        val updated = job.copy(runLabel = RunLabel.OFFICIAL_EOD, promotedAt = promotedAt, promotedBy = promotedBy)
        jobs[idx] = updated
        return updated
    }
    override suspend fun demoteOfficialEod(jobId: UUID): ValuationJob = throw UnsupportedOperationException()
    override suspend fun supersedeOfficialEod(jobId: UUID): ValuationJob = throw UnsupportedOperationException()
    override suspend fun findChartData(bookId: String, from: Instant, to: Instant, bucketInterval: String) = emptyList<ChartBucketRow>()
    override suspend fun resetOrphanedRunningJobs() = 0
    override suspend fun findByTriggeredBy(triggeredBy: String, limit: Int): List<ValuationJob> {
        return jobs.filter { it.triggeredBy == triggeredBy }.take(limit)
    }
    override suspend fun deleteByTriggeredBy(triggeredBy: String): Int {
        val count = jobs.count { it.triggeredBy == triggeredBy }
        jobs.removeAll { it.triggeredBy == triggeredBy }
        deleteCount += count
        return count
    }
    override suspend fun deleteOfficialEodDesignationsByPromotedBy(promotedBy: String): Int {
        val count = designations.count { it.promotedBy == promotedBy }
        designations.removeAll { it.promotedBy == promotedBy }
        deleteDesignationCount += count
        return count
    }
}

private class InMemoryCounterpartyExposureRepository : CounterpartyExposureRepository {
    val snapshots = mutableListOf<CounterpartyExposureSnapshot>()

    override suspend fun save(snapshot: CounterpartyExposureSnapshot): CounterpartyExposureSnapshot {
        val saved = snapshot.copy(id = (snapshots.size + 1).toLong())
        snapshots.add(saved)
        return saved
    }

    override suspend fun findLatestByCounterpartyId(counterpartyId: String): CounterpartyExposureSnapshot? =
        snapshots.filter { it.counterpartyId == counterpartyId }.maxByOrNull { it.calculatedAt }

    override suspend fun findByCounterpartyId(counterpartyId: String, limit: Int): List<CounterpartyExposureSnapshot> =
        snapshots.filter { it.counterpartyId == counterpartyId }.sortedByDescending { it.calculatedAt }.take(limit)

    override suspend fun findLatestForAllCounterparties(): List<CounterpartyExposureSnapshot> =
        snapshots.groupBy { it.counterpartyId }.mapValues { (_, v) -> v.maxByOrNull { it.calculatedAt }!! }.values.toList()
}
