package com.kinetix.common.observability

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.RetryableConsumer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.slf4j.MDC

class CorrelationIdKafkaTracingTest : FunSpec({

    afterEach { MDC.remove(CorrelationIdContext.MDC_KEY) }

    // --- Producer side ---

    test("withCorrelationId injects X-Correlation-ID header when MDC contains correlationId") {
        MDC.put(CorrelationIdContext.MDC_KEY, "kafka-producer-id")
        val original = ProducerRecord<String, String>("my-topic", "key", "value")
        val enriched = KafkaCorrelationIdHeaderWriter.withCorrelationId(original)

        val headerValue = enriched.headers().lastHeader(CorrelationIdContext.HEADER_NAME)
            ?.value()?.toString(Charsets.UTF_8)
        headerValue shouldBe "kafka-producer-id"
    }

    test("withCorrelationId does not inject header when MDC is empty") {
        MDC.remove(CorrelationIdContext.MDC_KEY)
        val original = ProducerRecord<String, String>("my-topic", "key", "value")
        val enriched = KafkaCorrelationIdHeaderWriter.withCorrelationId(original)

        val header = enriched.headers().lastHeader(CorrelationIdContext.HEADER_NAME)
        header shouldBe null
    }

    test("withCorrelationId preserves existing record headers alongside the new one") {
        MDC.put(CorrelationIdContext.MDC_KEY, "id-preserve")
        val original = ProducerRecord<String, String>("my-topic", "key", "value")
        original.headers().add("x-existing", "existing-value".toByteArray())
        val enriched = KafkaCorrelationIdHeaderWriter.withCorrelationId(original)

        enriched.headers().lastHeader("x-existing")?.value()?.toString(Charsets.UTF_8) shouldBe "existing-value"
        enriched.headers().lastHeader(CorrelationIdContext.HEADER_NAME)?.value()?.toString(Charsets.UTF_8) shouldBe "id-preserve"
    }

    // --- Consumer side ---

    test("RetryableConsumer extracts X-Correlation-ID from record headers and sets MDC for handler") {
        var capturedMdc: String? = null
        val consumer = RetryableConsumer(topic = "test.topic")

        val headers = RecordHeaders().apply {
            add(CorrelationIdContext.HEADER_NAME, "consumer-id-abc".toByteArray(Charsets.UTF_8))
        }

        runTest {
            consumer.process(
                key = "k",
                value = "v",
                originalHeaders = headers,
            ) {
                capturedMdc = MDC.get(CorrelationIdContext.MDC_KEY)
            }
        }

        capturedMdc shouldBe "consumer-id-abc"
    }

    test("RetryableConsumer leaves MDC unchanged after handler completes") {
        MDC.put(CorrelationIdContext.MDC_KEY, "pre-existing")
        val consumer = RetryableConsumer(topic = "test.topic")

        val headers = RecordHeaders().apply {
            add(CorrelationIdContext.HEADER_NAME, "transient-id".toByteArray(Charsets.UTF_8))
        }

        runTest {
            consumer.process(key = "k", value = "v", originalHeaders = headers) { }
        }

        // Should be restored to original value
        MDC.get(CorrelationIdContext.MDC_KEY) shouldBe "pre-existing"
    }

    test("RetryableConsumer works normally when no X-Correlation-ID header is present") {
        var handlerCalled = false
        val consumer = RetryableConsumer(topic = "test.topic")

        runTest {
            consumer.process(key = "k", value = "v", originalHeaders = null) {
                handlerCalled = true
            }
        }

        handlerCalled shouldBe true
    }
})
