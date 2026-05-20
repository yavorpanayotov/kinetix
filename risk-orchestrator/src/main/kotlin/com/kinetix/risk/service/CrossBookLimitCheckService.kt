package com.kinetix.risk.service

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.LimitServiceClient
import com.kinetix.risk.kafka.GovernanceAuditPublisher
import com.kinetix.risk.model.CrossBookValuationResult
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.math.RoundingMode

data class CrossBookLimitCheckResult(
    val groupId: String,
    val limitLevel: String,
    val limitEntityId: String,
    val limitValue: BigDecimal,
    val aggregatedVaR: BigDecimal,
    val status: String,
    val message: String,
)

class CrossBookLimitCheckService(
    private val limitServiceClient: LimitServiceClient,
    private val warningThresholdPct: Double = 0.8,
    private val governanceAuditPublisher: GovernanceAuditPublisher? = null,
) {
    private val logger = LoggerFactory.getLogger(CrossBookLimitCheckService::class.java)

    suspend fun checkLimits(result: CrossBookValuationResult): List<CrossBookLimitCheckResult> {
        val limits = when (val response = limitServiceClient.getLimits()) {
            is ClientResponse.Success -> response.value.filter { it.active && it.limitType == "VAR" }
            else -> return emptyList()
        }

        if (limits.isEmpty()) return emptyList()

        val aggregatedVaR = BigDecimal(result.varValue).setScale(2, RoundingMode.HALF_UP)
        val groupId = result.portfolioGroupId

        // Check against limits that match the group ID at DESK, DIVISION, or FIRM level
        return limits.mapNotNull { limit ->
            if (limit.entityId != groupId && limit.entityId != "FIRM") return@mapNotNull null

            val limitValue = BigDecimal(limit.limitValue)
            if (limitValue.signum() == 0) return@mapNotNull null

            val warningThreshold = limitValue.multiply(BigDecimal.valueOf(warningThresholdPct))

            val status = when {
                aggregatedVaR > limitValue -> "BREACHED"
                aggregatedVaR >= warningThreshold -> "WARNING"
                else -> "OK"
            }

            if (status != "OK") {
                logger.warn(
                    "Cross-book VaR limit {}: group={}, aggregatedVaR={}, limit={} ({})",
                    status, groupId, aggregatedVaR.toPlainString(), limitValue.toPlainString(), limit.level,
                )
                if (status == "BREACHED") {
                    governanceAuditPublisher?.publish(
                        GovernanceAuditEvent(
                            eventType = AuditEventType.LIMIT_BREACHED,
                            userId = "SYSTEM",
                            userRole = "SYSTEM",
                            limitId = limit.id,
                            bookId = groupId,
                            details = "VaR $aggregatedVaR > ${limit.level} limit $limitValue",
                            correlationId = MDC.get("correlationId"),
                        )
                    )
                }
            }

            CrossBookLimitCheckResult(
                groupId = groupId,
                limitLevel = limit.level,
                limitEntityId = limit.entityId,
                limitValue = limitValue,
                aggregatedVaR = aggregatedVaR,
                status = status,
                message = when (status) {
                    "BREACHED" -> "Aggregated VaR $aggregatedVaR exceeds ${limit.level} limit $limitValue"
                    "WARNING" -> "Aggregated VaR $aggregatedVaR approaching ${limit.level} limit $limitValue"
                    else -> "Within limit"
                },
            )
        }
    }
}
