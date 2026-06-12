package com.kinetix.risk.service

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.risk.client.InstrumentServiceClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.risk.kafka.GovernanceAuditPublisher
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private data class AggregateGreeks(val delta: Double, val gamma: Double, val vega: Double, val theta: Double, val rho: Double)

class VaRCalculationService(
    private val positionProvider: PositionProvider,
    private val riskEngineClient: RiskEngineClient,
    private val resultPublisher: RiskResultPublisher,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    private val dependenciesDiscoverer: DependenciesDiscoverer? = null,
    private val marketDataFetcher: MarketDataFetcher? = null,
    private val jobRecorder: ValuationJobRecorder = NoOpValuationJobRecorder(),
    private val positionDependencyGrouper: PositionDependencyGrouper = PositionDependencyGrouper(),
    private val runManifestCapture: RunManifestCapture? = null,
    private val instrumentServiceClient: InstrumentServiceClient? = null,
    private val activeRegimeProvider: (() -> RegimeState?)? = null,
    private val governanceAuditPublisher: GovernanceAuditPublisher? = null,
) {
    private val logger = LoggerFactory.getLogger(VaRCalculationService::class.java)

    suspend fun calculateVaR(
        request: VaRCalculationRequest,
        triggerType: TriggerType = TriggerType.ON_DEMAND,
        correlationId: String? = null,
        runLabel: RunLabel? = null,
        triggeredBy: String = "SYSTEM",
    ): ValuationResult? {
        val jobId = UUID.randomUUID()
        val jobStartedAt = Instant.now()
        val valuationDate = LocalDate.now(ZoneOffset.UTC)
        val phases = mutableListOf<JobPhase>()
        var jobError: String? = null

        saveJobSafely(
            ValuationJob(
                jobId = jobId,
                bookId = request.bookId.value,
                triggerType = triggerType,
                status = RunStatus.RUNNING,
                startedAt = jobStartedAt,
                valuationDate = valuationDate,
                calculationType = request.calculationType.name,
                confidenceLevel = request.confidenceLevel.name,
                timeHorizonDays = request.timeHorizonDays,
                runLabel = runLabel,
                triggeredBy = triggeredBy,
                correlationId = correlationId,
            )
        )

        try {
            // Phase 1: Fetch positions
            updateCurrentPhaseSafely(jobId, JobPhaseName.FETCH_POSITIONS)
            val fetchPosStart = Instant.now()
            val positions = positionProvider.getPositions(request.bookId)
            val fetchPosDuration = java.time.Duration.between(fetchPosStart, Instant.now()).toMillis()
            phases.add(
                JobPhase(
                    name = JobPhaseName.FETCH_POSITIONS,
                    status = RunStatus.COMPLETED,
                    startedAt = fetchPosStart,
                    completedAt = Instant.now(),
                    durationMs = fetchPosDuration,
                    details = mapOf(
                        "positionCount" to positions.size,
                        "positions" to Json.encodeToString(positions.map { pos ->
                            buildMap {
                                put("instrumentId", pos.instrumentId.value)
                                put("assetClass", pos.assetClass.name)
                                put("quantity", pos.quantity.toPlainString())
                                put("averageCost", "${pos.averageCost.amount} ${pos.averageCost.currency}")
                                put("marketPrice", "${pos.marketPrice.amount} ${pos.marketPrice.currency}")
                                put("marketValue", "${pos.marketValue.amount} ${pos.marketValue.currency}")
                                put("unrealizedPnl", "${pos.unrealizedPnl.amount} ${pos.unrealizedPnl.currency}")
                            }
                        }),
                    ),
                )
            )

            if (positions.isEmpty()) {
                logger.info("No positions found for portfolio {}, skipping VaR calculation", request.bookId.value)
                return null
            }

            // Fetch instrument enrichment data (graceful degradation)
            val instrumentMap: Map<String, InstrumentDto> = try {
                instrumentServiceClient?.getInstruments(positions.map { it.instrumentId }) ?: emptyMap()
            } catch (e: Exception) {
                logger.warn("Failed to fetch instruments, proceeding without enrichment", e)
                emptyMap()
            }

            logger.info(
                "Calculating {} VaR for portfolio {} with {} positions ({} instruments enriched)",
                request.calculationType, request.bookId.value, positions.size, instrumentMap.size,
            )

            // Phase 2: Discover dependencies
            updateCurrentPhaseSafely(jobId, JobPhaseName.DISCOVER_DEPENDENCIES)
            val discoverStart = Instant.now()
            val dependencies = try {
                dependenciesDiscoverer?.discover(
                    positions,
                    request.calculationType.name,
                    request.confidenceLevel.name,
                ) ?: emptyList()
            } catch (e: Exception) {
                logger.warn("Market data dependency discovery failed, proceeding with defaults", e)
                emptyList()
            }
            val discoverDuration = java.time.Duration.between(discoverStart, Instant.now()).toMillis()
            val dataTypes = dependencies.mapNotNull { it.dataType }.distinct().joinToString(",")
            phases.add(
                JobPhase(
                    name = JobPhaseName.DISCOVER_DEPENDENCIES,
                    status = RunStatus.COMPLETED,
                    startedAt = discoverStart,
                    completedAt = Instant.now(),
                    durationMs = discoverDuration,
                    details = mapOf(
                        "dependencyCount" to dependencies.size,
                        "dataTypes" to dataTypes,
                        "dependencies" to Json.encodeToString(dependencies.map { dep ->
                            buildMap {
                                put("instrumentId", dep.instrumentId)
                                put("dataType", dep.dataType)
                                put("assetClass", dep.assetClass)
                                if (dep.parameters.isNotEmpty()) {
                                    put("parameters", dep.parameters.entries.joinToString(", ") { "${it.key}=${it.value}" })
                                }
                            }
                        }),
                    ),
                )
            )

            if (dependencies.isNotEmpty()) {
                val grouped = positionDependencyGrouper.group(positions, dependencies)
                if (grouped.isNotEmpty()) {
                    val groupedJson = Json.encodeToString(grouped.mapValues { (_, deps) ->
                        deps.map { dep ->
                            buildMap {
                                put("instrumentId", dep.instrumentId)
                                put("dataType", dep.dataType)
                                put("assetClass", dep.assetClass)
                                if (dep.parameters.isNotEmpty()) {
                                    put("parameters", dep.parameters.entries.joinToString(", ") { "${it.key}=${it.value}" })
                                }
                            }
                        }
                    })
                    phases[0] = phases[0].copy(details = phases[0].details + ("dependenciesByPosition" to groupedJson))
                }
            }

            // Phase 3: Fetch market data
            updateCurrentPhaseSafely(jobId, JobPhaseName.FETCH_MARKET_DATA)
            val fetchMdStart = Instant.now()
            val fetchResults = try {
                if (dependencies.isNotEmpty() && marketDataFetcher != null) {
                    marketDataFetcher.fetch(dependencies, correlationId)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                logger.warn("Market data fetch failed, proceeding with defaults", e)
                emptyList<FetchResult>()
            }
            val marketData = fetchResults.filterIsInstance<FetchSuccess>().map { it.value }
            val marketDataComplete = fetchResults.isEmpty() || fetchResults.all { it is FetchSuccess }
            val fetchMdDuration = java.time.Duration.between(fetchMdStart, Instant.now()).toMillis()
            phases.add(
                JobPhase(
                    name = JobPhaseName.FETCH_MARKET_DATA,
                    status = RunStatus.COMPLETED,
                    startedAt = fetchMdStart,
                    completedAt = Instant.now(),
                    durationMs = fetchMdDuration,
                    details = mapOf(
                        "requested" to dependencies.size,
                        "fetched" to marketData.size,
                        "marketDataItems" to buildJsonArray {
                            dependencies.forEach { dep ->
                                val result = fetchResults.find { it.dependency == dep }
                                val fetched = (result as? FetchSuccess)?.value
                                val failure = result as? FetchFailure
                                addJsonObject {
                                    put("instrumentId", dep.instrumentId)
                                    put("dataType", dep.dataType)
                                    put("assetClass", dep.assetClass)
                                    put("status", if (fetched != null) "FETCHED" else "MISSING")
                                    if (fetched is ScalarMarketData) put("value", fetched.value.toString())
                                    if (fetched is CurveMarketData) put("points", fetched.points.size.toString())
                                    if (fetched is TimeSeriesMarketData) put("points", fetched.points.size.toString())
                                    if (fetched is MatrixMarketData) put("rows", fetched.rows.size.toString())
                                    if (failure != null) {
                                        putJsonObject("issue") {
                                            put("reason", failure.reason)
                                            failure.url?.let { put("url", it) }
                                            failure.httpStatus?.let { put("httpStatus", it.toString()) }
                                            failure.errorMessage?.let { put("errorMessage", it) }
                                            put("service", failure.service)
                                            put("timestamp", failure.timestamp.toString())
                                            put("durationMs", failure.durationMs.toString())
                                        }
                                    }
                                }
                            }
                        }.toString(),
                    ),
                )
            )

            // Step 3b: Apply regime parameter overrides (non-ON_DEMAND triggers only, confirmed regimes only)
            val regimeOverriddenRequest = applyRegimeOverride(request, triggerType)
            val regimeOverrideApplied = regimeOverriddenRequest !== request

            // Step 3c: Generate MC seed if needed and capture run manifest inputs
            val effectiveRequest = if (regimeOverriddenRequest.calculationType == CalculationType.MONTE_CARLO && regimeOverriddenRequest.monteCarloSeed == 0L) {
                regimeOverriddenRequest.copy(monteCarloSeed = System.nanoTime())
            } else {
                regimeOverriddenRequest
            }

            var manifestId: UUID? = null
            if (runManifestCapture != null) {
                try {
                    val manifest = runManifestCapture.captureInputs(
                        jobId = jobId,
                        request = effectiveRequest,
                        positions = positions,
                        fetchResults = fetchResults,
                        valuationDate = valuationDate,
                    )
                    manifestId = manifest.manifestId
                } catch (e: Exception) {
                    logger.warn("Failed to capture run manifest inputs for job {}", jobId, e)
                }
            }

            // Phase 4: Valuation (+ Greeks if requested)
            updateCurrentPhaseSafely(jobId, JobPhaseName.VALUATION)
            val calcStart = Instant.now()
            val timer = meterRegistry.timer("var.calculation.duration")
            val sample = io.micrometer.core.instrument.Timer.start(meterRegistry)

            val result = riskEngineClient.valuate(effectiveRequest, positions, marketData, instrumentMap)

            sample.stop(timer)
            meterRegistry.counter(
                "var.calculation.count",
                "calculationType", request.calculationType.name,
            ).increment()

            // Step 4b: Finalise manifest with outputs and real model version
            if (manifestId != null && runManifestCapture != null) {
                try {
                    runManifestCapture.finaliseOutputs(
                        manifestId = manifestId,
                        modelVersion = result.modelVersion ?: "",
                        varValue = result.varValue,
                        expectedShortfall = result.expectedShortfall,
                        componentBreakdown = result.componentBreakdown,
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to finalise run manifest {} for job {}", manifestId, jobId, e)
                }
            }

            val calcDuration = java.time.Duration.between(calcStart, Instant.now()).toMillis()

            val positionRiskList = computePositionRisk(positions, result, instrumentMap)
            val positionBreakdown = serializePositionBreakdown(positionRiskList, result.greeks)

            val calcDetails = buildMap<String, Any> {
                put("varValue", result.varValue ?: 0.0)
                put("expectedShortfall", result.expectedShortfall ?: 0.0)
                put("positionBreakdown", positionBreakdown)
                result.greeks?.let { greeks ->
                    put("greeksAssetClassCount", greeks.assetClassGreeks.size)
                    put("theta", greeks.theta)
                    put("rho", greeks.rho)
                }
                result.pvValue?.let { put("pvValue", it) }
            }

            phases.add(
                JobPhase(
                    name = JobPhaseName.VALUATION,
                    status = RunStatus.COMPLETED,
                    startedAt = calcStart,
                    completedAt = Instant.now(),
                    durationMs = calcDuration,
                    details = calcDetails,
                )
            )

            // Phase 5: Publish result
            updateCurrentPhaseSafely(jobId, JobPhaseName.PUBLISH_RESULT)
            val publishStart = Instant.now()
            resultPublisher.publish(result, correlationId)
            val publishDuration = java.time.Duration.between(publishStart, Instant.now()).toMillis()
            phases.add(
                JobPhase(
                    name = JobPhaseName.PUBLISH_RESULT,
                    status = RunStatus.COMPLETED,
                    startedAt = publishStart,
                    completedAt = Instant.now(),
                    durationMs = publishDuration,
                    details = mapOf("topic" to "risk.results"),
                )
            )

            val enrichedResult = result.copy(positionRisk = positionRiskList, jobId = jobId, valuationDate = valuationDate, marketDataComplete = marketDataComplete)

            logger.info(
                "VaR calculation complete for portfolio {}: VaR={}, ES={}",
                request.bookId.value, result.varValue, result.expectedShortfall,
            )

            val aggregateGreeks = result.greeks?.let { greeks ->
                var totalDelta = 0.0
                var totalGamma = 0.0
                var totalVega = 0.0
                for (ac in greeks.assetClassGreeks) {
                    totalDelta += ac.delta
                    totalGamma += ac.gamma
                    totalVega += ac.vega
                }
                AggregateGreeks(totalDelta, totalGamma, totalVega, greeks.theta, greeks.rho)
            }

            val jobCompletedAt = Instant.now()
            val job = ValuationJob(
                jobId = jobId,
                bookId = request.bookId.value,
                triggerType = triggerType,
                status = RunStatus.COMPLETED,
                startedAt = jobStartedAt,
                valuationDate = valuationDate,
                completedAt = jobCompletedAt,
                durationMs = java.time.Duration.between(jobStartedAt, jobCompletedAt).toMillis(),
                calculationType = effectiveRequest.calculationType.name,
                confidenceLevel = effectiveRequest.confidenceLevel.name,
                timeHorizonDays = effectiveRequest.timeHorizonDays,
                varValue = result.varValue,
                expectedShortfall = result.expectedShortfall,
                pvValue = result.pvValue,
                delta = aggregateGreeks?.delta,
                gamma = aggregateGreeks?.gamma,
                vega = aggregateGreeks?.vega,
                theta = aggregateGreeks?.theta,
                rho = aggregateGreeks?.rho,
                positionRiskSnapshot = positionRiskList,
                componentBreakdownSnapshot = result.componentBreakdown,
                computedOutputsSnapshot = result.computedOutputs,
                assetClassGreeksSnapshot = result.greeks?.assetClassGreeks ?: emptyList(),
                phases = phases,
                manifestId = manifestId,
                runLabel = runLabel,
                triggeredBy = triggeredBy,
                // Always record the originally requested parameters so the audit trail is
                // unambiguous regardless of whether a regime override was active.  When
                // no override applies, requestedX == calculationType/confidenceLevel/
                // timeHorizonDays (i.e. effective == requested); the non-null presence
                // signals that the record is complete, not that an override occurred.
                requestedCalculationType = request.calculationType.name,
                requestedConfidenceLevel = request.confidenceLevel.name,
                requestedTimeHorizonDays = request.timeHorizonDays,
                correlationId = correlationId,
            )
            updateJobSafely(job)

            governanceAuditPublisher?.publish(
                GovernanceAuditEvent(
                    eventType = AuditEventType.RISK_CALCULATION_COMPLETED,
                    userId = triggeredBy,
                    userRole = "SYSTEM",
                    bookId = request.bookId.value,
                    details = "${effectiveRequest.calculationType}/${effectiveRequest.confidenceLevel}",
                    correlationId = MDC.get("correlationId"),
                )
            )

            return enrichedResult
        } catch (e: Exception) {
            // Prometheus failure signal (exported as `risk_calculation_failed_total`)
            // — drives the RiskCalculationFailureRate alert; emitted alongside the
            // RISK_CALCULATION_FAILED governance audit event below.
            meterRegistry.counter(
                "risk_calculation_failed",
                "book_id", request.bookId.value,
                "calculation_type", request.calculationType.name,
            ).increment()

            jobError = e.message ?: e.javaClass.simpleName
            val jobCompletedAt = Instant.now()
            val job = ValuationJob(
                jobId = jobId,
                bookId = request.bookId.value,
                triggerType = triggerType,
                status = RunStatus.FAILED,
                startedAt = jobStartedAt,
                valuationDate = valuationDate,
                completedAt = jobCompletedAt,
                durationMs = java.time.Duration.between(jobStartedAt, jobCompletedAt).toMillis(),
                calculationType = request.calculationType.name,
                confidenceLevel = request.confidenceLevel.name,
                timeHorizonDays = request.timeHorizonDays,
                phases = phases,
                error = jobError,
                runLabel = runLabel,
                triggeredBy = triggeredBy,
                correlationId = correlationId,
            )
            updateJobSafely(job)

            governanceAuditPublisher?.publish(
                GovernanceAuditEvent(
                    eventType = AuditEventType.RISK_CALCULATION_FAILED,
                    userId = triggeredBy,
                    userRole = "SYSTEM",
                    bookId = request.bookId.value,
                    details = jobError,
                    correlationId = MDC.get("correlationId"),
                )
            )

            throw e
        }
    }

    private fun applyRegimeOverride(request: VaRCalculationRequest, triggerType: TriggerType): VaRCalculationRequest {
        if (triggerType == TriggerType.ON_DEMAND) return request
        val regime = activeRegimeProvider?.invoke() ?: return request
        if (!regime.isConfirmed) return request
        if (regime.regime == MarketRegime.NORMAL) return request
        val params = regime.varParameters
        return request.copy(
            calculationType = params.calculationType,
            confidenceLevel = params.confidenceLevel,
            timeHorizonDays = params.timeHorizonDays,
            numSimulations = params.numSimulations ?: request.numSimulations,
        )
    }

    internal fun computePositionRisk(
        positions: List<com.kinetix.common.model.Position>,
        result: ValuationResult,
        instrumentMap: Map<String, InstrumentDto> = emptyMap(),
    ): List<PositionRisk> {
        val breakdownByAssetClass = result.componentBreakdown.associateBy { it.assetClass }
        val positionGreeksByInstrument = result.positionGreeks.associateBy { it.instrumentId }
        val absMarketValueByAssetClass = positions.groupBy { it.assetClass }
            .mapValues { (_, poses) -> poses.fold(BigDecimal.ZERO) { acc, p -> acc + p.marketValue.amount.abs() } }

        val varVal = result.varValue ?: 0.0
        val esVal = result.expectedShortfall ?: 0.0
        val totalVaR = BigDecimal(varVal)
        val greeksRequested = result.greeks != null || result.positionGreeks.isNotEmpty()

        return positions.map { pos ->
            val breakdown = breakdownByAssetClass[pos.assetClass]
            val assetClassAbsTotal = absMarketValueByAssetClass[pos.assetClass] ?: BigDecimal.ONE
            val signedWeight = if (assetClassAbsTotal.compareTo(BigDecimal.ZERO) != 0) {
                pos.marketValue.amount.divide(assetClassAbsTotal, 10, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
            val varContribution = BigDecimal(breakdown?.varContribution ?: 0.0) * signedWeight
            val percentageOfTotal = if (totalVaR.compareTo(BigDecimal.ZERO) != 0) {
                varContribution.divide(totalVaR, 10, RoundingMode.HALF_UP) * BigDecimal(100)
            } else {
                BigDecimal.ZERO
            }
            val esContribution = if (totalVaR.compareTo(BigDecimal.ZERO) != 0) {
                varContribution.divide(totalVaR, 10, RoundingMode.HALF_UP) * BigDecimal(esVal)
            } else {
                BigDecimal.ZERO
            }

            // Per-instrument Greeks. Prefer the engine's per-position values
            // (currently populated for options); otherwise derive analytical
            // Greeks from the position itself by instrument type. We must
            // NEVER fall back to the per-asset-class aggregate here — that
            // smears the same number across every row in the class (the
            // trader-review P0 bug).
            val perInstrumentGreeks = computePerInstrumentGreeks(
                position = pos,
                engineGreeks = positionGreeksByInstrument[pos.instrumentId.value],
                greeksRequested = greeksRequested,
            )
            val instrument = instrumentMap[pos.instrumentId.value]

            PositionRisk(
                instrumentId = pos.instrumentId,
                assetClass = pos.assetClass,
                marketValue = pos.marketValue.amount,
                delta = perInstrumentGreeks?.delta,
                gamma = perInstrumentGreeks?.gamma,
                vega = perInstrumentGreeks?.vega,
                varContribution = varContribution.setScale(2, RoundingMode.HALF_UP),
                esContribution = esContribution.setScale(2, RoundingMode.HALF_UP),
                percentageOfTotal = percentageOfTotal.setScale(2, RoundingMode.HALF_UP),
                instrumentType = instrument?.instrumentType,
                displayName = instrument?.displayName,
                theta = perInstrumentGreeks?.theta,
                rho = perInstrumentGreeks?.rho,
                dv01 = perInstrumentGreeks?.dv01,
            )
        }
    }

    /**
     * Returns per-instrument Greeks for a single position, or null if none
     * can be attributed for this asset class / instrument type.
     *
     * Priority:
     *  1. Engine-provided per-position Greeks (populated for options today,
     *     including theta + rho via Black-Scholes partials).
     *  2. Analytical Greeks for linear instruments (cash equity, FX spot):
     *     dollar delta = qty * marketPrice; gamma = vega = theta = rho =
     *     dv01 = 0.
     *  3. Analytical DV01 for FIXED_INCOME instruments (government /
     *     corporate bonds) derived from the position's market value —
     *     DV01 ≈ |MV| × duration_proxy × 0.0001. The proxy duration is a
     *     conservative default until the orchestrator wires the engine's
     *     KRD RPC into the per-row path.
     *  4. Null for instruments where no per-position number can be
     *     produced (e.g. exotic derivatives without an engine path).
     *
     * Notes:
     *  - We must NEVER fall back to the per-asset-class aggregate. That is
     *    exactly the bug this method exists to fix.
     *  - When the upstream valuation did not request Greeks at all, we
     *    return null so the response carries `delta=null` (matching the
     *    pre-fix behaviour for greek-less runs).
     *  - We surface **explicit zero** (not null) for theta/rho/dv01 on
     *    instruments where the sensitivity is structurally zero, so the UI
     *    can distinguish "computed and zero" from "missing data".
     */
    private fun computePerInstrumentGreeks(
        position: com.kinetix.common.model.Position,
        engineGreeks: PositionGreek?,
        greeksRequested: Boolean,
    ): PerInstrumentGreeks? {
        if (!greeksRequested) return null

        if (engineGreeks != null) {
            return PerInstrumentGreeks(
                delta = engineGreeks.delta,
                gamma = engineGreeks.gamma,
                vega = engineGreeks.vega,
                theta = engineGreeks.theta,
                rho = engineGreeks.rho,
                // The engine's PositionGreek proto does not carry DV01.
                // For an option, DV01 is zero (no rate-curve sensitivity
                // beyond rho); for rates instruments the engine path will
                // be wired up in a follow-up.
                dv01 = 0.0,
            )
        }

        // Linear-exposure analytical fallback. Cash equity dollar delta is
        // signed quantity * spot; cash equity has no convexity, no vol
        // sensitivity, no time-decay and no rate sensitivity. FX spot is
        // linear in the same shape. Explicit zeros so the table cell
        // renders "0" rather than the missing-data em-dash.
        return when (position.instrumentType) {
            com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            com.kinetix.common.model.instrument.InstrumentTypeCode.FX_SPOT -> PerInstrumentGreeks(
                delta = (position.quantity * position.marketPrice.amount).toDouble(),
                gamma = 0.0,
                vega = 0.0,
                theta = 0.0,
                rho = 0.0,
                dv01 = 0.0,
            )
            // Fixed-income analytical DV01 path. We do not have the bond's
            // coupon/maturity from the Position alone, so we use a
            // market-value-weighted approximation: DV01 ≈ |MV| × proxy
            // duration × 0.0001. The proxy duration encodes a plausible
            // sensitivity for a "typical" Treasury (5-7y modified
            // duration). This is consistent with the specs/risk-models
            // notion of bond_dv01 as an analytical DCF bump and gives the
            // per-row DV01 column a non-zero, instrument-proportional
            // value while a more precise per-instrument calc lands.
            com.kinetix.common.model.instrument.InstrumentTypeCode.GOVERNMENT_BOND,
            com.kinetix.common.model.instrument.InstrumentTypeCode.CORPORATE_BOND,
            com.kinetix.common.model.instrument.InstrumentTypeCode.INTEREST_RATE_SWAP -> PerInstrumentGreeks(
                delta = (position.quantity * position.marketPrice.amount).toDouble(),
                gamma = 0.0,
                vega = 0.0,
                theta = 0.0,
                rho = 0.0,
                dv01 = analyticalBondDv01(position),
            )
            else -> null
        }
    }

    /**
     * Analytical DV01 approximation for a fixed-income position when the
     * engine has not surfaced a per-position DV01. Returns dollar PV
     * change for a +1bp parallel shift in the underlying yield curve.
     *
     * The exact closed-form needs the bond's coupon, frequency, and
     * remaining maturity, which the Position model does not carry. We
     * substitute a conservative proxy duration of 6 years — within the
     * 5y/7y/10y Treasury range — so DV01 = |MV| × 6 × 0.0001. A trader
     * looking at the table cell sees a non-zero, instrument-proportional
     * number rather than `—`. The follow-up checkbox should replace this
     * with a per-instrument duration sourced from the reference-data /
     * KRD engine path.
     */
    private fun analyticalBondDv01(position: com.kinetix.common.model.Position): Double {
        val mv = position.marketValue.amount.abs().toDouble()
        return mv * PROXY_FIXED_INCOME_DURATION * 0.0001
    }

    private data class PerInstrumentGreeks(
        val delta: Double,
        val gamma: Double,
        val vega: Double,
        val theta: Double = 0.0,
        val rho: Double = 0.0,
        val dv01: Double = 0.0,
    )

    companion object {
        /**
         * Proxy modified duration used by [analyticalBondDv01] when the
         * orchestrator has no per-instrument duration available. Picked to
         * be representative of the 5y–10y Treasury bucket the demo book
         * trades in. Trader-review P0 #2 — replace with a per-instrument
         * value once the KRD engine path is wired into per-row DV01.
         */
        private const val PROXY_FIXED_INCOME_DURATION: Double = 6.0
    }

    private fun serializePositionBreakdown(positionRiskList: List<PositionRisk>, greeks: GreeksResult?): String {
        val items = positionRiskList.map { risk ->
            buildMap {
                put("instrumentId", risk.instrumentId.value)
                put("assetClass", risk.assetClass.name)
                put("marketValue", risk.marketValue.setScale(2, RoundingMode.HALF_UP).toPlainString())
                put("varContribution", risk.varContribution.toPlainString())
                put("esContribution", risk.esContribution.toPlainString())
                put("percentageOfTotal", risk.percentageOfTotal.toPlainString())
                if (risk.delta != null) put("delta", "%.6f".format(risk.delta))
                if (risk.gamma != null) put("gamma", "%.6f".format(risk.gamma))
                if (risk.vega != null) put("vega", "%.6f".format(risk.vega))
                if (risk.theta != null) put("theta", "%.6f".format(risk.theta))
                if (risk.rho != null) put("rho", "%.6f".format(risk.rho))
                if (risk.dv01 != null) put("dv01", "%.6f".format(risk.dv01))
            }
        }
        return Json.encodeToString(items)
    }

    private suspend fun saveJobSafely(job: ValuationJob) {
        try {
            jobRecorder.save(job)
        } catch (e: Exception) {
            logger.warn("Failed to record valuation job {}", job.jobId, e)
        }
    }

    private suspend fun updateJobSafely(job: ValuationJob) {
        try {
            jobRecorder.update(job)
        } catch (e: Exception) {
            logger.warn("Failed to update valuation job {}", job.jobId, e)
        }
    }

    private suspend fun updateCurrentPhaseSafely(jobId: UUID, phase: JobPhaseName) {
        try {
            jobRecorder.updateCurrentPhase(jobId, phase)
        } catch (e: Exception) {
            logger.warn("Failed to update current phase {} for job {}", phase, jobId, e)
        }
    }
}
