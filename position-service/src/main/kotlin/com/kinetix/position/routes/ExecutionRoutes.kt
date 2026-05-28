package com.kinetix.position.routes

import com.kinetix.common.model.Side
import com.kinetix.position.fix.ExecutionCostAnalysis
import com.kinetix.position.fix.ExecutionCostMetrics
import com.kinetix.position.fix.ExecutionCostRepository
import com.kinetix.position.fix.PrimeBrokerPosition
import com.kinetix.position.fix.PrimeBrokerReconciliation
import com.kinetix.position.fix.PrimeBrokerReconciliationRepository
import com.kinetix.position.fix.PrimeBrokerReconciliationService
import com.kinetix.position.fix.ReconciliationBreakStatus
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.routes.dtos.ExecutionCostResponse
import com.kinetix.position.routes.dtos.PrimeBrokerPositionDto
import com.kinetix.position.routes.dtos.PrimeBrokerStatementRequest
import com.kinetix.position.routes.dtos.ReconciliationBreakDto
import com.kinetix.position.routes.dtos.ReconciliationResponse
import com.kinetix.position.routes.dtos.RecordExecutionCostRequest
import com.kinetix.position.routes.dtos.UpdateBreakStatusRequest
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

fun Route.executionRoutes(
    executionCostRepository: ExecutionCostRepository,
    primeBrokerReconciliationRepository: PrimeBrokerReconciliationRepository,
    reconciliationService: PrimeBrokerReconciliationService,
    positionRepository: PositionRepository,
) {
    route("/api/v1/execution") {

        route("/cost/{bookId}") {
            get({
                summary = "Get execution cost analysis for all filled orders in a book"
                tags = listOf("Execution")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                }
                response {
                    code(HttpStatusCode.OK) { body<List<ExecutionCostResponse>>() }
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val analyses = executionCostRepository.findByBookId(bookId)
                call.respond(analyses.map { it.toResponse() })
            }
        }

        route("/reconciliation/{bookId}") {
            get({
                summary = "Get all prime broker reconciliations for a book"
                tags = listOf("Execution")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                }
                response {
                    code(HttpStatusCode.OK) { body<List<ReconciliationResponse>>() }
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val reconciliations = primeBrokerReconciliationRepository.findByBookId(bookId)
                call.respond(reconciliations.map { it.toResponse() })
            }

            post("/statements", {
                summary = "Upload a prime broker statement and run reconciliation"
                tags = listOf("Execution")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                    body<PrimeBrokerStatementRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<ReconciliationResponse>() }
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val request = call.receive<PrimeBrokerStatementRequest>()
                require(request.bookId == bookId) {
                    "bookId in path ($bookId) does not match bookId in body (${request.bookId})"
                }

                // Load internal positions for the book
                val internalPositions = positionRepository
                    .findByBookId(com.kinetix.common.model.BookId(bookId))
                    .associate { it.instrumentId.value to it.quantity }

                val pbPositions = request.positions.associate { dto ->
                    dto.instrumentId to PrimeBrokerPosition(
                        instrumentId = dto.instrumentId,
                        quantity = BigDecimal(dto.quantity),
                        price = BigDecimal(dto.price),
                    )
                }

                val reconciliation = reconciliationService.reconcile(
                    bookId = bookId,
                    date = request.date,
                    internalPositions = internalPositions,
                    pbPositions = pbPositions,
                    reconciledAt = Instant.now(),
                )

                primeBrokerReconciliationRepository.save(reconciliation, UUID.randomUUID().toString())
                call.respond(HttpStatusCode.Created, reconciliation.toResponse())
            }
        }

        route("/reconciliation-breaks/{reconciliationId}/{instrumentId}/status") {
            patch({
                summary = "Update the status of a reconciliation break"
                tags = listOf("Execution")
                request {
                    pathParameter<String>("reconciliationId") { description = "Reconciliation identifier" }
                    pathParameter<String>("instrumentId") { description = "Instrument identifier" }
                    body<UpdateBreakStatusRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.BadRequest) { }
                    code(HttpStatusCode.NotFound) { }
                    code(HttpStatusCode.Conflict) { }
                }
            }) {
                val reconciliationId = call.requirePathParam("reconciliationId")
                val instrumentId = call.requirePathParam("instrumentId")
                val request = call.receive<UpdateBreakStatusRequest>()
                val target = runCatching { ReconciliationBreakStatus.valueOf(request.status) }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, "Invalid status: ${request.status}. Must be one of ${ReconciliationBreakStatus.entries.map { it.name }}")
                    return@patch
                }

                // Enforce the status state machine (specs/execution.allium
                // UpdateReconciliationBreakStatus): OPEN <-> INVESTIGATING ->
                // RESOLVED; RESOLVED is terminal. Mirrors the alert escalation
                // pattern (AlertStatus.canTransitionTo, commit 4679ce0d).
                val reconciliation = primeBrokerReconciliationRepository.findById(reconciliationId)
                if (reconciliation == null) {
                    call.respond(HttpStatusCode.NotFound, "Reconciliation not found: $reconciliationId")
                    return@patch
                }
                val currentBreak = reconciliation.breaks.firstOrNull { it.instrumentId == instrumentId }
                if (currentBreak == null) {
                    call.respond(HttpStatusCode.NotFound, "Break not found for instrument: $instrumentId")
                    return@patch
                }
                if (!currentBreak.status.canTransitionTo(target)) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        "Illegal status transition: ${currentBreak.status} -> $target. " +
                            "Legal transitions: OPEN <-> INVESTIGATING -> RESOLVED (terminal).",
                    )
                    return@patch
                }

                primeBrokerReconciliationRepository.updateBreakStatus(reconciliationId, instrumentId, target)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    // Demo/seed seam — see RecordExecutionCostRequest. Lives under the existing
    // /api/v1/internal/ namespace (alongside /api/v1/internal/position and
    // /api/v1/internal/trades) so demo-orchestrator can seed the Execution
    // Cost subtab; it is not part of the public trading contract.
    route("/api/v1/internal/execution/cost/{bookId}") {
        post({
            summary = "Record a synthetic execution cost analysis (demo seed)"
            tags = listOf("Execution", "Internal")
            request {
                pathParameter<String>("bookId") { description = "Book identifier" }
                body<RecordExecutionCostRequest>()
            }
            response {
                code(HttpStatusCode.Created) { body<ExecutionCostResponse>() }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val request = call.receive<RecordExecutionCostRequest>()
            val side = runCatching { Side.valueOf(request.side) }.getOrElse {
                throw IllegalArgumentException(
                    "Invalid side: ${request.side}. Must be one of ${Side.entries.map { it.name }}",
                )
            }
            val analysis = ExecutionCostAnalysis(
                orderId = request.orderId,
                bookId = bookId,
                instrumentId = request.instrumentId,
                completedAt = Instant.parse(request.completedAt),
                arrivalPrice = BigDecimal(request.arrivalPrice),
                averageFillPrice = BigDecimal(request.averageFillPrice),
                side = side,
                totalQty = BigDecimal(request.totalQty),
                metrics = ExecutionCostMetrics(
                    slippageBps = BigDecimal(request.slippageBps),
                    marketImpactBps = request.marketImpactBps?.let { BigDecimal(it) },
                    timingCostBps = request.timingCostBps?.let { BigDecimal(it) },
                    totalCostBps = BigDecimal(request.totalCostBps),
                ),
            )
            executionCostRepository.save(analysis)
            call.respond(HttpStatusCode.Created, analysis.toResponse())
        }
    }
}

// --- Mappers ---

private fun ExecutionCostAnalysis.toResponse() = ExecutionCostResponse(
    orderId = orderId,
    bookId = bookId,
    instrumentId = instrumentId,
    completedAt = completedAt.toString(),
    arrivalPrice = arrivalPrice.toPlainString(),
    averageFillPrice = averageFillPrice.toPlainString(),
    side = side.name,
    totalQty = totalQty.toPlainString(),
    slippageBps = metrics.slippageBps.toPlainString(),
    marketImpactBps = metrics.marketImpactBps?.toPlainString(),
    timingCostBps = metrics.timingCostBps?.toPlainString(),
    totalCostBps = metrics.totalCostBps.toPlainString(),
)

private fun PrimeBrokerReconciliation.toResponse() = ReconciliationResponse(
    reconciliationDate = reconciliationDate,
    bookId = bookId,
    status = status,
    totalPositions = totalPositions,
    matchedCount = matchedCount,
    breakCount = breakCount,
    breaks = breaks.map {
        ReconciliationBreakDto(
            instrumentId = it.instrumentId,
            internalQty = it.internalQty.toPlainString(),
            primeBrokerQty = it.primeBrokerQty.toPlainString(),
            breakQty = it.breakQty.toPlainString(),
            breakNotional = it.breakNotional.toPlainString(),
            severity = it.severity.name,
            status = it.status.name,
        )
    },
    reconciledAt = reconciledAt.toString(),
)
