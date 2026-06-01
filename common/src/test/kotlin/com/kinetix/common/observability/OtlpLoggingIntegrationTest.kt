package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class OtlpLoggingIntegrationTest : FunSpec({

    val exporter = InMemoryLogRecordExporter.create()

    val sdk = OpenTelemetrySdk.builder()
        .setLoggerProvider(
            SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()
        )
        .build()

    beforeSpec {
        OpenTelemetryAppender.install(sdk)
    }

    afterSpec {
        sdk.close()
    }

    beforeEach {
        exporter.reset()
        MDC.clear()
    }

    afterEach {
        MDC.clear()
    }

    test("SLF4J log records are exported through the OpenTelemetry pipeline") {
        val logger = LoggerFactory.getLogger("com.kinetix.test.OtlpPipeline")

        logger.info("Info message for OTLP test")
        logger.warn("Warning message for OTLP test")
        logger.error("Error message for OTLP test")

        val records = exporter.finishedLogRecordItems
        records shouldHaveSize 3

        val info = records[0]
        info.bodyValue!!.asString() shouldBe "Info message for OTLP test"
        info.severity.name shouldContain "INFO"

        val warn = records[1]
        warn.bodyValue!!.asString() shouldBe "Warning message for OTLP test"
        warn.severity.name shouldContain "WARN"

        val error = records[2]
        error.bodyValue!!.asString() shouldBe "Error message for OTLP test"
        error.severity.name shouldContain "ERROR"
    }

    test("MDC context fields are exported as log record attributes so Loki can facet on them") {
        val logger = LoggerFactory.getLogger("com.kinetix.test.MdcCapture")

        MDC.put("correlationId", "corr-1234")
        MDC.put("userId", "trader-alice")
        MDC.put("endpoint", "/api/trades")
        try {
            logger.info("Booked a trade")
        } finally {
            MDC.clear()
        }

        val records = exporter.finishedLogRecordItems
        records shouldHaveSize 1

        val attributes = records[0].attributes
        attributes.get(AttributeKey.stringKey("correlationId")) shouldBe "corr-1234"
        attributes.get(AttributeKey.stringKey("userId")) shouldBe "trader-alice"
        attributes.get(AttributeKey.stringKey("endpoint")) shouldBe "/api/trades"
    }

    test("exported log records contain the logger name") {
        val loggerName = "com.kinetix.test.LoggerNameCheck"
        val logger = LoggerFactory.getLogger(loggerName)

        logger.info("Checking logger name attribute")

        val records = exporter.finishedLogRecordItems
        records shouldHaveSize 1
        records[0].instrumentationScopeInfo.name shouldBe loggerName
    }
})
