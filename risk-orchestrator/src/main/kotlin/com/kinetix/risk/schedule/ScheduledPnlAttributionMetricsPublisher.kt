package com.kinetix.risk.schedule

import com.kinetix.common.model.BookId
import com.kinetix.risk.orchestrator.metrics.PnlAttributionMetrics
import com.kinetix.risk.persistence.PnlAttributionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import kotlin.coroutines.coroutineContext

/**
 * Periodically mirrors each book's latest persisted P&L attribution onto the
 * Prometheus gauges the Grafana "P&L" dashboard queries (kx-wrvc).
 *
 * The attribution rows are already computed and persisted (they back the P&L
 * tab); this job just keeps the gauge series fresh so the dashboard's
 * time-aligned panels resolve instead of reading "No data". Re-publishing on an
 * interval (rather than only when attribution is computed on demand) means the
 * series stay present for whatever data is in the table, including the
 * demo-seeded history.
 */
class ScheduledPnlAttributionMetricsPublisher(
    private val repository: PnlAttributionRepository,
    private val metrics: PnlAttributionMetrics,
    private val bookIds: suspend () -> List<BookId>,
    private val intervalMillis: Long = 60_000,
    private val lock: DistributedLock = NoOpDistributedLock(),
) {
    private val logger = LoggerFactory.getLogger(ScheduledPnlAttributionMetricsPublisher::class.java)

    suspend fun start() {
        while (coroutineContext.isActive) {
            lock.withLock("scheduled-pnl-attribution-metrics", ttlSeconds = intervalMillis / 1000) {
                publishOnce()
            }
            delay(intervalMillis)
        }
    }

    /** A single refresh pass — published gauges for every book's latest row. */
    suspend fun publishOnce() {
        val books = try {
            bookIds()
        } catch (e: Exception) {
            logger.error("Failed to fetch book list for P&L attribution metrics", e)
            return
        }
        for (bookId in books) {
            try {
                // supervisorScope so a single book's failure (e.g. a transaction
                // error) doesn't abort the whole refresh pass.
                supervisorScope {
                    val latest = repository.findLatestByBookId(bookId)
                    if (latest != null) {
                        metrics.publish(latest)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to publish P&L attribution metrics for book {}", bookId.value, e)
            }
        }
    }
}
