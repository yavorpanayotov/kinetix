package com.kinetix.fix.grpc

import com.kinetix.fix.session.CancelMessageBuilder
import com.kinetix.fix.session.FixSessionSender
import com.kinetix.fix.session.NewOrderSingleBuilder
import com.kinetix.fix.session.PendingNewCorrelator
import com.kinetix.fix.session.RecordingFixSessionSender
import com.kinetix.fix.session.SendOutcome
import com.kinetix.fix.venue.VenueCutoffRegistry
import com.kinetix.fix.venue.VenueSessionRegistry
import com.kinetix.proto.execution.CancelOrderRequest
import com.kinetix.proto.execution.OrderType
import com.kinetix.proto.execution.PlaceOrderRequest
import com.kinetix.proto.execution.Side
import com.kinetix.proto.execution.TimeInForce
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant

/**
 * Instrumentation contract for the five metrics the FIX Gateway Grafana dashboard
 * queries. PR 2 of docs/plans/grafana-v2.md covers the four whose code paths already
 * exist:
 *   - fix_messages_out_total          (Counter, tags: venue, msg_type)
 *   - cancel_ack_latency_seconds      (Timer,   tag:  venue)
 *   - cancel_failed_total             (Counter, tags: venue, reason)
 *   - unacknowledged_outbound_total   (Gauge,   tags: venue, msg_type)
 *
 * Each metric is asserted twice: once for the wire-format Prometheus name via a
 * real [PrometheusMeterRegistry] `.scrape()` (the dashboard PromQL must match it
 * exactly), and once at the real call site driving [FixGatewayServiceImpl] /
 * [PendingNewCorrelator] so the meter is proven to move on actual traffic — not
 * merely registered.
 *
 * The fifth dashboard metric, fix_message_log_partitions_archived_total, has no
 * underlying code path in fix-gateway (no partition-archival job exists) and is
 * out of scope for this checkbox.
 */
class FixGatewayMetricsTest : FunSpec({

    val fixedClock = { Instant.parse("2026-05-04T18:00:00Z") } // weekday, NYSE open

    fun service(
        meterRegistry: MeterRegistry,
        sessionSender: FixSessionSender = RecordingFixSessionSender(SendOutcome.Sent),
        lookup: FixGatewayServiceImpl.OriginalOrderLookup = FixGatewayServiceImpl.OriginalOrderLookup { _, _ ->
            FixGatewayServiceImpl.OriginalOrder("AAPL", '1', BigDecimal("100"))
        },
        correlator: PendingNewCorrelator = PendingNewCorrelator(meterRegistry = meterRegistry),
    ) = FixGatewayServiceImpl(
        venueSessionRegistry = VenueSessionRegistry(),
        venueCutoffRegistry = VenueCutoffRegistry(),
        cancelMessageBuilder = CancelMessageBuilder(),
        newOrderSingleBuilder = NewOrderSingleBuilder(),
        pendingNewCorrelator = correlator,
        sessionSender = sessionSender,
        originalOrderLookup = lookup,
        clock = fixedClock,
        meterRegistry = meterRegistry,
    )

    fun cancelRequest(
        clOrdId: String = "ord-1",
        venue: String = "NYSE",
        venueOrderId: String = "VENUE-1",
    ): CancelOrderRequest = CancelOrderRequest.newBuilder()
        .setClOrdId(clOrdId)
        .setVenue(venue)
        .setVenueOrderId(venueOrderId)
        .build()

    fun limitBuyAaplDay(
        clOrdId: String = "ord-place-1",
        venue: String = "NYSE",
        timeoutMs: Int = 0,
    ): PlaceOrderRequest = PlaceOrderRequest.newBuilder()
        .setClOrdId(clOrdId)
        .setVenue(venue)
        .setInstrumentId("AAPL")
        .setSide(Side.BUY)
        .setOrderType(OrderType.LIMIT)
        .setQuantity("100")
        .setLimitPrice("150.25")
        .setTimeInForce(TimeInForce.TIF_DAY)
        .setVenueAckTimeoutMs(timeoutMs)
        .build()

    // ---------------------------------------------------------------------
    // fix_messages_out_total — Counter
    // ---------------------------------------------------------------------

    test("fix_messages_out_total is a Counter tagged by venue and msg_type") {
        val registry = SimpleMeterRegistry()
        service(registry).handleCancel(cancelRequest())

        val meter = registry.find("fix_messages_out_total")
            .tag("venue", "NYSE")
            .tag("msg_type", "ORDER_CANCEL_REQUEST")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("a sent 35=F cancel increments fix_messages_out_total at the real call site") {
        val registry = SimpleMeterRegistry()
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.Sent))
            .handleCancel(cancelRequest())

        registry.counter(
            "fix_messages_out_total",
            "venue", "NYSE",
            "msg_type", "ORDER_CANCEL_REQUEST",
        ).count() shouldBe 1.0
    }

    test("a failed cancel send does NOT increment fix_messages_out_total") {
        val registry = SimpleMeterRegistry()
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.SessionDown))
            .handleCancel(cancelRequest())

        registry.find("fix_messages_out_total").counter() shouldBe null
    }

    test("a sent 35=D NewOrderSingle increments fix_messages_out_total at the real call site") {
        val registry = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(meterRegistry = registry)
        val svc = service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.Sent), correlator = correlator)

        runBlocking {
            coroutineScope {
                async(Dispatchers.Default) {
                    delay(10)
                    correlator.completePendingNew("NYSE", "ord-place-1", "VEN-1")
                }
                svc.handlePlaceOrder(limitBuyAaplDay(timeoutMs = 5_000))
            }
        }

        registry.counter(
            "fix_messages_out_total",
            "venue", "NYSE",
            "msg_type", "NEW_ORDER_SINGLE",
        ).count() shouldBe 1.0
    }

    test("fix_messages_out_total scrapes under exactly that Prometheus name") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        service(registry).handleCancel(cancelRequest())

        val scrape = registry.scrape()
        scrape shouldContain "fix_messages_out_total{"
    }

    // ---------------------------------------------------------------------
    // cancel_ack_latency_seconds — Timer
    // ---------------------------------------------------------------------

    test("cancel_ack_latency is a Timer tagged by venue") {
        val registry = SimpleMeterRegistry()
        service(registry).handleCancel(cancelRequest())

        val meter = registry.find("cancel_ack_latency").tag("venue", "NYSE").meter()
        meter shouldNotBe null
        (meter is Timer) shouldBe true
    }

    test("handleCancel records a cancel-ack latency sample at the real call site") {
        val registry = SimpleMeterRegistry()
        service(registry).handleCancel(cancelRequest())

        val timer = registry.find("cancel_ack_latency").tag("venue", "NYSE").timer()
        timer shouldNotBe null
        timer!!.count() shouldBe 1L
        timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) shouldBeGreaterThan 0.0
    }

    test("cancel-ack latency is recorded even when the cancel send fails") {
        val registry = SimpleMeterRegistry()
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.SessionDown))
            .handleCancel(cancelRequest())

        registry.find("cancel_ack_latency").tag("venue", "NYSE").timer()!!.count() shouldBe 1L
    }

    test("cancel_ack_latency scrapes under the cancel_ack_latency_seconds Prometheus name with buckets") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        service(registry).handleCancel(cancelRequest())

        val scrape = registry.scrape()
        scrape shouldContain "cancel_ack_latency_seconds_bucket{"
        scrape shouldContain "cancel_ack_latency_seconds_count{"
    }

    // ---------------------------------------------------------------------
    // cancel_failed_total — Counter
    // ---------------------------------------------------------------------

    test("cancel_failed_total is a Counter tagged by venue and reason") {
        val registry = SimpleMeterRegistry()
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.SessionDown))
            .handleCancel(cancelRequest())

        val meter = registry.find("cancel_failed_total")
            .tag("venue", "NYSE")
            .tag("reason", "SESSION_DOWN")
            .meter()
        meter shouldNotBe null
        (meter is Counter) shouldBe true
    }

    test("a SESSION_DOWN cancel increments cancel_failed_total at the real call site") {
        val registry = SimpleMeterRegistry()
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.SessionDown))
            .handleCancel(cancelRequest())

        registry.counter("cancel_failed_total", "venue", "NYSE", "reason", "SESSION_DOWN")
            .count() shouldBe 1.0
    }

    test("an UNKNOWN_VENUE cancel increments cancel_failed_total tagged UNKNOWN_VENUE") {
        val registry = SimpleMeterRegistry()
        service(registry).handleCancel(cancelRequest(venue = "MADEUP"))

        registry.counter("cancel_failed_total", "venue", "MADEUP", "reason", "UNKNOWN_VENUE")
            .count() shouldBe 1.0
    }

    test("an INVALID_REQUEST cancel increments cancel_failed_total tagged INVALID_REQUEST") {
        val registry = SimpleMeterRegistry()
        service(registry).handleCancel(cancelRequest(venueOrderId = ""))

        registry.counter("cancel_failed_total", "venue", "NYSE", "reason", "INVALID_REQUEST")
            .count() shouldBe 1.0
    }

    test("a successful cancel does NOT increment cancel_failed_total") {
        val registry = SimpleMeterRegistry()
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.Sent))
            .handleCancel(cancelRequest())

        registry.find("cancel_failed_total").counter() shouldBe null
    }

    test("cancel_failed_total scrapes under exactly that Prometheus name") {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        service(registry, sessionSender = RecordingFixSessionSender(SendOutcome.SessionDown))
            .handleCancel(cancelRequest())

        registry.scrape() shouldContain "cancel_failed_total{"
    }

    // ---------------------------------------------------------------------
    // unacknowledged_outbound — Gauge from PendingNewCorrelator
    //
    // Registered as a Micrometer Gauge named `unacknowledged_outbound`. The
    // Prometheus client strips the `_total` counter suffix from non-counter
    // meters, so a gauge cannot be exported as `unacknowledged_outbound_total`;
    // the dashboard panel's PromQL must drop the `_total` suffix to match (a
    // dashboard-side change tracked under checkbox 2.3, not instrumentation).
    // ---------------------------------------------------------------------

    val unackMeter = PendingNewCorrelator.UNACKNOWLEDGED_OUTBOUND_METER

    test("unacknowledged_outbound is a Gauge tagged by venue and msg_type") {
        val registry = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(meterRegistry = registry)
        correlator.register("NYSE", "ord-g1") {}

        val meter = registry.find(unackMeter)
            .tag("venue", "NYSE")
            .tag("msg_type", "NEW_ORDER_SINGLE")
            .meter()
        meter shouldNotBe null
        (meter is Gauge) shouldBe true
    }

    test("unacknowledged_outbound gauge reflects the correlator in-flight count") {
        val registry = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(meterRegistry = registry)
        correlator.register("NYSE", "ord-g2a") {}
        correlator.register("NYSE", "ord-g2b") {}

        registry.find(unackMeter).tag("venue", "NYSE").gauge()!!
            .value() shouldBe 2.0
    }

    test("unacknowledged_outbound gauge drops back as awaits resolve") {
        val registry = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(meterRegistry = registry)
        correlator.register("NYSE", "ord-g3") {}
        registry.find(unackMeter).tag("venue", "NYSE").gauge()!!
            .value() shouldBe 1.0

        correlator.completePendingNew("NYSE", "ord-g3", "VEN-G3")
        runBlocking { correlator.await("NYSE", "ord-g3", java.time.Duration.ofMillis(50)) }

        registry.find(unackMeter).tag("venue", "NYSE").gauge()!!
            .value() shouldBe 0.0
    }

    test("unacknowledged_outbound is per venue") {
        val registry = SimpleMeterRegistry()
        val correlator = PendingNewCorrelator(meterRegistry = registry)
        correlator.register("NYSE", "ord-g4a") {}
        correlator.register("LSE", "ord-g4b") {}

        registry.find(unackMeter).tag("venue", "NYSE").gauge()!!.value() shouldBe 1.0
        registry.find(unackMeter).tag("venue", "LSE").gauge()!!.value() shouldBe 1.0
    }

    test("unacknowledged_outbound scrapes under a valid Prometheus gauge name") {
        // The Prometheus client strips the counter-only `_total` suffix from
        // gauges, so this gauge is exported as `unacknowledged_outbound`.
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val correlator = PendingNewCorrelator(meterRegistry = registry)
        correlator.register("NYSE", "ord-g5") {}

        val scrape = registry.scrape()
        scrape shouldContain "unacknowledged_outbound{"
        scrape shouldContain "# TYPE unacknowledged_outbound gauge"
    }
})
