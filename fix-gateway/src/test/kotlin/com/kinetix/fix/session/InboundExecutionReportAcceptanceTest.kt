package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.fix.kafka.ExecutionReportProducerFactory
import com.kinetix.fix.kafka.KafkaExecutionReportPublisher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.time.Instant
import java.util.Properties

/**
 * End-to-end inbound path: raw FIX → [InboundFixHandler] → real Kafka (`execution.reports`).
 * Plan §3.10. Drives the handler with crafted FIX messages that mirror the bytes a
 * QuickFIX/J `Application.fromApp` callback would receive at runtime; the wire-format
 * contract is the same on both code paths.
 *
 * Coverage:
 *   - 35=8 Fill / Partial / Cancelled / Replace
 *   - 35=9 OrderCancelReject → REJECTED keyed by OrigClOrdID
 *   - 35=j BusinessMessageReject → BUSINESS_REJECT
 *   - FIX 4.2 ExecType=1 (Partial) AND FIX 4.4 ExecType=F (Fill) variants
 *   - PossDupFlag=Y inbound → no duplicate event on the topic
 *   - Missing ClOrdID → event publishes (no drop) and partition-key falls back to venue
 */
class InboundExecutionReportAcceptanceTest : FunSpec({

    val kafka = KafkaContainer("apache/kafka:3.8.1")
    val sessionId = "FIX.4.4:KINETIX->NYSE"
    val fixVersion44 = "FIX.4.4"
    val fixVersion42 = "FIX.4.2"
    val fixedClock = { Instant.parse("2026-05-07T10:00:00Z") }

    beforeSpec { kafka.start() }
    afterSpec { kafka.stop() }

    fun newHandler(topic: String): Triple<InboundFixHandler, KafkaExecutionReportPublisher, SimpleMeterRegistry> {
        val producer = ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers)
        val publisher = KafkaExecutionReportPublisher(producer, topic = topic)
        val registry = SimpleMeterRegistry()
        val handler = InboundFixHandler(
            converter = FIXMessageConverter(),
            publisher = publisher,
            meterRegistry = registry,
            clock = fixedClock,
        )
        return Triple(handler, publisher, registry)
    }

    fun consume(topic: String, expected: Int = 1, timeoutSec: Long = 10): List<Pair<String?, ExecutionReportEvent>> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "consume-${System.nanoTime()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val out = mutableListOf<Pair<String?, ExecutionReportEvent>>()
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos()
            while (out.size < expected && System.nanoTime() < deadline) {
                val records = consumer.poll(Duration.ofMillis(500))
                for (r in records) {
                    out += r.key() to Json.decodeFromString<ExecutionReportEvent>(r.value())
                }
            }
        }
        return out
    }

    test("FIX 4.4 35=8 Fill flows raw-FIX → Kafka with FILL discriminator and clOrdId partition key") {
        val topic = "exec.fill44.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)

        val msg = fix(
            "35" to "8", "11" to "clord-44-fill", "17" to "exec-44-fill", "37" to "ord-44-fill",
            "150" to "F", "32" to "100", "31" to "150.25", "14" to "100", "6" to "150.25",
            "30" to "NYSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion44)

        val received = consume(topic)
        received.size shouldBe 1
        received[0].first shouldBe "clord-44-fill"
        received[0].second.eventType shouldBe ExecutionEventType.FILL
        received[0].second.execType shouldBe "F"
        received[0].second.fixVersion shouldBe "FIX.4.4"
    }

    test("FIX 4.2 35=8 ExecType=1 Partial flows as PARTIAL_FILL discriminator") {
        val topic = "exec.partial42.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)

        val msg = fix(
            "35" to "8", "11" to "clord-42-partial", "17" to "exec-42-partial", "37" to "ord-42-partial",
            "150" to "1", "32" to "30", "31" to "150.20", "14" to "30", "6" to "150.20",
            "30" to "LSE",
        )
        handler.handle(msg, sessionId, fixVersion42)

        val received = consume(topic)
        received[0].second.eventType shouldBe ExecutionEventType.PARTIAL_FILL
        received[0].second.execType shouldBe "1"
        received[0].second.fixVersion shouldBe "FIX.4.2"
    }

    test("35=8 ExecType=4 flows as CANCELLED") {
        val topic = "exec.cancelled.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)
        val msg = fix(
            "35" to "8", "11" to "clord-cxl", "17" to "exec-cxl", "37" to "ord-cxl",
            "150" to "4", "32" to "0", "31" to "0", "14" to "50", "6" to "149.90",
            "30" to "NYSE",
        )
        handler.handle(msg, sessionId, fixVersion44)
        val received = consume(topic)
        received[0].second.eventType shouldBe ExecutionEventType.CANCELLED
    }

    test("35=8 ExecType=5 flows as REPLACED with new qty/price in lastQty/lastPrice") {
        val topic = "exec.replace.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)
        val msg = fix(
            "35" to "8", "11" to "clord-rep", "17" to "exec-rep", "37" to "ord-rep",
            "150" to "5", "32" to "200", "31" to "151.00", "14" to "0", "6" to "0",
            "30" to "NYSE",
        )
        handler.handle(msg, sessionId, fixVersion44)
        val received = consume(topic)
        received[0].second.eventType shouldBe ExecutionEventType.REPLACED
        received[0].second.lastQty shouldBe "200"
        received[0].second.lastPrice shouldBe "151.00"
    }

    test("35=9 OrderCancelReject flows as REJECTED keyed by OrigClOrdID") {
        val topic = "exec.cxlrej.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)
        val msg = fix(
            "35" to "9", "11" to "clord-cxl-1", "37" to "ord-1", "41" to "clord-orig-1",
            "434" to "1", "102" to "0", "58" to "Too late to cancel", "30" to "NYSE",
        )
        handler.handle(msg, sessionId, fixVersion44)

        val received = consume(topic)
        received.size shouldBe 1
        received[0].first shouldBe "clord-orig-1"
        received[0].second.eventType shouldBe ExecutionEventType.REJECTED
        received[0].second.rejectReason shouldBe "Too late to cancel"
        received[0].second.rejectCode shouldBe "0"
    }

    test("35=j BusinessMessageReject flows as BUSINESS_REJECT") {
        val topic = "exec.bizrej.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)
        val msg = fix(
            "35" to "j", "45" to "5", "372" to "D",
            "380" to "5", "58" to "Unknown instrument", "30" to "NYSE",
        )
        handler.handle(msg, sessionId, fixVersion44)

        val received = consume(topic)
        received.size shouldBe 1
        received[0].second.eventType shouldBe ExecutionEventType.BUSINESS_REJECT
        received[0].second.rejectReason shouldBe "Unknown instrument"
    }

    test("PossDupFlag=Y inbound does NOT produce a downstream event on execution.reports") {
        val topic = "exec.dup.${System.nanoTime()}"
        val (handler, _, registry) = newHandler(topic)

        val msg = fix(
            "35" to "8", "11" to "clord-dup", "17" to "exec-dup", "37" to "ord-dup",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "NYSE", "38" to "100", "43" to "Y",
        )
        handler.handle(msg, sessionId, fixVersion44)

        // Use shorter timeout — we EXPECT the topic to remain empty
        val received = consume(topic, expected = 1, timeoutSec = 3)
        received.shouldBeEmpty()
        registry.counter("fix_messages_in_total", "venue", "NYSE", "msg_type", "POSS_DUP_DROPPED").count() shouldBe 1.0
    }

    test("missing ClOrdID still publishes; partition key falls back to venue") {
        val topic = "exec.noclord.${System.nanoTime()}"
        val (handler, _, registry) = newHandler(topic)

        val msg = fix(
            "35" to "8", "17" to "exec-no-clord", "37" to "ord-no-clord",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "LSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion44)

        val received = consume(topic)
        received.size shouldBe 1
        received[0].first shouldBe "LSE"
        received[0].second.clOrdId shouldBe ""
        registry.counter("malformed_inbound_total", "venue", "LSE", "defect", "MISSING_CLORD_ID").count() shouldBe 1.0
    }

    test("eventId is unique across two physically identical inbound messages") {
        val topic = "exec.distinct.${System.nanoTime()}"
        val (handler, _, _) = newHandler(topic)
        val msg = fix(
            "35" to "8", "11" to "clord-d", "17" to "exec-d", "37" to "ord-d",
            "150" to "F", "32" to "100", "31" to "150.00", "14" to "100", "6" to "150.00",
            "30" to "NYSE", "38" to "100",
        )
        handler.handle(msg, sessionId, fixVersion44)
        handler.handle(msg, sessionId, fixVersion44)
        val received = consume(topic, expected = 2)
        received.size shouldBe 2
        received[0].second.eventId shouldNotBe received[1].second.eventId
        received.map { it.second.eventType } shouldContain ExecutionEventType.FILL
    }
})

private fun fix(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("|") { (tag, value) -> "$tag=$value" }
