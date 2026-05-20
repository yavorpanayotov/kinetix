package com.kinetix.risk.service

import com.kinetix.risk.kafka.RegimeEventPublisher
import com.kinetix.risk.model.AdaptiveVaRParameters
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.MarketRegime
import com.kinetix.risk.model.MarketRegimeHistory
import com.kinetix.risk.model.RegimeSignals
import com.kinetix.risk.model.RegimeState
import com.kinetix.risk.persistence.MarketRegimeRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

private fun crisisState() = RegimeState(
    regime = MarketRegime.CRISIS,
    detectedAt = Instant.parse("2026-05-20T14:30:00Z"),
    confidence = 0.88,
    signals = RegimeSignals(realisedVol20d = 0.30, crossAssetCorrelation = 0.82),
    varParameters = AdaptiveVaRParameters(
        calculationType = CalculationType.MONTE_CARLO,
        confidenceLevel = ConfidenceLevel.CL_99,
        timeHorizonDays = 5,
        correlationMethod = "stressed",
        numSimulations = 50_000,
    ),
    consecutiveObservations = 3,
    isConfirmed = true,
    degradedInputs = false,
)

private fun historyRecord(id: UUID) = MarketRegimeHistory(
    id = id,
    regime = MarketRegime.NORMAL,
    startedAt = Instant.parse("2026-05-20T08:00:00Z"),
    endedAt = null,
    durationMs = null,
    signals = RegimeSignals(realisedVol20d = 0.10, crossAssetCorrelation = 0.40),
    varParameters = AdaptiveVaRParameters(
        calculationType = CalculationType.PARAMETRIC,
        confidenceLevel = ConfidenceLevel.CL_95,
        timeHorizonDays = 1,
        correlationMethod = "standard",
        numSimulations = null,
    ),
    confidence = 0.92,
    degradedInputs = false,
    consecutiveObservations = 0,
)

/** In-memory repository capturing inserts and closes for assertion. */
private class RecordingRepository(private var current: MarketRegimeHistory? = null) : MarketRegimeRepository {
    val inserted = mutableListOf<RegimeState>()
    val closed = mutableListOf<Pair<UUID, Instant>>()

    override suspend fun insert(state: RegimeState, id: UUID): UUID {
        inserted.add(state)
        return id
    }

    override suspend fun close(id: UUID, endedAt: Instant) {
        closed.add(id to endedAt)
    }

    override suspend fun findCurrent(): MarketRegimeHistory? = current
    override suspend fun findRecent(limit: Int): List<MarketRegimeHistory> = emptyList()
}

private class RecordingPublisher : RegimeEventPublisher {
    val published = mutableListOf<Triple<MarketRegime, MarketRegime, RegimeState>>()
    var shouldThrow = false
    override suspend fun publish(from: MarketRegime, to: RegimeState, correlationId: String?) {
        if (shouldThrow) throw RuntimeException("kafka down")
        published.add(Triple(from, to.regime, to))
    }
}

class PersistingRegimeTransitionListenerTest : FunSpec({

    test("closes the previously open regime history record on transition") {
        val openId = UUID.randomUUID()
        val repo = RecordingRepository(current = historyRecord(openId))
        val listener = PersistingRegimeTransitionListener(repo, RecordingPublisher())

        listener.onRegimeTransition(MarketRegime.NORMAL, MarketRegime.CRISIS, crisisState())

        repo.closed.size shouldBe 1
        repo.closed.first().first shouldBe openId
    }

    test("inserts a new open-ended history record for the regime now in effect") {
        val repo = RecordingRepository(current = historyRecord(UUID.randomUUID()))
        val listener = PersistingRegimeTransitionListener(repo, RecordingPublisher())

        listener.onRegimeTransition(MarketRegime.NORMAL, MarketRegime.CRISIS, crisisState())

        repo.inserted.size shouldBe 1
        repo.inserted.first().regime shouldBe MarketRegime.CRISIS
    }

    test("inserts without closing when no record is currently open") {
        val repo = RecordingRepository(current = null)
        val listener = PersistingRegimeTransitionListener(repo, RecordingPublisher())

        listener.onRegimeTransition(MarketRegime.NORMAL, MarketRegime.CRISIS, crisisState())

        repo.closed.size shouldBe 0
        repo.inserted.size shouldBe 1
    }

    test("publishes a regime change event to Kafka on transition") {
        val publisher = RecordingPublisher()
        val listener = PersistingRegimeTransitionListener(RecordingRepository(), publisher)

        listener.onRegimeTransition(MarketRegime.NORMAL, MarketRegime.CRISIS, crisisState())

        publisher.published.size shouldBe 1
        publisher.published.first().first shouldBe MarketRegime.NORMAL
        publisher.published.first().second shouldBe MarketRegime.CRISIS
    }

    test("persists history even when Kafka publishing fails") {
        val repo = RecordingRepository()
        val publisher = RecordingPublisher().apply { shouldThrow = true }
        val listener = PersistingRegimeTransitionListener(repo, publisher)

        listener.onRegimeTransition(MarketRegime.NORMAL, MarketRegime.CRISIS, crisisState())

        repo.inserted.size shouldBe 1
    }
})
