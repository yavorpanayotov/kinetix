package com.kinetix.common.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.MDC

class CorrelationIdContextTest : FunSpec({

    afterEach { MDC.remove(CorrelationIdContext.MDC_KEY) }

    test("current returns null when MDC has no correlationId") {
        MDC.remove(CorrelationIdContext.MDC_KEY)
        CorrelationIdContext.current() shouldBe null
    }

    test("current returns the value placed in MDC") {
        MDC.put(CorrelationIdContext.MDC_KEY, "abc-123")
        CorrelationIdContext.current() shouldBe "abc-123"
    }

    test("runWithCorrelationId sets MDC for the duration of the block") {
        var captured: String? = null
        CorrelationIdContext.runWithCorrelationId("req-xyz") {
            captured = MDC.get(CorrelationIdContext.MDC_KEY)
        }
        captured shouldBe "req-xyz"
    }

    test("runWithCorrelationId restores previous MDC value after block completes") {
        MDC.put(CorrelationIdContext.MDC_KEY, "outer-id")
        CorrelationIdContext.runWithCorrelationId("inner-id") {
            MDC.get(CorrelationIdContext.MDC_KEY) shouldBe "inner-id"
        }
        MDC.get(CorrelationIdContext.MDC_KEY) shouldBe "outer-id"
    }

    test("runWithCorrelationId removes MDC key when there was no prior value") {
        MDC.remove(CorrelationIdContext.MDC_KEY)
        CorrelationIdContext.runWithCorrelationId("temp-id") {
            MDC.get(CorrelationIdContext.MDC_KEY) shouldBe "temp-id"
        }
        MDC.get(CorrelationIdContext.MDC_KEY) shouldBe null
    }

    test("runWithCorrelationId restores previous MDC value even when block throws") {
        MDC.put(CorrelationIdContext.MDC_KEY, "outer-id")
        try {
            CorrelationIdContext.runWithCorrelationId("inner-id") {
                throw RuntimeException("oops")
            }
        } catch (_: RuntimeException) {}
        MDC.get(CorrelationIdContext.MDC_KEY) shouldBe "outer-id"
    }

    test("MDC_KEY constant equals 'correlationId'") {
        CorrelationIdContext.MDC_KEY shouldBe "correlationId"
    }

    test("HEADER_NAME constant equals 'X-Correlation-ID'") {
        CorrelationIdContext.HEADER_NAME shouldBe "X-Correlation-ID"
    }

    test("GRPC_METADATA_KEY constant equals 'x-correlation-id'") {
        CorrelationIdContext.GRPC_METADATA_KEY shouldBe "x-correlation-id"
    }

    test("generate returns a non-null non-blank UUID-format string") {
        val id = CorrelationIdContext.generate()
        id shouldNotBe null
        id.isNotBlank() shouldBe true
    }
})
