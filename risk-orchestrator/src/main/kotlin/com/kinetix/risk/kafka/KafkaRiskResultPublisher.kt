package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.KafkaOtelHeaderWriter
import com.kinetix.common.kafka.events.ComponentBreakdownEvent
import com.kinetix.common.kafka.events.PositionBreakdownItem
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.risk.model.ValuationResult
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaRiskResultPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.results",
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : RiskResultPublisher {

    private val logger = LoggerFactory.getLogger(KafkaRiskResultPublisher::class.java)

    override suspend fun publish(result: ValuationResult, correlationId: String?) {
        val event = RiskResultEvent(
            bookId = result.bookId.value,
            calculationType = result.calculationType.name,
            confidenceLevel = result.confidenceLevel.name,
            varValue = (result.varValue ?: 0.0).toString(),
            expectedShortfall = (result.expectedShortfall ?: 0.0).toString(),
            componentBreakdown = result.componentBreakdown.map {
                ComponentBreakdownEvent(
                    assetClass = it.assetClass.name,
                    varContribution = it.varContribution.toString(),
                    percentageOfTotal = it.percentageOfTotal.toString(),
                )
            },
            calculatedAt = result.calculatedAt.toString(),
            correlationId = correlationId,
            positionBreakdown = result.positionRisk.map {
                PositionBreakdownItem(
                    instrumentId = it.instrumentId.value,
                    assetClass = it.assetClass.name,
                    marketValue = it.marketValue.toPlainString(),
                    varContribution = it.varContribution.toPlainString(),
                    percentageOfTotal = it.percentageOfTotal.toPlainString(),
                    delta = it.delta?.toString(),
                    gamma = it.gamma?.toString(),
                    vega = it.vega?.toString(),
                )
            }.ifEmpty { null },
        )
        val json = Json.encodeToString(event)
        val record = KafkaOtelHeaderWriter.injectTraceContext(
            KafkaCorrelationIdHeaderWriter.withCorrelationId(
                ProducerRecord(topic, result.bookId.value, json)
            ),
            openTelemetry,
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish risk result to Kafka: bookId={}, topic={}",
                result.bookId.value, topic, e,
            )
        }
    }
}
