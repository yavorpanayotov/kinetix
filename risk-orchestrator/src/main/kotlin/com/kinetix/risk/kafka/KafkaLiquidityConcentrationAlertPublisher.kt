package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.KafkaOtelHeaderWriter
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.common.model.LiquidityRiskResult
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
 * Publishes a [RiskResultEvent] to `risk.results` when portfolio-level liquidity
 * concentration is detected (concentrationCount > 0).
 *
 * The event carries [RiskResultEvent.liquidityConcentrationStatus] populated from
 * [LiquidityRiskResult.portfolioConcentrationStatus]. The notification-service's
 * `LiquidityConcentrationExtractor` reads this field and fires any matching
 * LIQUIDITY_CONCENTRATION alert rules.
 */
class KafkaLiquidityConcentrationAlertPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.results",
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : LiquidityConcentrationAlertPublisher {

    private val logger = LoggerFactory.getLogger(KafkaLiquidityConcentrationAlertPublisher::class.java)

    override suspend fun publishConcentrationAlert(result: LiquidityRiskResult) {
        val event = RiskResultEvent(
            bookId = result.bookId,
            varValue = result.portfolioLvar.toString(),
            expectedShortfall = "0.0",
            calculationType = "LIQUIDITY_CONCENTRATION",
            calculatedAt = Instant.now().toString(),
            liquidityConcentrationStatus = result.portfolioConcentrationStatus,
        )
        val json = Json.encodeToString(event)
        val record = KafkaOtelHeaderWriter.injectTraceContext(
            KafkaCorrelationIdHeaderWriter.withCorrelationId(
                ProducerRecord(topic, result.bookId, json)
            ),
            openTelemetry,
        )
        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
            logger.info(
                "Published LIQUIDITY_CONCENTRATION alert for book {} (concentrationStatus={})",
                result.bookId, result.portfolioConcentrationStatus,
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to publish LIQUIDITY_CONCENTRATION alert for book {}: {}",
                result.bookId, e.message,
            )
        }
    }
}
