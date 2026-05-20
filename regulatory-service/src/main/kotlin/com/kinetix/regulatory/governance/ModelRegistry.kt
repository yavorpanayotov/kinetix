package com.kinetix.regulatory.governance

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import org.slf4j.MDC
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// TODO(REG_D-03): Introduce a RegimeModelConfig entity that maps a named market regime
// (e.g. "HIGH_VOL", "STRESS", "NORMAL") to the approved ModelVersion that should be used
// for that regime. Requires a new RegimeModelConfig domain class, repository, and routes
// (POST to create, GET to list, PATCH to activate). The risk-orchestrator can then query
// the active regime and select the correct model version for VaR calculation automatically.
class ModelRegistry(
    private val repository: ModelVersionRepository,
    private val auditPublisher: GovernanceAuditPublisher? = null,
) {

    private val allowedTransitions = mapOf(
        ModelVersionStatus.DRAFT to setOf(ModelVersionStatus.VALIDATED),
        ModelVersionStatus.VALIDATED to setOf(ModelVersionStatus.APPROVED),
        ModelVersionStatus.APPROVED to setOf(ModelVersionStatus.RETIRED),
        ModelVersionStatus.RETIRED to emptySet(),
    )

    suspend fun register(
        modelName: String,
        version: String,
        parameters: String,
        registeredBy: String,
        modelTier: String? = null,
        validationReportUrl: String? = null,
        knownLimitations: String? = null,
        approvedUseCases: String? = null,
        nextValidationDate: java.time.LocalDate? = null,
    ): ModelVersion {
        val modelVersion = ModelVersion(
            id = UUID.randomUUID().toString(),
            modelName = modelName,
            version = version,
            status = ModelVersionStatus.DRAFT,
            parameters = parameters,
            registeredBy = registeredBy,
            approvedBy = null,
            approvedAt = null,
            createdAt = Instant.now(),
            modelTier = modelTier,
            validationReportUrl = validationReportUrl,
            knownLimitations = knownLimitations,
            approvedUseCases = approvedUseCases,
            nextValidationDate = nextValidationDate,
        )
        repository.save(modelVersion)
        return modelVersion
    }

    suspend fun listAll(): List<ModelVersion> = repository.findAll()

    suspend fun findById(id: String): ModelVersion? = repository.findById(id)

    suspend fun transitionStatus(
        id: String,
        targetStatus: ModelVersionStatus,
        approvedBy: String?,
    ): ModelVersion {
        val model = repository.findById(id)
            ?: throw NoSuchElementException("Model version not found: $id")

        val allowed = allowedTransitions[model.status] ?: emptySet()
        if (targetStatus !in allowed) {
            throw IllegalStateException(
                "Cannot transition from ${model.status} to $targetStatus"
            )
        }

        if (targetStatus == ModelVersionStatus.APPROVED && approvedBy == model.registeredBy) {
            throw IllegalArgumentException(
                "Self-approval is not permitted: approvedBy and registeredBy cannot be the same user"
            )
        }

        val updated = model.copy(
            status = targetStatus,
            approvedBy = if (targetStatus == ModelVersionStatus.APPROVED) approvedBy else model.approvedBy,
            approvedAt = if (targetStatus == ModelVersionStatus.APPROVED) Instant.now() else model.approvedAt,
        )
        repository.save(updated)

        auditPublisher?.publish(
            GovernanceAuditEvent(
                eventType = AuditEventType.MODEL_STATUS_CHANGED,
                userId = approvedBy ?: "SYSTEM",
                userRole = if (approvedBy != null) "APPROVER" else "SYSTEM",
                modelName = model.modelName,
                details = "${model.status}->${targetStatus}",
                correlationId = MDC.get("correlationId"),
            )
        )

        return updated
    }

    /**
     * Returns all APPROVED models whose [ModelVersion.nextValidationDate] has passed
     * relative to [asOf]. These are models that are overdue for revalidation.
     *
     * Models without a [ModelVersion.nextValidationDate] are not considered stale.
     */
    suspend fun checkStaleness(asOf: LocalDate = LocalDate.now()): List<ModelVersion> =
        repository.findAll().filter { model ->
            model.status == ModelVersionStatus.APPROVED &&
                model.nextValidationDate != null &&
                model.nextValidationDate < asOf
        }
}
