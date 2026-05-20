package com.kinetix.regulatory.stress

import com.kinetix.regulatory.client.CorrelationServiceClient
import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.regulatory.audit.GovernanceAuditPublisher
import org.slf4j.MDC
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class StressScenarioService(
    private val repository: StressScenarioRepository,
    private val resultRepository: StressTestResultRepository? = null,
    private val riskOrchestratorClient: RiskOrchestratorClient? = null,
    private val auditPublisher: GovernanceAuditPublisher? = null,
    private val correlationServiceClient: CorrelationServiceClient? = null,
) {

    suspend fun create(
        name: String,
        description: String,
        shocks: String,
        createdBy: String,
        scenarioType: ScenarioType = ScenarioType.PARAMETRIC,
        category: ScenarioCategory = ScenarioCategory.INTERNAL_APPROVED,
        parentScenarioId: String? = null,
        correlationOverride: String? = null,
        liquidityStressFactors: String? = null,
        historicalPeriodId: String? = null,
        targetLoss: BigDecimal? = null,
    ): StressScenario {
        val scenario = StressScenario(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            shocks = shocks,
            status = ScenarioStatus.DRAFT,
            createdBy = createdBy,
            approvedBy = null,
            approvedAt = null,
            createdAt = Instant.now(),
            scenarioType = scenarioType,
            category = category,
            parentScenarioId = parentScenarioId,
            correlationOverride = correlationOverride,
            liquidityStressFactors = liquidityStressFactors,
            historicalPeriodId = historicalPeriodId,
            targetLoss = targetLoss,
        )
        repository.save(scenario)
        return scenario
    }

    suspend fun submitForApproval(id: String): StressScenario {
        val scenario = findOrThrow(id)
        if (scenario.status != ScenarioStatus.DRAFT) {
            throw IllegalStateException("Can only submit for approval from DRAFT status, current: ${scenario.status}")
        }
        val updated = scenario.copy(status = ScenarioStatus.PENDING_APPROVAL)
        repository.save(updated)
        return updated
    }

    suspend fun approve(id: String, approvedBy: String): StressScenario {
        val scenario = findOrThrow(id)
        if (scenario.createdBy == approvedBy) {
            throw IllegalArgumentException("Four-eyes violation: approver cannot be the same as creator")
        }
        if (scenario.status != ScenarioStatus.PENDING_APPROVAL) {
            throw IllegalStateException("Can only approve from PENDING_APPROVAL status, current: ${scenario.status}")
        }
        val updated = scenario.copy(
            status = ScenarioStatus.APPROVED,
            approvedBy = approvedBy,
            approvedAt = Instant.now(),
        )
        repository.save(updated)
        auditPublisher?.publish(
            GovernanceAuditEvent(
                eventType = AuditEventType.SCENARIO_APPROVED,
                userId = approvedBy,
                userRole = "APPROVER",
                scenarioId = id,
                details = scenario.name,
                correlationId = MDC.get("correlationId"),
            )
        )
        return updated
    }

    suspend fun retire(id: String): StressScenario {
        val scenario = findOrThrow(id)
        if (scenario.status != ScenarioStatus.APPROVED) {
            throw IllegalStateException("Can only retire from APPROVED status, current: ${scenario.status}")
        }
        val updated = scenario.copy(status = ScenarioStatus.RETIRED)
        repository.save(updated)
        auditPublisher?.publish(
            GovernanceAuditEvent(
                eventType = AuditEventType.SCENARIO_RETIRED,
                userId = "SYSTEM",
                userRole = "SYSTEM",
                scenarioId = id,
                details = scenario.name,
                correlationId = MDC.get("correlationId"),
            )
        )
        return updated
    }

    suspend fun update(
        id: String,
        shocks: String? = null,
        correlationOverride: String? = null,
        liquidityStressFactors: String? = null,
    ): StressScenario {
        val scenario = findOrThrow(id)
        if (scenario.status == ScenarioStatus.RETIRED) {
            throw IllegalStateException("Cannot update a RETIRED scenario")
        }

        val resetStatus = scenario.status == ScenarioStatus.APPROVED ||
                          scenario.status == ScenarioStatus.PENDING_APPROVAL

        val updated = scenario.copy(
            version = scenario.version + 1,
            shocks = shocks ?: scenario.shocks,
            correlationOverride = correlationOverride ?: scenario.correlationOverride,
            liquidityStressFactors = liquidityStressFactors ?: scenario.liquidityStressFactors,
            status = if (resetStatus) ScenarioStatus.DRAFT else scenario.status,
            approvedBy = if (resetStatus) null else scenario.approvedBy,
            approvedAt = if (resetStatus) null else scenario.approvedAt,
        )
        repository.save(updated)
        return updated
    }

    suspend fun listAll(): List<StressScenario> = repository.findAll()

    suspend fun listApproved(): List<StressScenario> = repository.findByStatus(ScenarioStatus.APPROVED)

    suspend fun findById(id: String): StressScenario? = repository.findById(id)

    suspend fun runScenario(scenarioId: String, bookId: String, modelVersion: String?): StressTestResult {
        val scenario = findOrThrow(scenarioId)
        if (scenario.status != ScenarioStatus.APPROVED) {
            throw IllegalStateException("Can only run APPROVED scenarios, current: ${scenario.status}")
        }

        val pnlImpact = computePnlImpact(bookId, scenario)
        val result = StressTestResult(
            id = UUID.randomUUID().toString(),
            scenarioId = scenarioId,
            bookId = bookId,
            calculatedAt = Instant.now(),
            basePv = null,
            stressedPv = null,
            pnlImpact = pnlImpact,
            varImpact = null,
            positionImpacts = null,
            modelVersion = modelVersion,
        )

        resultRepository?.save(result)
        auditPublisher?.publish(
            GovernanceAuditEvent(
                eventType = AuditEventType.STRESS_TEST_RUN,
                userId = "SYSTEM",
                userRole = "SYSTEM",
                scenarioId = scenarioId,
                bookId = bookId,
                details = "modelVersion=$modelVersion",
                correlationId = MDC.get("correlationId"),
            )
        )
        return result
    }

    private suspend fun computePnlImpact(bookId: String, scenario: StressScenario): BigDecimal {
        val client = riskOrchestratorClient ?: return BigDecimal.ZERO
        val priceShocks = parseShocks(scenario.shocks)
        val result = client.runStressTest(
            bookId = bookId,
            scenarioName = scenario.name,
            priceShocks = priceShocks,
        )
        return runCatching { BigDecimal(result.pnlImpact) }.getOrDefault(BigDecimal.ZERO)
    }

    private fun parseShocks(shocksJson: String): Map<String, Double> =
        runCatching {
            Json.parseToJsonElement(shocksJson)
                .jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.double }
        }.getOrDefault(emptyMap())

    /**
     * Creates a parametric scenario whose secondary shocks are derived from the primary shock
     * via the correlation matrix fetched from the correlation-service.
     *
     * For each asset class i, the implied shock is: corr(primary, i) * primaryShock.
     * The primary asset class retains its shock unchanged (self-correlation is 1.0).
     */
    suspend fun createCorrelatedScenario(
        name: String,
        description: String,
        primaryAssetClass: String,
        primaryShock: Double,
        assetClasses: List<String>,
        createdBy: String,
    ): StressScenario {
        require(primaryAssetClass in assetClasses) {
            "primaryAssetClass '$primaryAssetClass' must be included in assetClasses list"
        }
        val client = correlationServiceClient
            ?: throw IllegalStateException("CorrelationServiceClient not configured")
        val matrix = client.fetchLatestMatrix(assetClasses)
            ?: throw IllegalStateException("No approved correlation matrix available for asset classes: $assetClasses")

        val primaryIndex = matrix.labels.indexOf(primaryAssetClass)
        val n = matrix.labels.size
        val shocksJson = buildJsonObject {
            matrix.labels.forEachIndexed { i, label ->
                val corr = matrix.values[primaryIndex * n + i]
                put(label, corr * primaryShock)
            }
        }

        return create(
            name = name,
            description = description,
            shocks = shocksJson.toString(),
            createdBy = createdBy,
            scenarioType = ScenarioType.PARAMETRIC,
        )
    }

    /**
     * Runs a 2D parametric sweep across all combinations of two shock axes.
     *
     * For each cell in the cartesian product of [primaryRange] x [secondaryRange],
     * the combined shock is passed to the risk orchestrator and the P&L impact
     * is recorded. The result contains a flat list of cells and identifies the
     * worst (most negative) P&L impact across the entire grid.
     */
    suspend fun runParametricGrid(
        bookId: String,
        primaryAxis: String,
        primaryRange: List<Double>,
        secondaryAxis: String,
        secondaryRange: List<Double>,
    ): ParametricGridResult {
        val client = riskOrchestratorClient
            ?: throw IllegalStateException("RiskOrchestratorClient not configured")

        val cells = mutableListOf<GridCell>()
        for (primaryShock in primaryRange) {
            for (secondaryShock in secondaryRange) {
                val shocks = mapOf(primaryAxis to primaryShock, secondaryAxis to secondaryShock)
                val cellName = "grid:$primaryAxis=$primaryShock:$secondaryAxis=$secondaryShock"
                val dto = client.runStressTest(
                    bookId = bookId,
                    scenarioName = cellName,
                    priceShocks = shocks,
                )
                cells.add(
                    GridCell(
                        primaryAxis = primaryAxis,
                        primaryShock = primaryShock,
                        secondaryAxis = secondaryAxis,
                        secondaryShock = secondaryShock,
                        pnlImpact = dto.pnlImpact,
                    )
                )
            }
        }

        val worstPnlImpact = cells
            .minByOrNull { runCatching { it.pnlImpact.toDouble() }.getOrElse { Double.MAX_VALUE } }
            ?.pnlImpact

        return ParametricGridResult(
            primaryAxis = primaryAxis,
            secondaryAxis = secondaryAxis,
            cells = cells,
            worstPnlImpact = worstPnlImpact,
        )
    }

    private suspend fun findOrThrow(id: String): StressScenario =
        repository.findById(id) ?: throw NoSuchElementException("Stress scenario not found: $id")
}
