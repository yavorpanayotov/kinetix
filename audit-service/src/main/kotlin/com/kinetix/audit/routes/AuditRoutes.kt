package com.kinetix.audit.routes

import com.kinetix.audit.dtos.toResponse
import com.kinetix.audit.metrics.AuditMetrics
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.audit.persistence.AuditHasher
import com.kinetix.audit.persistence.ChainVerificationResult
import com.kinetix.audit.persistence.VerificationCheckpoint
import com.kinetix.audit.persistence.VerificationCheckpointRepository
import com.kinetix.audit.routes.dtos.GapDetectionResponse
import com.kinetix.audit.routes.dtos.SequenceGap
import com.kinetix.common.audit.AuditEventType
import io.github.smiley4.ktoropenapi.get
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

private val logger = LoggerFactory.getLogger("com.kinetix.audit.routes.AuditRoutes")

private const val DEFAULT_PAGE_LIMIT = 1000
private const val MAX_PAGE_LIMIT = 10000
private const val VERIFY_BATCH_SIZE = 10000

fun Route.auditRoutes(
    repository: AuditEventRepository,
    checkpointRepository: VerificationCheckpointRepository? = null,
    metrics: AuditMetrics? = null,
) {
    route("/api/v1/audit") {
        get("/events", {
            summary = "List audit events"
            tags = listOf("Audit")
            request {
                queryParameter<String>("bookId") {
                    description = "Filter by book ID"
                    required = false
                }
                queryParameter<String>("tradeId") {
                    description = "Filter by trade ID"
                    required = false
                }
                queryParameter<String>("eventType") {
                    description = "Filter by event type (one of the AuditEventType values)"
                    required = false
                }
                queryParameter<String>("from") {
                    description = "Only events received at or after this ISO-8601 instant (inclusive)"
                    required = false
                }
                queryParameter<String>("to") {
                    description = "Only events received at or before this ISO-8601 instant (inclusive)"
                    required = false
                }
                queryParameter<Long>("afterId") {
                    description = "Return events with id greater than this value (cursor-based pagination)"
                    required = false
                }
                queryParameter<Int>("limit") {
                    description = "Maximum number of events to return (default $DEFAULT_PAGE_LIMIT, max $MAX_PAGE_LIMIT)"
                    required = false
                }
            }
        }) {
            val bookId = call.request.queryParameters["bookId"]
            val tradeId = call.request.queryParameters["tradeId"]
            val eventType = call.request.queryParameters["eventType"]?.also { value ->
                require(AuditEventType.entries.any { it.name == value }) {
                    "Unknown eventType '$value'"
                }
            }
            val from = call.request.queryParameters["from"].parseInstantOrReject("from")
            val to = call.request.queryParameters["to"].parseInstantOrReject("to")
            val afterId = call.request.queryParameters["afterId"]?.toLongOrNull() ?: 0L
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT)
                .coerceIn(1, MAX_PAGE_LIMIT)

            val events = repository.findPage(
                afterId = afterId,
                limit = limit,
                bookId = bookId,
                tradeId = tradeId,
                eventType = eventType,
                from = from,
                to = to,
            )
            call.respond(events.map { it.toResponse() })
        }

        get("/verify", {
            summary = "Verify audit chain integrity"
            tags = listOf("Audit")
        }) {
            logger.info("Starting audit chain verification")

            // Resume from the last saved checkpoint if available, scanning only new events.
            val latestCheckpoint = checkpointRepository?.findLatest()
            var lastId = latestCheckpoint?.lastEventId ?: 0L
            var previousHash: String? = latestCheckpoint?.lastHash
            var totalVerified = latestCheckpoint?.eventCount ?: 0L
            var valid = true

            do {
                val batch = repository.findPage(afterId = lastId, limit = VERIFY_BATCH_SIZE)
                if (batch.isEmpty()) break

                val result = AuditHasher.verifyChainIncremental(batch, previousHash)
                if (!result.valid) {
                    valid = false
                    break
                }

                previousHash = result.lastHash
                lastId = batch.last().id
                totalVerified += result.eventsVerified
            } while (batch.size == VERIFY_BATCH_SIZE)

            // Persist a checkpoint only on success so the next call can resume from here.
            if (valid && checkpointRepository != null && previousHash != null) {
                checkpointRepository.save(
                    VerificationCheckpoint(
                        id = 0L, // auto-assigned by database
                        lastEventId = lastId,
                        lastHash = previousHash,
                        eventCount = totalVerified,
                        verifiedAt = Instant.now(),
                    )
                )
            }

            val result = ChainVerificationResult(valid = valid, eventCount = totalVerified.toInt())
            metrics?.recordVerification(passed = result.valid)
            logger.info("Audit chain verification complete: valid={}, eventCount={}", result.valid, result.eventCount)
            call.respond(result)
        }

        get("/gaps", {
            summary = "Detect gaps in audit event sequence numbers"
            tags = listOf("Audit")
        }) {
            logger.info("Starting audit sequence gap detection")

            val events = repository.findAll()
            val sequenced = events.mapNotNull { it.sequenceNumber }.sorted()

            val gaps = mutableListOf<SequenceGap>()
            for (i in 0 until sequenced.size - 1) {
                val current = sequenced[i]
                val next = sequenced[i + 1]
                if (next > current + 1) {
                    gaps.add(SequenceGap(
                        afterSequence = current,
                        beforeSequence = next,
                        missingCount = next - current - 1,
                    ))
                }
            }

            val response = GapDetectionResponse(gapCount = gaps.size, gaps = gaps)
            logger.info("Gap detection complete: {} gaps found", response.gapCount)
            call.respond(response)
        }
    }
}

/**
 * Parses an optional ISO-8601 instant query parameter. Returns `null` when absent,
 * and throws [IllegalArgumentException] (mapped to HTTP 400 by StatusPages) when the
 * value is present but unparseable.
 */
private fun String?.parseInstantOrReject(paramName: String): Instant? {
    if (this == null) return null
    return try {
        Instant.parse(this)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid '$paramName' timestamp: '$this' is not a valid ISO-8601 instant", e)
    }
}
