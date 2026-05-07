package com.kinetix.common.kafka.events

import kotlinx.serialization.Serializable

/**
 * Risk-side break event published to the `risk.breaks` Kafka topic. Drives
 * ops alerting + the trader-facing RiskAlertBanner. ADR-0035 phase 2 uses
 * this for ghost-fill detection: a FIX 35=8 fill arriving against an
 * EXPIRED / CANCELLED / REJECTED order publishes a CRITICAL break that
 * requires manual ops resolution (Position is NOT auto-updated).
 *
 * Severity vocabulary is intentionally bounded so consumers can switch on
 * the string without dynamic-config worries:
 *   - INFO     informational; no action required
 *   - WARNING  needs attention but does not block trading
 *   - CRITICAL needs ops intervention; surfaces via paging alert + banner
 */
@Serializable
data class RiskBreakEvent(
    val eventId: String,
    val breakType: String,
    val severity: String,
    val message: String,
    val occurredAt: String,
    val orderId: String? = null,
    val bookId: String? = null,
    val instrumentId: String? = null,
    val venue: String? = null,
    val correlationId: String? = null,
    /**
     * Free-form supplementary context. Consumers MUST NOT use values from
     * here as Prometheus labels (label cardinality risk) — they're for
     * human-readable surfacing only.
     */
    val attributes: Map<String, String> = emptyMap(),
)
