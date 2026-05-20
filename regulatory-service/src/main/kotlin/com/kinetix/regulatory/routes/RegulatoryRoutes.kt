package com.kinetix.regulatory.routes

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.regulatory.dtos.FrtbCalculationResponse
import com.kinetix.regulatory.dtos.FrtbHistoryResponse
import com.kinetix.regulatory.dtos.RiskClassChargeDto
import com.kinetix.regulatory.model.FrtbCalculationRecord
import com.kinetix.regulatory.model.RiskClassCharge
import com.kinetix.regulatory.persistence.FrtbCalculationRepository
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.kinetix.regulatory.routes.RegulatoryRoutes")

fun Route.regulatoryRoutes(
    repository: FrtbCalculationRepository,
    client: RiskOrchestratorClient,
    auditPublisher: GovernanceAuditPublisher? = null,
) {
    route("/api/v1/regulatory/frtb/{bookId}") {
        route("/calculate") {
            post({
                summary = "Calculate FRTB for a book"
                tags = listOf("FRTB")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                }
            }) {
                val bookId = call.parameters["bookId"]
                    ?: throw IllegalArgumentException("Missing required path parameter: bookId")

                logger.info("FRTB calculation requested for book={}", bookId)
                val frtbResult = client.calculateFrtb(bookId)

                val record = FrtbCalculationRecord(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    totalSbmCharge = BigDecimal(frtbResult.totalSbmCharge),
                    grossJtd = BigDecimal(frtbResult.grossJtd),
                    hedgeBenefit = BigDecimal(frtbResult.hedgeBenefit),
                    netDrc = BigDecimal(frtbResult.netDrc),
                    exoticNotional = BigDecimal(frtbResult.exoticNotional),
                    otherNotional = BigDecimal(frtbResult.otherNotional),
                    totalRrao = BigDecimal(frtbResult.totalRrao),
                    totalCapitalCharge = BigDecimal(frtbResult.totalCapitalCharge),
                    sbmCharges = frtbResult.sbmCharges.map {
                        RiskClassCharge(
                            riskClass = it.riskClass,
                            deltaCharge = BigDecimal(it.deltaCharge),
                            vegaCharge = BigDecimal(it.vegaCharge),
                            curvatureCharge = BigDecimal(it.curvatureCharge),
                            totalCharge = BigDecimal(it.totalCharge),
                        )
                    },
                    calculatedAt = Instant.parse(frtbResult.calculatedAt),
                    storedAt = Instant.now(),
                )

                repository.save(record)
                logger.info("FRTB calculation completed for book={}, totalCapitalCharge={}", bookId, record.totalCapitalCharge)
                auditPublisher?.publish(
                    GovernanceAuditEvent(
                        eventType = AuditEventType.REPORT_GENERATED,
                        userId = "SYSTEM",
                        userRole = "SYSTEM",
                        bookId = bookId,
                        details = "FRTB",
                        correlationId = MDC.get("correlationId"),
                    )
                )
                call.respond(HttpStatusCode.Created, record.toResponse())
            }
        }

        route("/history") {
            get({
                summary = "Get FRTB calculation history"
                tags = listOf("FRTB")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                    queryParameter<String>("limit") {
                        description = "Maximum number of results"
                        required = false
                    }
                    queryParameter<String>("offset") {
                        description = "Number of results to skip"
                        required = false
                    }
                }
            }) {
                val bookId = call.parameters["bookId"]
                    ?: throw IllegalArgumentException("Missing required path parameter: bookId")
                val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.queryParameters["offset"]?.toIntOrNull() ?: 0

                val records = repository.findByBookId(bookId, limit, offset)
                call.respond(
                    FrtbHistoryResponse(
                        calculations = records.map { it.toResponse() },
                        total = records.size,
                        limit = limit,
                        offset = offset,
                    ),
                )
            }
        }

        route("/latest") {
            get({
                summary = "Get latest FRTB calculation"
                tags = listOf("FRTB")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                }
            }) {
                val bookId = call.parameters["bookId"]
                    ?: throw IllegalArgumentException("Missing required path parameter: bookId")

                val record = repository.findLatestByBookId(bookId)
                if (record != null) {
                    call.respond(record.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun FrtbCalculationRecord.toResponse() = FrtbCalculationResponse(
    id = id,
    bookId = bookId,
    sbmCharges = sbmCharges.map {
        RiskClassChargeDto(
            riskClass = it.riskClass,
            deltaCharge = "%.2f".format(it.deltaCharge),
            vegaCharge = "%.2f".format(it.vegaCharge),
            curvatureCharge = "%.2f".format(it.curvatureCharge),
            totalCharge = "%.2f".format(it.totalCharge),
            tenorCharges = it.tenorCharges?.map { tc ->
                com.kinetix.regulatory.dtos.TenorChargeDto(
                    tenorLabel = tc.tenorLabel,
                    sensitivity = "%.2f".format(tc.sensitivity),
                    riskWeight = "%.4f".format(tc.riskWeight),
                    weightedSensitivity = "%.2f".format(tc.weightedSensitivity),
                )
            },
        )
    },
    totalSbmCharge = "%.2f".format(totalSbmCharge),
    grossJtd = "%.2f".format(grossJtd),
    hedgeBenefit = "%.2f".format(hedgeBenefit),
    netDrc = "%.2f".format(netDrc),
    exoticNotional = "%.2f".format(exoticNotional),
    otherNotional = "%.2f".format(otherNotional),
    totalRrao = "%.2f".format(totalRrao),
    totalCapitalCharge = "%.2f".format(totalCapitalCharge),
    calculatedAt = calculatedAt.toString(),
    storedAt = storedAt.toString(),
)
