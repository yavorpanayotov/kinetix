package com.kinetix.referencedata.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.model.CreditSpread
import com.kinetix.common.model.DividendYield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaReferenceDataPublisher(
    private val producer: KafkaProducer<String, String>,
) : ReferenceDataPublisher {

    private val logger = LoggerFactory.getLogger(KafkaReferenceDataPublisher::class.java)

    override suspend fun publishDividendYield(dividendYield: DividendYield) {
        val event = DividendYieldEvent.from(dividendYield)
        val json = Json.encodeToString(event)
        val record = ProducerRecord("reference-data.dividends", dividendYield.instrumentId.value, json)
        send(record, "dividend yield", dividendYield.instrumentId.value)
    }

    override suspend fun publishCreditSpread(creditSpread: CreditSpread) {
        val event = CreditSpreadEvent.from(creditSpread)
        val json = Json.encodeToString(event)
        val record = ProducerRecord("reference-data.credit-spreads", creditSpread.instrumentId.value, json)
        send(record, "credit spread", creditSpread.instrumentId.value)
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
