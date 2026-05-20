package com.kinetix.risk.service

import com.kinetix.risk.kafka.RegimeEventPublisher
import com.kinetix.risk.model.MarketRegime
import com.kinetix.risk.model.RegimeState
import com.kinetix.risk.persistence.MarketRegimeRepository
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * [RegimeTransitionListener] that persists confirmed regime transitions to the
 * `market_regime_history` table and publishes a regime-change event to Kafka.
 *
 * On a confirmed transition this listener:
 *  1. Closes the currently-open [com.kinetix.risk.model.MarketRegimeHistory]
 *     record (sets `ended_at`), capturing the period the prior regime was active.
 *  2. Inserts a new open-ended history record for the regime now in effect.
 *  3. Publishes a `risk.regime.changes` Kafka event so downstream consumers
 *     (notification-service, UI) observe the transition.
 *
 * This is the runtime path for the spec rule `ConfirmRegimeTransition`'s
 * `RegimeHistory.created` and `RegimeTransitioned` ensures
 * (`specs/regime.allium:210-223`).
 */
class PersistingRegimeTransitionListener(
    private val repository: MarketRegimeRepository,
    private val eventPublisher: RegimeEventPublisher,
    private val clock: () -> Instant = { Instant.now() },
) : RegimeTransitionListener {

    private val logger = LoggerFactory.getLogger(PersistingRegimeTransitionListener::class.java)

    override suspend fun onRegimeTransition(from: MarketRegime, to: MarketRegime, state: RegimeState) {
        val now = clock()
        try {
            val open = repository.findCurrent()
            if (open != null) {
                repository.close(open.id, now)
            }
            repository.insert(state)
            logger.info("Persisted regime transition {} -> {} to market_regime_history", from, to)
        } catch (e: Exception) {
            logger.error("Failed to persist regime transition {} -> {}", from, to, e)
        }

        try {
            eventPublisher.publish(from = from, to = state)
        } catch (e: Exception) {
            logger.error("Failed to publish regime transition event {} -> {}", from, to, e)
        }
    }
}
