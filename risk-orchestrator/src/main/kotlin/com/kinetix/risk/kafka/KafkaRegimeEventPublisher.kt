package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.KafkaOtelHeaderWriter
import com.kinetix.common.kafka.events.MarketRegimeEvent
import com.kinetix.risk.model.MarketRegime
import com.kinetix.risk.model.RegimeState
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaRegimeEventPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.regime.changes",
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : RegimeEventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaRegimeEventPublisher::class.java)

    override suspend fun publish(from: MarketRegime, to: RegimeState, correlationId: String?) {
        val params = to.varParameters
        val signals = to.signals
        val event = MarketRegimeEvent(
            regime = to.regime.name,
            previousRegime = from.name,
            transitionedAt = to.detectedAt.toString(),
            confidence = to.confidence.toString(),
            degradedInputs = to.degradedInputs,
            consecutiveObservations = to.consecutiveObservations,
            realisedVol20d = signals.realisedVol20d.toString(),
            crossAssetCorrelation = signals.crossAssetCorrelation.toString(),
            creditSpreadBps = signals.creditSpreadBps?.toString(),
            pnlVolatility = signals.pnlVolatility?.toString(),
            effectiveCalculationType = params.calculationType.name,
            effectiveConfidenceLevel = params.confidenceLevel.name,
            effectiveTimeHorizonDays = params.timeHorizonDays,
            effectiveCorrelationMethod = params.correlationMethod,
            effectiveNumSimulations = params.numSimulations,
            correlationId = correlationId,
        )
        val json = Json.encodeToString(event)
        // Constant partition key: regime transitions must be ordered globally
        val record = KafkaOtelHeaderWriter.injectTraceContext(
            KafkaCorrelationIdHeaderWriter.withCorrelationId(
                ProducerRecord(topic, "regime", json)
            ),
            openTelemetry,
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish regime change event to Kafka: from={}, to={}, topic={}",
                from, to.regime, topic, e,
            )
        }
    }
}
