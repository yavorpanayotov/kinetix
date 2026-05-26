package com.kinetix.demo.schedule

/**
 * Summary of a [DemoVaRBootstrapJob.runOnce] sweep over all demo books.
 *
 * @property successCount number of books for which VaR was calculated successfully.
 * @property failureCount number of books that could not be processed on this pass.
 * @property failedBooks IDs of the books that could not be processed, in order
 *     encountered. Each ID corresponds to one failure in [failureCount].
 * @property durationMillis wall-clock time in milliseconds from the start to the
 *     end of the sweep, measured via the injected [java.time.Clock].
 */
data class BootstrapResult(
    val successCount: Int,
    val failureCount: Int,
    val failedBooks: List<String>,
    val durationMillis: Long,
)
