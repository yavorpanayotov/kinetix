package com.kinetix.position.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.TradeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaTradeEventPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "trades.lifecycle",
) : TradeEventPublisher {

    private val logger = LoggerFactory.getLogger(KafkaTradeEventPublisher::class.java)

    override suspend fun publish(event: TradeEvent) {
        val message = TradeEventMessage.from(event)
        val json = Json.encodeToString(message)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, event.trade.bookId.value, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish trade event to Kafka: tradeId={}, topic={}",
                event.trade.tradeId.value, topic, e,
            )
        }
    }
}
