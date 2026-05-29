package com.kinetix.notification.engine

import com.kinetix.common.kafka.events.PositionBreakdownItem
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.notification.engine.extractors.DEFAULT_EXTRACTORS
import com.kinetix.notification.engine.extractors.MetricExtractor
import com.kinetix.notification.model.*
import com.kinetix.notification.persistence.AlertEventRepository
import com.kinetix.notification.persistence.AlertRuleRepository
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import kotlin.math.abs

/**
 * Default suppression window for per-(rule, book) deduplication of alert
 * firings. Within this window the engine collapses repeat firings of the
 * same `(ruleId, bookId)` into a single stored row — including the case
 * where the prior alert was manually resolved or acknowledged before the
 * window elapsed.
 *
 * Five minutes is the operational sweet spot identified during the trader
 * review (§20): long enough to absorb the burst telemetry that follows a
 * single underlying market move, short enough that genuinely new breaches
 * still get visibility within one risk recalc cycle.
 */
const val DEFAULT_ALERT_SUPPRESSION_WINDOW_SECONDS: Long = 300L

class RulesEngine(
    private val repository: AlertRuleRepository,
    private val meterRegistry: MeterRegistry? = null,
    private val eventRepository: AlertEventRepository? = null,
    extractors: List<MetricExtractor> = DEFAULT_EXTRACTORS,
    private val suggestedActionGenerator: SuggestedActionGenerator = SuggestedActionGenerator(),
    /**
     * Sliding-window suppression for repeat firings of the same `(ruleId, bookId)`.
     * Set to 0 to disable; the default matches [DEFAULT_ALERT_SUPPRESSION_WINDOW_SECONDS].
     */
    private val suppressionWindowSeconds: Long = DEFAULT_ALERT_SUPPRESSION_WINDOW_SECONDS,
    /**
     * Injectable clock so tests can pin wall-clock time and assert the
     * window boundary deterministically. Production callers leave this at
     * `Clock.systemUTC()`.
     */
    private val clock: Clock = Clock.systemUTC(),
) {

    private val extractorsByType: Map<AlertType, MetricExtractor> = extractors.associateBy { it.type }

    private val logger = LoggerFactory.getLogger(RulesEngine::class.java)

    suspend fun addRule(rule: AlertRule) {
        repository.save(rule)
    }

    suspend fun removeRule(ruleId: String): Boolean {
        return repository.deleteById(ruleId)
    }

    suspend fun listRules(): List<AlertRule> = repository.findAll()

    suspend fun evaluate(event: RiskResultEvent): List<AlertEvent> {
        val rules = repository.findAll().filter { it.enabled }
        val firedRuleIds = mutableSetOf<String>()

        val alerts = rules.mapNotNull { rule ->
            meterRegistry?.counter(
                "notification_rules_evaluated_total",
                "alert_type", rule.type.name,
                "book_id", event.bookId,
            )?.increment()

            val currentValue = extractMetric(rule.type, event) ?: return@mapNotNull null
            val triggered = compare(currentValue, rule.operator, rule.threshold)

            if (triggered) {
                firedRuleIds.add(rule.id)

                // Snooze: if there's any existing alert for this (rule, book) that is
                // currently snoozed (snoozed_until > now), skip re-firing entirely.
                val now = clock.instant()
                val existingForRule = eventRepository?.findLatestByRuleAndBook(rule.id, event.bookId)
                if (existingForRule != null && existingForRule.snoozedUntil != null && existingForRule.snoozedUntil.isAfter(now)) {
                    logger.debug(
                        "Skipping snoozed alert for rule={}, book={}, snoozedUntil={}",
                        rule.name, event.bookId, existingForRule.snoozedUntil,
                    )
                    return@mapNotNull null
                }

                // Deduplication: skip if an active alert already exists for this (rule, book)
                val existing = eventRepository?.findActiveByRuleAndBook(rule.id, event.bookId)
                if (existing != null) {
                    logger.debug(
                        "Suppressing duplicate alert for rule={}, book={}, existing={}",
                        rule.name, event.bookId, existing.id,
                    )
                    return@mapNotNull null
                }

                // Sliding-window suppression: even if the prior alert is
                // RESOLVED/ACKNOWLEDGED/ESCALATED, drop re-fires for the same
                // (rule, book) that arrive within `suppressionWindowSeconds`
                // of the most recent triggeredAt. Mirrors trader review §20 —
                // a single underlying market move should not page the desk
                // twice within minutes of triage.
                if (suppressionWindowSeconds > 0 && existingForRule != null) {
                    val ageSeconds = now.epochSecond - existingForRule.triggeredAt.epochSecond
                    if (ageSeconds in 0..suppressionWindowSeconds) {
                        logger.debug(
                            "Suppressing rule={} book={} within {}s window (ageSeconds={})",
                            rule.name, event.bookId, suppressionWindowSeconds, ageSeconds,
                        )
                        return@mapNotNull null
                    }
                }

                meterRegistry?.counter(
                    "notification_alerts_triggered_total",
                    "alert_type", rule.type.name,
                    "severity", rule.severity.name,
                )?.increment()

                val topContributors = event.positionBreakdown
                    ?.sortedByDescending { abs(it.varContribution.toDoubleOrNull() ?: 0.0) }
                    ?.take(10)
                    ?: emptyList()

                val suggestion = suggestedActionGenerator.generate(rule, event, topContributors)

                AlertEvent(
                    id = UUID.randomUUID().toString(),
                    ruleId = rule.id,
                    ruleName = rule.name,
                    type = rule.type,
                    severity = rule.severity,
                    message = "${rule.name}: ${rule.type} ${rule.operator} ${rule.threshold} (current: $currentValue) for book ${event.bookId}",
                    currentValue = currentValue,
                    threshold = rule.threshold,
                    bookId = event.bookId,
                    triggeredAt = clock.instant(),
                    correlationId = event.correlationId,
                    contributors = serializeTopContributors(event.positionBreakdown),
                    suggestedAction = suggestion,
                )
            } else {
                null
            }
        }

        // Auto-resolve: any TRIGGERED alert for this book whose rule no longer fires
        autoResolve(event, rules, firedRuleIds)

        if (alerts.isEmpty()) {
            logger.debug("No alerts triggered for book={}, rules evaluated={}", event.bookId, rules.size)
        }

        return alerts
    }

    private suspend fun autoResolve(
        event: RiskResultEvent,
        rules: List<AlertRule>,
        firedRuleIds: Set<String>,
    ) {
        val activeAlerts = eventRepository?.findActiveByBook(event.bookId) ?: return
        for (alert in activeAlerts) {
            if (alert.ruleId in firedRuleIds) continue
            // Rule still exists but no longer fires → auto-resolve
            val ruleStillExists = rules.any { it.id == alert.ruleId }
            if (ruleStillExists) {
                eventRepository.updateStatus(
                    id = alert.id,
                    status = AlertStatus.RESOLVED,
                    resolvedAt = clock.instant(),
                    resolvedReason = "AUTO_CLEARED",
                )
                logger.info("Auto-resolved alert={} for book={}", alert.id, alert.bookId)
            }
        }
    }

    private fun extractMetric(type: AlertType, event: RiskResultEvent): Double? {
        val extractor = extractorsByType[type] ?: return null
        return extractor.extract(event)
    }

    private fun compare(value: Double, operator: ComparisonOperator, threshold: Double): Boolean = when (operator) {
        ComparisonOperator.GREATER_THAN -> value > threshold
        ComparisonOperator.LESS_THAN -> value < threshold
        ComparisonOperator.EQUALS -> value == threshold
    }

    private fun serializeTopContributors(breakdown: List<PositionBreakdownItem>?): String? {
        if (breakdown.isNullOrEmpty()) return null
        val top10 = breakdown
            .sortedByDescending { abs(it.varContribution.toDoubleOrNull() ?: 0.0) }
            .take(10)
        return Json.encodeToString(top10)
    }
}
