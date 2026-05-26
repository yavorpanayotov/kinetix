package com.kinetix.demo.schedule

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit tests for [BootstrapStateHolder].
 *
 * State machine: NOT_STARTED → IN_PROGRESS → READY or FAILED.
 */
class BootstrapStateHolderTest : FunSpec({

    test("initial state is NOT_STARTED with no result") {
        val holder = BootstrapStateHolder()

        holder.get() shouldBe BootstrapState.NOT_STARTED
        holder.getResult() shouldBe null
    }

    test("setInProgress transitions state to IN_PROGRESS") {
        val holder = BootstrapStateHolder()
        holder.setInProgress()

        holder.get() shouldBe BootstrapState.IN_PROGRESS
        holder.getResult() shouldBe null
    }

    test("setReady transitions state to READY and stores result") {
        val holder = BootstrapStateHolder()
        val result = BootstrapResult(
            successCount = 8,
            failureCount = 0,
            failedBooks = emptyList(),
            durationMillis = 1234L,
            sodSuccessCount = 8,
            sodFailureCount = 0,
            sodFailedBooks = emptyList(),
        )

        holder.setInProgress()
        holder.setReady(result)

        holder.get() shouldBe BootstrapState.READY
        holder.getResult() shouldNotBe null
        holder.getResult()!!.successCount shouldBe 8
        holder.getResult()!!.sodSuccessCount shouldBe 8
    }

    test("setFailed transitions state to FAILED and stores result") {
        val holder = BootstrapStateHolder()
        val result = BootstrapResult(
            successCount = 3,
            failureCount = 5,
            failedBooks = listOf("book-a", "book-b"),
            durationMillis = 500L,
            sodSuccessCount = 0,
            sodFailureCount = 0,
            sodFailedBooks = emptyList(),
        )

        holder.setInProgress()
        holder.setFailed(result)

        holder.get() shouldBe BootstrapState.FAILED
        holder.getResult() shouldNotBe null
        holder.getResult()!!.failureCount shouldBe 5
    }

    test("setFailed with null result transitions state to FAILED with no result") {
        val holder = BootstrapStateHolder()
        holder.setInProgress()
        holder.setFailed(null)

        holder.get() shouldBe BootstrapState.FAILED
        holder.getResult() shouldBe null
    }
})
