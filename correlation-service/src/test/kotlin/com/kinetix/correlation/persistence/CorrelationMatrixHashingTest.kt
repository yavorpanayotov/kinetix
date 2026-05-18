package com.kinetix.correlation.persistence

import com.kinetix.correlation.persistence.ExposedCorrelationMatrixRepository.Companion.labelsHash
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pure unit tests for the labels-hash function used to key correlation
 * matrices for lookup. The hash must be invariant under label reordering
 * (since callers do not control input order) but distinct across different
 * label sets.
 */
class CorrelationMatrixHashingTest : FunSpec({

    test("hash is invariant under label reordering") {
        val ascending = labelsHash(listOf("AAPL", "GOOG", "MSFT"))
        val descending = labelsHash(listOf("MSFT", "GOOG", "AAPL"))
        val shuffled = labelsHash(listOf("GOOG", "AAPL", "MSFT"))

        ascending shouldBe descending
        ascending shouldBe shuffled
    }

    test("hash distinguishes different label sets") {
        val a = labelsHash(listOf("AAPL", "MSFT"))
        val b = labelsHash(listOf("AAPL", "GOOG"))

        a shouldNotBe b
    }

    test("hash distinguishes a label subset from a superset") {
        val pair = labelsHash(listOf("AAPL", "MSFT"))
        val triple = labelsHash(listOf("AAPL", "MSFT", "GOOG"))

        pair shouldNotBe triple
    }

    test("hash is stable for the singleton label set") {
        val first = labelsHash(listOf("AAPL"))
        val second = labelsHash(listOf("AAPL"))

        first shouldBe second
    }

    test("hash is a 32-character lower-case hex string (MD5)") {
        val hash = labelsHash(listOf("AAPL", "MSFT", "GOOG"))

        hash.length shouldBe 32
        hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    test("hash is case-sensitive on labels") {
        val lower = labelsHash(listOf("aapl", "msft"))
        val upper = labelsHash(listOf("AAPL", "MSFT"))

        lower shouldNotBe upper
    }
})
