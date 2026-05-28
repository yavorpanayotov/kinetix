package com.kinetix.gateway.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Publishes a per-certificate `gateway.mtls.cert.expiry.days` gauge so
 * the platform can alert at 30 / 14 / 7 days out, well before a
 * silently-expired cert starts dropping client handshakes. The gauge
 * value is the number of (24-hour) days remaining until `notAfter`,
 * computed off the supplied [Clock] so it updates as time passes
 * without any extra ticker.
 *
 * Negative values indicate the certificate has already expired.
 */
class MtlsCertificateExpiryMetric(
    private val registry: MeterRegistry,
    private val dynamicClock: () -> Clock,
) {
    /** Convenience constructor for a fixed clock — used in tests. */
    constructor(registry: MeterRegistry, clock: Clock) : this(registry, { clock })

    /** Default-args constructor for production wiring. */
    constructor(registry: MeterRegistry) : this(registry, { Clock.systemUTC() })

    fun register(subjectCn: String, notAfter: Instant) {
        val tags = Tags.of("subject_cn", subjectCn)
        Gauge.builder(METRIC_NAME) { daysRemaining(notAfter) }
            .description("Days remaining until the mTLS certificate's notAfter")
            .tags(tags)
            .baseUnit("days")
            .register(registry)
    }

    private fun daysRemaining(notAfter: Instant): Double {
        val now = dynamicClock().instant()
        val seconds = Duration.between(now, notAfter).seconds
        return seconds / SECONDS_PER_DAY
    }

    companion object {
        const val METRIC_NAME = "gateway.mtls.cert.expiry.days"
        private const val SECONDS_PER_DAY: Double = 86_400.0
    }
}
