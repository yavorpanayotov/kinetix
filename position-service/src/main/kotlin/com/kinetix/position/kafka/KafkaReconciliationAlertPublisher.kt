package com.kinetix.position.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.position.fix.PrimeBrokerReconciliation
import com.kinetix.position.fix.ReconciliationBreak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Publishes one RECONCILIATION_BREAK risk-result event per material break
 * to the `risk.results` topic, per `execution.allium:437-448`
 * (`AlertOnReconciliationBreaks`). The notification-service `RiskResultConsumer`
 * evaluates each event independently against alert rules — alert rules
 * configured for `RECONCILIATION_BREAK` will fire once per break rather than
 * once per reconciliation.
 *
 * Per-break delivery means a reconciliation with N material breaks produces
 * N alerts; downstream alert dedup or aggregation lives in the notification
 * service, not here.
 */
class KafkaReconciliationAlertPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.results",
) : ReconciliationAlertPublisher {

    private val logger = LoggerFactory.getLogger(KafkaReconciliationAlertPublisher::class.java)

    override suspend fun publishBreakAlert(
        reconciliation: PrimeBrokerReconciliation,
        break_: ReconciliationBreak,
    ) {
        val event = RiskResultEvent(
            bookId = reconciliation.bookId,
            varValue = "0.0",
            expectedShortfall = "0.0",
            calculationType = "RECONCILIATION_BREAK",
            calculatedAt = Instant.now().toString(),
        )

        val json = Json.encodeToString(event)
        // Key by book + instrument so per-break alerts land on consistent partitions
        // and downstream consumers can dedup by (bookId, instrumentId).
        val key = "${reconciliation.bookId}|${break_.instrumentId}"
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, key, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
            logger.warn(
                "Published RECONCILIATION_BREAK alert for book={} instrument={} severity={} notional={}",
                reconciliation.bookId, break_.instrumentId, break_.severity, break_.breakNotional,
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to publish RECONCILIATION_BREAK alert for book={} instrument={}: {}",
                reconciliation.bookId, break_.instrumentId, e.message,
            )
        }
    }
}
