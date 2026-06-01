package com.kinetix.risk.routes

import com.kinetix.common.dtos.ApiError
import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.risk.routes.dtos.*
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.mapper.toDetailResponse
import com.kinetix.risk.mapper.toProto
import com.kinetix.risk.mapper.toValuationResult
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.ValuationOutput
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.persistence.LatestStressBatchRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.service.BatchStressTestService
import com.kinetix.risk.service.NoSodBaselineException
import com.kinetix.risk.service.PnlComputationService
import com.kinetix.risk.service.SodSnapshotService
import com.kinetix.risk.service.StressLimitCheckService
import com.kinetix.risk.service.VaRCalculationService
import com.kinetix.risk.service.ValuationJobRecorder
import com.kinetix.risk.service.RebalancingWhatIfService
import com.kinetix.risk.service.WhatIfAnalysisService
import com.kinetix.risk.routes.dtos.BatchScenarioFailureDto
import com.kinetix.risk.routes.dtos.BatchScenarioResultDto
import com.kinetix.risk.routes.dtos.BatchStressRunResultResponse
import com.kinetix.proto.common.BookId as ProtoBookId
import com.kinetix.proto.risk.FrtbRequest
import com.kinetix.proto.risk.GenerateReportRequest
import com.kinetix.proto.risk.HistoricalReplayRequest as ProtoHistoricalReplayRequest
import com.kinetix.proto.risk.InstrumentDailyReturns
import com.kinetix.proto.risk.ListScenariosRequest
import com.kinetix.proto.risk.RegulatoryReportingServiceGrpcKt
import com.kinetix.proto.risk.ReportFormat
import com.kinetix.proto.risk.ReverseStressRequest as ProtoReverseStressRequest
import com.kinetix.proto.risk.StressTestRequest
import com.kinetix.proto.risk.StressTestServiceGrpcKt
import com.kinetix.risk.routes.dtos.HistoricalReplayRequestBody
import com.kinetix.risk.routes.dtos.ReverseStressRequestBody
import com.kinetix.risk.model.SnapshotType
import com.kinetix.common.resilience.CircuitBreakerOpenException
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.LocalDate

fun Route.riskRoutes(
    varCalculationService: VaRCalculationService,
    varCache: VaRCache,
    positionProvider: PositionProvider,
    stressTestStub: StressTestServiceGrpcKt.StressTestServiceCoroutineStub,
    regulatoryStub: RegulatoryReportingServiceGrpcKt.RegulatoryReportingServiceCoroutineStub,
    riskEngineClient: RiskEngineClient? = null,
    whatIfAnalysisService: WhatIfAnalysisService? = null,
    rebalancingWhatIfService: RebalancingWhatIfService? = null,
    pnlAttributionRepository: PnlAttributionRepository? = null,
    sodSnapshotService: SodSnapshotService? = null,
    pnlComputationService: PnlComputationService? = null,
    stressLimitCheckService: StressLimitCheckService? = null,
    jobRecorder: ValuationJobRecorder? = null,
    batchStressTestService: BatchStressTestService? = null,
    latestStressBatchRepository: LatestStressBatchRepository? = null,
) {
    // VaR routes
    route("/api/v1/risk/var/{bookId}") {
        post({
            summary = "Calculate VaR for a portfolio"
            tags = listOf("VaR")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                body<VaRCalculationRequestBody>()
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val body = call.receive<VaRCalculationRequestBody>()
            val requestedOutputs = body.requestedOutputs
                ?.mapNotNull { runCatching { ValuationOutput.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: ValuationOutput.entries.toSet()
            val request = VaRCalculationRequest(
                bookId = BookId(bookId),
                calculationType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC"),
                confidenceLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95"),
                timeHorizonDays = body.timeHorizonDays?.toInt() ?: 1,
                numSimulations = body.numSimulations?.toInt() ?: 10_000,
                requestedOutputs = requestedOutputs,
            )
            val result = try {
                varCalculationService.calculateVaR(request, triggeredBy = "API")
            } catch (e: CircuitBreakerOpenException) {
                val staleResult = varCache.get(bookId)
                if (staleResult != null) {
                    call.response.header("Warning", """199 - "Stale data"""")
                    call.respond(staleResult.toResponse().copy(stale = true))
                } else {
                    call.response.header("Retry-After", "30")
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiError(code = "SERVICE_UNAVAILABLE", message = "Risk engine temporarily unavailable"),
                    )
                }
                return@post
            }
            if (result != null) {
                varCache.put(bookId, result)
                call.respond(result.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get({
            summary = "Get latest cached VaR result"
            tags = listOf("VaR")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                queryParameter<String>("valuationDate") {
                    description = "Valuation date (YYYY-MM-DD). When set, returns historical snapshot."
                    required = false
                }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val valuationDateParam = call.request.queryParameters["valuationDate"]

            if (valuationDateParam != null) {
                val valuationDate = try {
                    LocalDate.parse(valuationDateParam)
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid 'valuationDate' format. Expected YYYY-MM-DD.")
                    return@get
                }
                if (valuationDate > LocalDate.now(java.time.ZoneOffset.UTC)) {
                    call.respond(HttpStatusCode.NotFound, "No risk snapshot for future date")
                    return@get
                }
                val job = jobRecorder?.findLatestCompletedByDate(bookId, valuationDate)
                if (job != null) {
                    val result = job.toValuationResult()
                    if (result != null) {
                        call.respond(result.toResponse())
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            } else {
                val cached = varCache.get(bookId)
                if (cached != null) {
                    call.respond(cached.toResponse())
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }

    // Position-level risk routes
    get("/api/v1/risk/positions/{bookId}", {
        summary = "Get position-level risk breakdown"
        tags = listOf("Position Risk")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
            queryParameter<String>("valuationDate") {
                description = "Valuation date (YYYY-MM-DD). When set, returns historical snapshot."
                required = false
            }
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val valuationDateParam = call.request.queryParameters["valuationDate"]

        if (valuationDateParam != null) {
            val valuationDate = try {
                LocalDate.parse(valuationDateParam)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid 'valuationDate' format. Expected YYYY-MM-DD.")
                return@get
            }
            val job = jobRecorder?.findLatestCompletedByDate(bookId, valuationDate)
            if (job != null && job.positionRiskSnapshot.isNotEmpty()) {
                call.respond(job.positionRiskSnapshot.map { it.toDto() })
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        } else {
            val cached = varCache.get(bookId)
            if (cached != null && cached.positionRisk.isNotEmpty()) {
                call.respond(cached.positionRisk.map { it.toDto() })
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    // P&L attribution routes
    if (pnlAttributionRepository != null) {
        get("/api/v1/risk/pnl-attribution/{bookId}", {
            summary = "Get P&L attribution for a portfolio"
            tags = listOf("P&L Attribution")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                queryParameter<String>("date") {
                    description = "Attribution date (ISO-8601 date, e.g. 2025-01-15). Defaults to latest available."
                    required = false
                }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val dateParam = call.request.queryParameters["date"]

            val attribution = if (dateParam != null) {
                val date = try {
                    java.time.LocalDate.parse(dateParam)
                } catch (_: java.time.format.DateTimeParseException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid 'date' parameter")
                    return@get
                }
                pnlAttributionRepository.findByBookIdAndDate(BookId(bookId), date)
            } else {
                pnlAttributionRepository.findLatestByBookId(BookId(bookId))
            }

            if (attribution != null) {
                call.respond(attribution.toResponse())
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    // SOD snapshot routes
    if (sodSnapshotService != null) {
        get("/api/v1/risk/sod-snapshot/{bookId}/status", {
            summary = "Get SOD baseline status for a portfolio"
            tags = listOf("SOD Snapshot")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val status = sodSnapshotService.getBaselineStatus(
                BookId(bookId),
                LocalDate.now(),
            )
            call.respond(status.toResponse())
        }

        post("/api/v1/risk/sod-snapshot/{bookId}", {
            summary = "Create manual SOD snapshot"
            tags = listOf("SOD Snapshot")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                queryParameter<String>("jobId") {
                    description = "Optional VaR job ID to use as baseline source"
                    required = false
                }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val today = LocalDate.now()
            val jobIdParam = call.request.queryParameters["jobId"]
            try {
                if (jobIdParam != null) {
                    sodSnapshotService.createSnapshotFromJob(
                        BookId(bookId),
                        java.util.UUID.fromString(jobIdParam),
                        today,
                    )
                } else {
                    sodSnapshotService.createSnapshot(
                        BookId(bookId),
                        SnapshotType.MANUAL,
                        date = today,
                    )
                }
            } catch (e: IllegalStateException) {
                call.response.status(HttpStatusCode.UnprocessableEntity)
                call.respond(
                    mapOf("error" to "no_valuation_data", "message" to (e.message ?: "Cannot create snapshot")),
                )
                return@post
            } catch (e: IllegalArgumentException) {
                call.response.status(HttpStatusCode.UnprocessableEntity)
                call.respond(
                    mapOf("error" to "invalid_job", "message" to (e.message ?: "Invalid job")),
                )
                return@post
            }
            val status = sodSnapshotService.getBaselineStatus(BookId(bookId), today)
            call.response.status(HttpStatusCode.Created)
            call.respond(status.toResponse())
        }

        delete("/api/v1/risk/sod-snapshot/{bookId}", {
            summary = "Reset SOD baseline for a portfolio"
            tags = listOf("SOD Snapshot")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            sodSnapshotService.resetBaseline(BookId(bookId), LocalDate.now())
            call.response.status(HttpStatusCode.NoContent)
            call.respond("")
        }
    }

    // P&L computation route
    if (pnlComputationService != null) {
        post("/api/v1/risk/pnl-attribution/{bookId}/compute", {
            summary = "Trigger P&L attribution computation"
            tags = listOf("P&L Attribution")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            try {
                val attribution = pnlComputationService.compute(BookId(bookId))
                call.respond(attribution.toResponse())
            } catch (e: NoSodBaselineException) {
                call.response.status(HttpStatusCode.PreconditionFailed)
                call.respond(
                    mapOf("error" to "no_sod_baseline", "message" to (e.message ?: "")),
                )
            }
        }
    }

    // What-if analysis routes
    if (whatIfAnalysisService != null) {
        route("/api/v1/risk/what-if/{bookId}") {
            post({
                summary = "Run what-if analysis for a portfolio"
                tags = listOf("What-If")
                request {
                    pathParameter<String>("bookId") { description = "Portfolio identifier" }
                    body<WhatIfRequestBody>()
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val body = call.receive<WhatIfRequestBody>()

                val trades = body.hypotheticalTrades.map { it.toDomain() }
                val calcType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC")
                val confLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95")

                val result = whatIfAnalysisService.analyzeWhatIf(
                    bookId = BookId(bookId),
                    hypotheticalTrades = trades,
                    calculationType = calcType,
                    confidenceLevel = confLevel,
                )

                call.respond(result.toResponse())
            }
        }
    }

    // Rebalancing what-if route
    if (rebalancingWhatIfService != null) {
        post("/api/v1/risk/what-if/{bookId}/rebalance", {
            summary = "Run rebalancing what-if analysis for a portfolio"
            tags = listOf("What-If")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                body<com.kinetix.risk.routes.dtos.RebalancingRequestBody>()
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val body = call.receive<com.kinetix.risk.routes.dtos.RebalancingRequestBody>()

            val trades = body.trades.map { it.toDomain() }
            val calcType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC")
            val confLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95")

            val result = rebalancingWhatIfService.analyzeRebalancing(
                bookId = BookId(bookId),
                trades = trades,
                calculationType = calcType,
                confidenceLevel = confLevel,
            )

            call.respond(result.toResponse())
        }
    }

    // Stress test routes
    route("/api/v1/risk/stress/{bookId}") {
        post({
            summary = "Run stress test for a portfolio"
            tags = listOf("Stress Tests")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                body<StressTestRequestBody>()
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val body = call.receive<StressTestRequestBody>()
            val positions = positionProvider.getPositions(BookId(bookId))
            val calcType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC")
            val confLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95")

            val protoRequest = StressTestRequest.newBuilder()
                .setBookId(ProtoBookId.newBuilder().setValue(bookId))
                .setScenarioName(body.scenarioName)
                .setCalculationType(calcType.toProto())
                .setConfidenceLevel(confLevel.toProto())
                .setTimeHorizonDays(body.timeHorizonDays?.toInt() ?: 1)
                .addAllPositions(positions.map { it.toProto() })
                .also { builder ->
                    body.volShocks?.forEach { (k, v) -> builder.putVolShocks(k, v) }
                    body.priceShocks?.forEach { (k, v) -> builder.putPriceShocks(k, v) }
                    body.description?.let { builder.description = it }
                }
                .build()

            val response = stressTestStub.runStressTest(protoRequest)
            val stressResult = response.toStressTestResponse()
            val withBreaches = if (stressLimitCheckService != null) {
                val breaches = stressLimitCheckService.evaluateBreaches(stressResult)
                stressResult.copy(limitBreaches = breaches)
            } else {
                stressResult
            }
            call.respond(withBreaches)
        }
    }

    // Batch stress test route — delegates to BatchStressTestService when wired up.
    // Returns ranked results (worst P&L first) with individual scenario failure tolerance.
    post("/api/v1/risk/stress/{bookId}/batch", {
        summary = "Run all stress tests for a portfolio, ranked by worst P&L impact"
        tags = listOf("Stress Tests")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
            body<StressTestBatchRequestBody>()
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val body = call.receive<StressTestBatchRequestBody>()

        if (batchStressTestService != null) {
            val batchResult = batchStressTestService.runBatch(
                bookId = BookId(bookId),
                scenarioNames = body.scenarioNames,
                calculationType = body.calculationType ?: "PARAMETRIC",
                confidenceLevel = body.confidenceLevel ?: "CL_95",
                timeHorizonDays = body.timeHorizonDays?.toInt() ?: 1,
            )
            val responseDto = BatchStressRunResultResponse(
                results = batchResult.results.map { r ->
                    BatchScenarioResultDto(
                        scenarioName = r.scenarioName,
                        baseVar = r.baseVar,
                        stressedVar = r.stressedVar,
                        pnlImpact = r.pnlImpact,
                    )
                },
                failedScenarios = batchResult.failedScenarios.map { f ->
                    BatchScenarioFailureDto(
                        scenarioName = f.scenarioName,
                        errorMessage = f.errorMessage,
                    )
                },
                worstScenarioName = batchResult.worstScenarioName,
                worstPnlImpact = batchResult.worstPnlImpact,
            )
            // Persist the latest batch per book so the Scenarios tab can populate
            // on cold open without a fresh "Run All Scenarios" click (kx-kjse).
            // Only store non-empty sweeps — an empty result would clobber a
            // previously useful one with nothing.
            if (responseDto.results.isNotEmpty()) {
                latestStressBatchRepository?.save(BookId(bookId), responseDto)
            }
            call.respond(responseDto)
        } else {
            // Legacy path: inline parallel execution without ranking or failure tolerance.
            val positions = positionProvider.getPositions(BookId(bookId))
            val calcType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC")
            val confLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95")
            val timeHorizon = body.timeHorizonDays?.toInt() ?: 1
            val protoPositions = positions.map { it.toProto() }

            val rawResults = coroutineScope {
                body.scenarioNames.map { scenarioName ->
                    async {
                        val protoRequest = StressTestRequest.newBuilder()
                            .setBookId(ProtoBookId.newBuilder().setValue(bookId))
                            .setScenarioName(scenarioName)
                            .setCalculationType(calcType.toProto())
                            .setConfidenceLevel(confLevel.toProto())
                            .setTimeHorizonDays(timeHorizon)
                            .addAllPositions(protoPositions)
                            .build()
                        stressTestStub.runStressTest(protoRequest).toStressTestResponse()
                    }
                }.awaitAll()
            }

            val results = if (stressLimitCheckService != null) {
                rawResults.map { result ->
                    val breaches = stressLimitCheckService.evaluateBreaches(result)
                    result.copy(limitBreaches = breaches)
                }
            } else {
                rawResults
            }

            call.respond(results)
        }
    }

    // Most recent persisted batch stress result for the book (kx-kjse).
    // Lets the Scenarios tab render the comparison grid on cold open; returns
    // 404 when no batch has been run/seeded for the book yet.
    get("/api/v1/risk/stress/{bookId}/batch", {
        summary = "Most recent persisted batch stress result for a portfolio"
        tags = listOf("Stress Tests")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val stored = latestStressBatchRepository?.findLatestByBookId(BookId(bookId))
        if (stored != null) {
            call.respond(stored)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }

    get("/api/v1/risk/stress/scenarios", {
        summary = "List available stress test scenarios"
        tags = listOf("Stress Tests")
    }) {
        val response = stressTestStub.listScenarios(ListScenariosRequest.getDefaultInstance())
        call.respond(response.scenarioNamesList)
    }

    // Historical replay route
    post("/api/v1/risk/stress/{bookId}/historical-replay", {
        summary = "Run a historical scenario replay against current positions"
        tags = listOf("Stress Tests")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
            body<HistoricalReplayRequestBody>()
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val body = call.receive<HistoricalReplayRequestBody>()
        val positions = positionProvider.getPositions(BookId(bookId))

        val protoRequest = ProtoHistoricalReplayRequest.newBuilder()
            .also { builder ->
                builder.addAllPositions(positions.map { it.toProto() })
                body.instrumentReturns.forEach { ir ->
                    builder.addInstrumentReturns(
                        InstrumentDailyReturns.newBuilder()
                            .setInstrumentId(ir.instrumentId)
                            .addAllDailyReturns(ir.dailyReturns)
                            .build()
                    )
                }
                body.scenarioName?.let { builder.scenarioName = it }
                body.windowStart?.let { builder.windowStart = it }
                body.windowEnd?.let { builder.windowEnd = it }
            }
            .build()

        val response = stressTestStub.runHistoricalReplay(protoRequest)
        call.respond(response.toHistoricalReplayResponse())
    }

    // Reverse stress route
    post("/api/v1/risk/stress/{bookId}/reverse", {
        summary = "Find minimum-norm shock vector that causes the target portfolio loss"
        tags = listOf("Stress Tests")
        request {
            pathParameter<String>("bookId") { description = "Portfolio identifier" }
            body<ReverseStressRequestBody>()
        }
    }) {
        val bookId = call.requirePathParam("bookId")
        val body = call.receive<ReverseStressRequestBody>()
        val positions = positionProvider.getPositions(BookId(bookId))

        val protoRequest = ProtoReverseStressRequest.newBuilder()
            .addAllPositions(positions.map { it.toProto() })
            .setTargetLoss(body.targetLoss)
            .setMaxShock(body.maxShock)
            .build()

        val response = stressTestStub.runReverseStress(protoRequest)
        call.respond(response.toReverseStressResponse())
    }

    // Greeks routes — convenience wrapper that goes through the full valuation pipeline
    route("/api/v1/risk/greeks/{bookId}") {
        post({
            summary = "Calculate Greeks for a portfolio"
            tags = listOf("Greeks")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                body<VaRCalculationRequestBody>()
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val body = call.receive<VaRCalculationRequestBody>()
            val request = VaRCalculationRequest(
                bookId = BookId(bookId),
                calculationType = CalculationType.valueOf(body.calculationType ?: "PARAMETRIC"),
                confidenceLevel = ConfidenceLevel.valueOf(body.confidenceLevel ?: "CL_95"),
                timeHorizonDays = body.timeHorizonDays?.toInt() ?: 1,
                numSimulations = body.numSimulations?.toInt() ?: 10_000,
                requestedOutputs = ValuationOutput.entries.toSet(),
            )
            val result = varCalculationService.calculateVaR(request, triggeredBy = "API")
            if (result?.greeks != null) {
                varCache.put(bookId, result)
                val greeks = result.greeks
                call.respond(
                    GreeksResponse(
                        bookId = bookId,
                        assetClassGreeks = greeks.assetClassGreeks.map {
                            GreekValuesDto(
                                assetClass = it.assetClass.name,
                                delta = "%.6f".format(it.delta),
                                gamma = "%.6f".format(it.gamma),
                                vega = "%.6f".format(it.vega),
                            )
                        },
                        theta = "%.6f".format(greeks.theta),
                        rho = "%.6f".format(greeks.rho),
                        calculatedAt = result.calculatedAt.toString(),
                    )
                )
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    // FRTB routes
    route("/api/v1/regulatory/frtb/{bookId}") {
        post({
            summary = "Calculate FRTB for a portfolio"
            tags = listOf("Regulatory")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val positions = positionProvider.getPositions(BookId(bookId))

            val protoRequest = FrtbRequest.newBuilder()
                .setBookId(ProtoBookId.newBuilder().setValue(bookId))
                .addAllPositions(positions.map { it.toProto() })
                .build()

            val response = regulatoryStub.calculateFrtb(protoRequest)
            call.respond(
                FrtbResultResponse(
                    bookId = response.bookId,
                    sbmCharges = response.sbm.riskClassChargesList.map {
                        RiskClassChargeDto(
                            riskClass = FRTB_RISK_CLASS_NAMES[it.riskClass] ?: it.riskClass.name,
                            deltaCharge = "%.2f".format(it.deltaCharge),
                            vegaCharge = "%.2f".format(it.vegaCharge),
                            curvatureCharge = "%.2f".format(it.curvatureCharge),
                            totalCharge = "%.2f".format(it.totalCharge),
                        )
                    },
                    totalSbmCharge = "%.2f".format(response.sbm.totalSbmCharge),
                    grossJtd = "%.2f".format(response.drc.grossJtd),
                    hedgeBenefit = "%.2f".format(response.drc.hedgeBenefit),
                    netDrc = "%.2f".format(response.drc.netDrc),
                    exoticNotional = "%.2f".format(response.rrao.exoticNotional),
                    otherNotional = "%.2f".format(response.rrao.otherNotional),
                    totalRrao = "%.2f".format(response.rrao.totalRrao),
                    totalCapitalCharge = "%.2f".format(response.totalCapitalCharge),
                    calculatedAt = Instant.ofEpochSecond(response.calculatedAt.seconds, response.calculatedAt.nanos.toLong()).toString(),
                )
            )
        }
    }

    // Report routes
    route("/api/v1/regulatory/report/{bookId}") {
        post({
            summary = "Generate regulatory report"
            tags = listOf("Regulatory")
            request {
                pathParameter<String>("bookId") { description = "Portfolio identifier" }
                body<GenerateReportRequestBody>()
            }
        }) {
            val bookId = call.requirePathParam("bookId")
            val body = call.receive<GenerateReportRequestBody>()
            val positions = positionProvider.getPositions(BookId(bookId))
            val format = when (body.format?.uppercase()) {
                "XBRL" -> ReportFormat.XBRL
                else -> ReportFormat.CSV
            }

            val protoRequest = GenerateReportRequest.newBuilder()
                .setBookId(ProtoBookId.newBuilder().setValue(bookId))
                .addAllPositions(positions.map { it.toProto() })
                .setFormat(format)
                .build()

            val response = regulatoryStub.generateReport(protoRequest)
            call.respond(
                ReportResponse(
                    bookId = response.bookId,
                    format = response.format.name,
                    content = response.content,
                    generatedAt = Instant.ofEpochSecond(response.generatedAt.seconds, response.generatedAt.nanos.toLong()).toString(),
                )
            )
        }
    }

    // Market data dependencies routes
    if (riskEngineClient != null) {
        route("/api/v1/risk/dependencies/{bookId}") {
            post({
                summary = "Discover market data dependencies"
                tags = listOf("Dependencies")
                request {
                    pathParameter<String>("bookId") { description = "Portfolio identifier" }
                    body<DependenciesRequestBody>()
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val body = call.receive<DependenciesRequestBody>()
                val positions = positionProvider.getPositions(BookId(bookId))
                val calcType = body.calculationType ?: "PARAMETRIC"
                val confLevel = body.confidenceLevel ?: "CL_95"

                val response = riskEngineClient.discoverDependencies(positions, calcType, confLevel)
                call.respond(
                    DataDependenciesResponse(
                        bookId = bookId,
                        dependencies = response.dependenciesList.map {
                            MarketDataDependencyDto(
                                dataType = MARKET_DATA_TYPE_NAMES[it.dataType] ?: it.dataType.name,
                                instrumentId = it.instrumentId,
                                assetClass = it.assetClass,
                                required = it.required,
                                description = it.description,
                                parameters = it.parametersMap,
                            )
                        },
                    )
                )
            }
        }
    }
}
