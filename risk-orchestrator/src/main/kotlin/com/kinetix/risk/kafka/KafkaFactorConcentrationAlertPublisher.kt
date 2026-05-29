package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.KafkaOtelHeaderWriter
import com.kinetix.common.kafka.events.ConcentrationItem
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.risk.model.FactorDecompositionSnapshot
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Publishes a [RiskResultEvent] to `risk.results` when the factor decomposition engine
 * reports a concentration warning.
 *
 * The event carries a sentinel [ConcentrationItem] with
 * [instrumentId] = "FACTOR_MODEL" and percentage = 100.0. The notification-service
 * `FactorConcentrationExtractor` recognises this sentinel and fires the
 * FACTOR_CONCENTRATION alert type for any matching alert rules.
 */
class KafkaFactorConcentrationAlertPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.results",
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : FactorConcentrationAlertPublisher {

    private val logger = LoggerFactory.getLogger(KafkaFactorConcentrationAlertPublisher::class.java)

    override suspend fun publishConcentrationWarning(snapshot: FactorDecompositionSnapshot) {
        val event = RiskResultEvent(
            bookId = snapshot.bookId,
            varValue = snapshot.totalVar.toString(),
            expectedShortfall = "0.0",
            calculationType = "FACTOR_DECOMPOSITION",
            calculatedAt = Instant.now().toString(),
            concentrationByInstrument = listOf(
                ConcentrationItem(
                    instrumentId = FACTOR_CONCENTRATION_SENTINEL_ID,
                    percentage = 100.0,
                )
            ),
        )
        val json = Json.encodeToString(event)
        val record = KafkaOtelHeaderWriter.injectTraceContext(
            KafkaCorrelationIdHeaderWriter.withCorrelationId(
                ProducerRecord(topic, snapshot.bookId, json)
            ),
            openTelemetry,
        )
        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
            logger.info(
                "Published factor concentration alert for book {} (totalVar={}, systematicVar={})",
                snapshot.bookId, snapshot.totalVar, snapshot.systematicVar,
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to publish factor concentration alert for book {}: {}",
                snapshot.bookId, e.message,
            )
        }
    }

    companion object {
        const val FACTOR_CONCENTRATION_SENTINEL_ID = "FACTOR_MODEL"
    }
}
