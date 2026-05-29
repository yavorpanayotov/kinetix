package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.KafkaOtelHeaderWriter
import com.kinetix.common.kafka.events.InstrumentPnlItem
import com.kinetix.common.kafka.events.IntradayPnlEvent
import com.kinetix.risk.model.IntradayPnlSnapshot
import com.kinetix.risk.service.IntradayPnlPublisher
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaIntradayPnlPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.pnl.intraday",
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
) : IntradayPnlPublisher {

    private val logger = LoggerFactory.getLogger(KafkaIntradayPnlPublisher::class.java)

    override suspend fun publish(snapshot: IntradayPnlSnapshot) {
        val event = IntradayPnlEvent(
            bookId = snapshot.bookId.value,
            snapshotAt = snapshot.snapshotAt.toString(),
            baseCurrency = snapshot.baseCurrency,
            trigger = snapshot.trigger.name.lowercase(),
            totalPnl = snapshot.totalPnl.toPlainString(),
            realisedPnl = snapshot.realisedPnl.toPlainString(),
            unrealisedPnl = snapshot.unrealisedPnl.toPlainString(),
            deltaPnl = snapshot.deltaPnl.toPlainString(),
            gammaPnl = snapshot.gammaPnl.toPlainString(),
            vegaPnl = snapshot.vegaPnl.toPlainString(),
            thetaPnl = snapshot.thetaPnl.toPlainString(),
            rhoPnl = snapshot.rhoPnl.toPlainString(),
            vannaPnl = snapshot.vannaPnl.toPlainString(),
            volgaPnl = snapshot.volgaPnl.toPlainString(),
            charmPnl = snapshot.charmPnl.toPlainString(),
            crossGammaPnl = snapshot.crossGammaPnl.toPlainString(),
            unexplainedPnl = snapshot.unexplainedPnl.toPlainString(),
            highWaterMark = snapshot.highWaterMark.toPlainString(),
            instrumentPnl = snapshot.instrumentPnl.map { pos ->
                InstrumentPnlItem(
                    instrumentId = pos.instrumentId,
                    assetClass = pos.assetClass,
                    totalPnl = pos.totalPnl,
                    deltaPnl = pos.deltaPnl,
                    gammaPnl = pos.gammaPnl,
                    vegaPnl = pos.vegaPnl,
                    thetaPnl = pos.thetaPnl,
                    rhoPnl = pos.rhoPnl,
                    vannaPnl = pos.vannaPnl,
                    volgaPnl = pos.volgaPnl,
                    charmPnl = pos.charmPnl,
                    crossGammaPnl = pos.crossGammaPnl,
                    unexplainedPnl = pos.unexplainedPnl,
                )
            }.ifEmpty { null },
            correlationId = snapshot.correlationId,
            missingFxRates = snapshot.missingFxRates,
        )
        val json = Json.encodeToString(event)
        val record = KafkaOtelHeaderWriter.injectTraceContext(
            KafkaCorrelationIdHeaderWriter.withCorrelationId(
                ProducerRecord(topic, snapshot.bookId.value, json)
            ),
            openTelemetry,
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish intraday P&L event to Kafka: bookId={}, topic={}",
                snapshot.bookId.value, topic, e,
            )
        }
    }
}
