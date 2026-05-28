package com.kinetix.correlation.sparse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A correlation matrix arriving from upstream is sparse: not every
 * pair has a published correlation, particularly for thinly-traded
 * cross-pairs. The service must handle the missing-pair case
 * deterministically — return null (so the consumer can fall back to a
 * factor model or treat the pair as zero-correlation) rather than
 * default-zero silently or throw.
 */
class SparseCorrelationMatrixTest : FunSpec({

    test("present pair returns the stored correlation") {
        val m = SparseCorrelationMatrix(
            pairs = mapOf(("AAPL" to "MSFT") to 0.65),
        )
        m.correlation("AAPL", "MSFT") shouldBe 0.65
    }

    test("symmetry: querying B,A returns the same value as A,B") {
        val m = SparseCorrelationMatrix(
            pairs = mapOf(("AAPL" to "MSFT") to 0.65),
        )
        m.correlation("MSFT", "AAPL") shouldBe 0.65
    }

    test("self-pair returns 1.0 regardless of whether it was stored") {
        val m = SparseCorrelationMatrix(pairs = emptyMap())
        m.correlation("AAPL", "AAPL") shouldBe 1.0
    }

    test("missing pair returns null (not 0.0)") {
        val m = SparseCorrelationMatrix(
            pairs = mapOf(("AAPL" to "MSFT") to 0.65),
        )
        m.correlation("AAPL", "GOOG") shouldBe null
    }

    test("hasPair reports presence without disclosing the value") {
        val m = SparseCorrelationMatrix(
            pairs = mapOf(("AAPL" to "MSFT") to 0.65),
        )
        m.hasPair("AAPL", "MSFT") shouldBe true
        m.hasPair("MSFT", "AAPL") shouldBe true
        m.hasPair("AAPL", "GOOG") shouldBe false
    }

    test("size counts unique unordered pairs") {
        val m = SparseCorrelationMatrix(
            pairs = mapOf(
                ("AAPL" to "MSFT") to 0.65,
                ("AAPL" to "GOOG") to 0.40,
            ),
        )
        m.size shouldBe 2
    }
})
