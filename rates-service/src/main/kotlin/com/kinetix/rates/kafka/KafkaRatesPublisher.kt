package com.kinetix.rates.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.model.ForwardCurve
import com.kinetix.common.model.RiskFreeRate
import com.kinetix.common.model.YieldCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaRatesPublisher(
    private val producer: KafkaProducer<String, String>,
) : RatesPublisher {

    private val logger = LoggerFactory.getLogger(KafkaRatesPublisher::class.java)

    override suspend fun publishYieldCurve(curve: YieldCurve) {
        val event = YieldCurveEvent.from(curve)
        val json = Json.encodeToString(event)
        val record = ProducerRecord("rates.yield-curves", curve.curveId, json)
        send(record, "yield curve", curve.curveId)
    }

    override suspend fun publishRiskFreeRate(rate: RiskFreeRate) {
        val event = RiskFreeRateEvent.from(rate)
        val json = Json.encodeToString(event)
        val key = "${rate.currency.currencyCode}:${rate.tenor}"
        val record = ProducerRecord("rates.risk-free", key, json)
        send(record, "risk-free rate", key)
    }

    override suspend fun publishForwardCurve(curve: ForwardCurve) {
        val event = ForwardCurveEvent.from(curve)
        val json = Json.encodeToString(event)
        val record = ProducerRecord("rates.forwards", curve.instrumentId.value, json)
        send(record, "forward curve", curve.instrumentId.value)
    }

    private suspend fun send(record: ProducerRecord<String, String>, type: String, key: String) {
        val recordWithId = KafkaCorrelationIdHeaderWriter.withCorrelationId(record)
        try {
            withContext(Dispatchers.IO) {
                producer.send(recordWithId).get()
            }
        } catch (e: Exception) {
            logger.error("Failed to publish {} event to Kafka: key={}, topic={}", type, key, record.topic(), e)
        }
    }
}
