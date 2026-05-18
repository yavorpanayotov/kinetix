package com.kinetix.correlation.persistence

import com.kinetix.correlation.persistence.ExposedCorrelationMatrixRepository.Companion.labelsHash
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.distinct
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll

/**
 * Property-based tests for [ExposedCorrelationMatrixRepository.labelsHash].
 *
 * Where example-based tests (see [CorrelationMatrixHashingTest]) verify
 * specific cases, these verify *invariants* — properties that must hold for
 * any valid input. Catches an entire class of bug, not one instance.
 */
class CorrelationMatrixHashPropertyTest : FunSpec({

    // Deterministic seed so failures are reproducible across runs and CI.
    PropertyTesting.defaultSeed = 1_337L

    val tickerArb = Arb.stringPattern("[A-Z]{1,5}")
    val labelSetArb = Arb.list(tickerArb, range = 1..8).distinct()

    test("labelsHash is invariant under permutation of input labels") {
        checkAll(labelSetArb) { labels ->
            val canonical = labelsHash(labels)
            checkAll(20, Arb.shuffle(labels)) { permuted ->
                labelsHash(permuted) shouldBe canonical
            }
        }
    }

    test("labelsHash is deterministic — same input produces same output across calls") {
        checkAll(labelSetArb) { labels ->
            labelsHash(labels) shouldBe labelsHash(labels)
        }
    }

    test("labelsHash always returns a 32-character lowercase hex MD5 string") {
        checkAll(labelSetArb) { labels ->
            val hash = labelsHash(labels)
            hash.length shouldBe 32
            hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        }
    }

    test("labelsHash distinguishes a label set from a strict superset of it") {
        checkAll(labelSetArb, tickerArb) { labels, extra ->
            if (extra !in labels) {
                labelsHash(labels) shouldNotBe labelsHash(labels + extra)
            }
        }
    }
})
