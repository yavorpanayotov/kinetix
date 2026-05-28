package com.kinetix.gateway.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The gateway terminates mutual TLS for client connections. Certificates
 * have an expiry date, and once one expires every downstream request
 * silently fails the handshake — operators only find out when the
 * pager wakes them up. Surfacing the days-until-expiry as a Micrometer
 * gauge lets the platform alert at 30 / 14 / 7 days out, well before
 * the cliff. The gauge name is `gateway.mtls.cert.expiry.days` and the
 * tag is the certificate subject CN.
 */
class MtlsCertificateExpiryMetricTest : FunSpec({

    fun fixedClock(now: Instant) = Clock.fixed(now, ZoneId.of("UTC"))

    test("registers a gauge tagged with the certificate subject CN") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val expiry = now.plusSeconds(30L * 24 * 3600)
        val metric = MtlsCertificateExpiryMetric(registry, fixedClock(now))
        metric.register(subjectCn = "gateway.kinetixrisk.ai", notAfter = expiry)
        val gauge = registry.find("gateway.mtls.cert.expiry.days")
            .tag("subject_cn", "gateway.kinetixrisk.ai")
            .gauge()
        gauge shouldBe gauge!!
        gauge.value() shouldBe 30.0
    }

    test("the gauge value updates as time passes (clock-driven)") {
        val registry = SimpleMeterRegistry()
        val now1 = Instant.parse("2026-05-28T00:00:00Z")
        val expiry = now1.plusSeconds(30L * 24 * 3600)

        // First reading at t0: 30 days remaining.
        val clock = java.util.concurrent.atomic.AtomicReference(fixedClock(now1))
        val metric = MtlsCertificateExpiryMetric(registry, dynamicClock = { clock.get() })
        metric.register(subjectCn = "api.kinetixrisk.ai", notAfter = expiry)

        // Advance the clock by 10 days.
        clock.set(fixedClock(now1.plusSeconds(10L * 24 * 3600)))
        val gauge = registry.find("gateway.mtls.cert.expiry.days")
            .tag("subject_cn", "api.kinetixrisk.ai")
            .gauge()
        gauge!!.value() shouldBe 20.0
    }

    test("an expired certificate reports a negative days-remaining (operators page on <= 0)") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-28T00:00:00Z")
        // Expired 3 days ago.
        val expiry = now.minusSeconds(3L * 24 * 3600)
        val metric = MtlsCertificateExpiryMetric(registry, fixedClock(now))
        metric.register("expired.kinetixrisk.ai", expiry)
        val gauge = registry.find("gateway.mtls.cert.expiry.days")
            .tag("subject_cn", "expired.kinetixrisk.ai")
            .gauge()
        gauge!!.value() shouldBe -3.0
    }

    test("registering multiple certificates exposes one gauge per subject_cn tag") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val metric = MtlsCertificateExpiryMetric(registry, fixedClock(now))
        metric.register("gateway.kinetixrisk.ai", now.plusSeconds(30L * 24 * 3600))
        metric.register("api.kinetixrisk.ai", now.plusSeconds(60L * 24 * 3600))

        val gauges = registry.find("gateway.mtls.cert.expiry.days").gauges()
        gauges.size shouldBe 2
    }

    test("gauges are positive (well above 0) for a freshly-issued cert (~365 days)") {
        val registry = SimpleMeterRegistry()
        val now = Instant.parse("2026-05-28T00:00:00Z")
        val expiry = now.plusSeconds(365L * 24 * 3600)
        val metric = MtlsCertificateExpiryMetric(registry, fixedClock(now))
        metric.register("fresh.kinetixrisk.ai", expiry)
        val gauge = registry.find("gateway.mtls.cert.expiry.days")
            .tag("subject_cn", "fresh.kinetixrisk.ai").gauge()
        gauge!!.value() shouldBeGreaterThan 360.0
    }
})
