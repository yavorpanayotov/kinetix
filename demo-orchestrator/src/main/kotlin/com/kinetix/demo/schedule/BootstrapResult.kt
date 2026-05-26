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
 * @property sodSuccessCount number of books for which an SOD baseline was captured
 *     successfully. Zero when the job was constructed without a SOD job dependency.
 * @property sodFailureCount number of books for which SOD baseline capture failed.
 * @property sodFailedBooks IDs of the books that failed SOD baseline capture, in
 *     order encountered.
 */
data class BootstrapResult(
    val successCount: Int,
    val failureCount: Int,
    val failedBooks: List<String>,
    val durationMillis: Long,
    val sodSuccessCount: Int = 0,
    val sodFailureCount: Int = 0,
    val sodFailedBooks: List<String> = emptyList(),
)
