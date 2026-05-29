package com.kinetix.fix.canary

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit

class MicrometerSliReaderTest : FunSpec({

    // -----------------------------------------------------------------
    // Rejection rate
    // -----------------------------------------------------------------

    test("rejection rate is 0.0 when no orders have been recorded") {
        val registry = SimpleMeterRegistry()
        MicrometerSliReader(registry).rejectionRatePct() shouldBe 0.0
    }

    test("rejection rate is 0.0 when only successful cancels exist") {
        val registry = SimpleMeterRegistry()
        registry.counter("fix_messages_out_total", "venue", "NYSE", "msg_type", "ORDER_CANCEL_REQUEST").increment()
        registry.counter("fix_messages_out_total", "venue", "NYSE", "msg_type", "ORDER_CANCEL_REQUEST").increment()

        MicrometerSliReader(registry).rejectionRatePct() shouldBe 0.0
    }

    test("rejection rate reflects cancel_failed_total as fraction of total outbound attempts") {
        val registry = SimpleMeterRegistry()
        // 2 successful cancels, 1 new order, 1 failed → 1 / (2+1+1) = 25%
        registry.counter("fix_messages_out_total", "venue", "NYSE", "msg_type", "ORDER_CANCEL_REQUEST").increment(2.0)
        registry.counter("fix_messages_out_total", "venue", "NYSE", "msg_type", "NEW_ORDER_SINGLE").increment()
        registry.counter("cancel_failed_total", "venue", "NYSE", "reason", "SESSION_DOWN").increment()

        val rate = MicrometerSliReader(registry).rejectionRatePct()
        rate shouldBe 25.0
    }

    // -----------------------------------------------------------------
    // Uptime
    // -----------------------------------------------------------------

    test("uptime is 100.0 when no messages have been recorded") {
        val registry = SimpleMeterRegistry()
        MicrometerSliReader(registry).uptimePct() shouldBe 100.0
    }

    test("uptime is 100.0 when all outbound messages were successfully sent") {
        val registry = SimpleMeterRegistry()
        registry.counter("fix_messages_out_total", "venue", "NYSE", "msg_type", "ORDER_CANCEL_REQUEST").increment(10.0)

        MicrometerSliReader(registry).uptimePct() shouldBe 100.0
    }

    test("uptime falls below 100 when SESSION_DOWN cancels are recorded") {
        val registry = SimpleMeterRegistry()
        // 9 sent, 1 session-down → down fraction = 1/10 = 10% → uptime = 90%
        registry.counter("fix_messages_out_total", "venue", "NYSE", "msg_type", "ORDER_CANCEL_REQUEST").increment(9.0)
        registry.counter("cancel_failed_total", "venue", "NYSE", "reason", "SESSION_DOWN").increment()

        MicrometerSliReader(registry).uptimePct() shouldBe 90.0
    }

    // -----------------------------------------------------------------
    // Ack latency
    // -----------------------------------------------------------------

    test("ack latency is 0.0 when no timer samples exist") {
        val registry = SimpleMeterRegistry()
        MicrometerSliReader(registry).avgAckLatencyMs() shouldBe 0.0
    }

    test("ack latency reflects mean of cancel_ack_latency timer samples") {
        val registry = SimpleMeterRegistry()
        val timer = io.micrometer.core.instrument.Timer.builder("cancel_ack_latency")
            .tag("venue", "NYSE")
            .register(registry)
        timer.record(100, TimeUnit.MILLISECONDS)
        timer.record(200, TimeUnit.MILLISECONDS)

        // Mean = 150ms
        val latency = MicrometerSliReader(registry).avgAckLatencyMs()
        latency shouldBeGreaterThan 149.0
        latency shouldBeLessThan 151.0
    }

    test("ack latency aggregates across multiple venue timers") {
        val registry = SimpleMeterRegistry()
        val timerNyse = io.micrometer.core.instrument.Timer.builder("cancel_ack_latency")
            .tag("venue", "NYSE")
            .register(registry)
        val timerLse = io.micrometer.core.instrument.Timer.builder("cancel_ack_latency")
            .tag("venue", "LSE")
            .register(registry)
        timerNyse.record(100, TimeUnit.MILLISECONDS) // 1 sample at 100ms
        timerLse.record(300, TimeUnit.MILLISECONDS)  // 1 sample at 300ms

        // Overall mean = (100 + 300) / 2 = 200ms
        val latency = MicrometerSliReader(registry).avgAckLatencyMs()
        latency shouldBeGreaterThan 199.0
        latency shouldBeLessThan 201.0
    }
})
