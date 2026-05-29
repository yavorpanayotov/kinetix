package com.kinetix.gateway.client

import com.kinetix.gateway.client.dtos.ChartDataClientDto
import com.kinetix.gateway.client.dtos.EodTimelineClientDto
import com.kinetix.gateway.client.dtos.toDomain
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class HttpRiskServiceClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : RiskServiceClient {

    @Serializable
    private data class UpstreamErrorBody(val code: String = "", val message: String = "")

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private suspend fun handleErrorResponse(response: HttpResponse): Nothing {
        val body = try {
            response.bodyAsText()
        } catch (_: Exception) {
            ""
        }

        val message = try {
            lenientJson.decodeFromString<UpstreamErrorBody>(body).message
        } catch (_: Exception) {
            body.ifBlank { response.status.description }
        }

        when (response.status.value) {
            503 -> {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toIntOrNull()
                throw ServiceUnavailableException(retryAfter, message)
            }
            504 -> throw GatewayTimeoutException(message)
            else -> throw UpstreamErrorException(response.status.value, message)
        }
    }

    override suspend fun getMarginEstimate(bookId: String, previousMTM: String?): MarginEstimateSummary? {
        val response = httpClient.get("$baseUrl/api/v1/books/$bookId/margin") {
            if (previousMTM != null) {
                url { parameters.append("previousMTM", previousMTM) }
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body<MarginEstimateClientDto>().toDomain()
    }

    override suspend fun calculateVaR(params: VaRCalculationParams): ValuationResultSummary? {
        val response = httpClient.post("$baseUrl/api/v1/risk/var/${params.bookId}") {
            contentType(ContentType.Application.Json)
            setBody(
                VaRCalculationRequestDto(
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                    timeHorizonDays = params.timeHorizonDays.toString(),
                    numSimulations = params.numSimulations.toString(),
                    requestedOutputs = params.requestedOutputs,
                )
            )
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: ValuationResultDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getLatestVaR(bookId: String, valuationDate: String?): ValuationResultSummary? {
        val response = httpClient.get("$baseUrl/api/v1/risk/var/$bookId") {
            if (!valuationDate.isNullOrBlank()) {
                url { parameters.append("valuationDate", valuationDate) }
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: ValuationResultDto = response.body()
        return dto.toDomain()
    }

    override suspend fun runStressTest(params: StressTestParams): StressTestResultSummary? {
        val response = httpClient.post("$baseUrl/api/v1/risk/stress/${params.bookId}") {
            contentType(ContentType.Application.Json)
            setBody(
                StressTestRequestDto(
                    scenarioName = params.scenarioName,
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                    timeHorizonDays = params.timeHorizonDays.toString(),
                    volShocks = params.volShocks,
                    priceShocks = params.priceShocks,
                    description = params.description,
                )
            )
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: StressTestResultDto = response.body()
        return dto.toDomain()
    }

    override suspend fun runBatchStressTest(params: StressTestBatchParams): BatchStressRunSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/stress/${params.bookId}/batch") {
            contentType(ContentType.Application.Json)
            setBody(
                StressTestBatchRequestDto(
                    scenarioNames = params.scenarioNames,
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                    timeHorizonDays = params.timeHorizonDays.toString(),
                )
            )
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: BatchStressRunResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun listScenarios(): List<String> {
        val response = httpClient.get("$baseUrl/api/v1/risk/stress/scenarios")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun calculateGreeks(params: VaRCalculationParams): GreeksResultSummary? {
        val result = calculateVaR(
            params.copy(requestedOutputs = listOf("VAR", "EXPECTED_SHORTFALL", "GREEKS"))
        )
        return result?.greeks
    }

    override suspend fun calculateFrtb(bookId: String): FrtbResultSummary? {
        val response = httpClient.post("$baseUrl/api/v1/regulatory/frtb/$bookId") {
            contentType(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: FrtbResultDto = response.body()
        return dto.toDomain()
    }

    override suspend fun generateReport(bookId: String, format: String): ReportResult? {
        val response = httpClient.post("$baseUrl/api/v1/regulatory/report/$bookId") {
            contentType(ContentType.Application.Json)
            setBody(GenerateReportRequestDto(format = format))
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: ReportResultDto = response.body()
        return dto.toDomain()
    }

    override suspend fun discoverDependencies(params: DependenciesParams): DataDependenciesSummary? {
        val response = httpClient.post("$baseUrl/api/v1/risk/dependencies/${params.bookId}") {
            contentType(ContentType.Application.Json)
            setBody(
                DependenciesRequestDto(
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                )
            )
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: DataDependenciesDto = response.body()
        return dto.toDomain()
    }

    override suspend fun listValuationJobs(bookId: String, limit: Int, offset: Int, from: Instant?, to: Instant?, valuationDate: String?): Pair<List<ValuationJobSummaryItem>, Long> {
        val response = httpClient.get("$baseUrl/api/v1/risk/jobs/$bookId") {
            url {
                parameters.append("limit", limit.toString())
                parameters.append("offset", offset.toString())
                if (from != null) parameters.append("from", from.toString())
                if (to != null) parameters.append("to", to.toString())
                if (!valuationDate.isNullOrBlank()) parameters.append("valuationDate", valuationDate)
            }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: PaginatedJobsClientDto = response.body()
        return Pair(dto.items.map { it.toDomain() }, dto.totalCount)
    }

    override suspend fun getValuationJobDetail(jobId: String): ValuationJobDetailItem? {
        val response = httpClient.get("$baseUrl/api/v1/risk/jobs/detail/$jobId")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: ValuationJobDetailClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getSodBaselineStatus(bookId: String): SodBaselineStatusSummary? {
        val response = httpClient.get("$baseUrl/api/v1/risk/sod-snapshot/$bookId/status")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: SodBaselineStatusClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun createSodSnapshot(bookId: String, jobId: String?): SodBaselineStatusSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/sod-snapshot/$bookId") {
            contentType(ContentType.Application.Json)
            if (jobId != null) {
                url.parameters.append("jobId", jobId)
            }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: SodBaselineStatusClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun resetSodBaseline(bookId: String) {
        httpClient.delete("$baseUrl/api/v1/risk/sod-snapshot/$bookId")
    }

    override suspend fun computePnlAttribution(bookId: String): PnlAttributionSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/pnl-attribution/$bookId/compute") {
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: PnlAttributionClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun runWhatIf(params: WhatIfRequestParams): WhatIfResultSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/what-if/${params.bookId}") {
            contentType(ContentType.Application.Json)
            setBody(
                WhatIfRequestClientDto(
                    hypotheticalTrades = params.hypotheticalTrades.map {
                        HypotheticalTradeClientDto(
                            instrumentId = it.instrumentId,
                            assetClass = it.assetClass,
                            side = it.side,
                            quantity = it.quantity,
                            priceAmount = it.priceAmount,
                            priceCurrency = it.priceCurrency,
                            instrumentType = it.instrumentType,
                        )
                    },
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                )
            )
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: WhatIfResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun runRebalancing(params: RebalancingRequestParams): RebalancingWhatIfResultSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/what-if/${params.bookId}/rebalance") {
            contentType(ContentType.Application.Json)
            setBody(
                RebalancingRequestClientDto(
                    trades = params.trades.map {
                        RebalancingTradeClientDto(
                            instrumentId = it.instrumentId,
                            assetClass = it.assetClass,
                            side = it.side,
                            quantity = it.quantity,
                            priceAmount = it.priceAmount,
                            priceCurrency = it.priceCurrency,
                            instrumentType = it.instrumentType,
                            bidAskSpreadBps = it.bidAskSpreadBps,
                        )
                    },
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                )
            )
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: RebalancingWhatIfResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getPositionRisk(bookId: String, valuationDate: String?): List<PositionRiskSummaryItem>? {
        val response = httpClient.get("$baseUrl/api/v1/risk/positions/$bookId") {
            if (!valuationDate.isNullOrBlank()) {
                url { parameters.append("valuationDate", valuationDate) }
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dtos: List<PositionRiskClientDto> = response.body()
        return dtos.map { it.toDomain() }
    }

    override suspend fun getPnlAttribution(bookId: String, date: String?): PnlAttributionSummary? {
        val response = httpClient.get("$baseUrl/api/v1/risk/pnl-attribution/$bookId") {
            if (date != null) {
                url { parameters.append("date", date) }
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: PnlAttributionClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun compareRuns(bookId: String, baseJobId: String, targetJobId: String): JsonObject {
        val response = httpClient.post("$baseUrl/api/v1/risk/compare/$bookId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("baseJobId", baseJobId)
                put("targetJobId", targetJobId)
            })
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun compareDayOverDay(bookId: String, targetDate: String?, baseDate: String?): JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/compare/$bookId/day-over-day") {
            url {
                if (targetDate != null) parameters.append("targetDate", targetDate)
                if (baseDate != null) parameters.append("baseDate", baseDate)
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun compareDayOverDayAttribution(bookId: String, targetDate: String?, baseDate: String?): JsonObject {
        val response = httpClient.post("$baseUrl/api/v1/risk/compare/$bookId/day-over-day/attribution") {
            contentType(ContentType.Application.Json)
            url {
                if (targetDate != null) parameters.append("targetDate", targetDate)
                if (baseDate != null) parameters.append("baseDate", baseDate)
            }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun compareModel(bookId: String, request: JsonObject): JsonObject {
        val response = httpClient.post("$baseUrl/api/v1/risk/compare/$bookId/model") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun promoteJobLabel(jobId: String, body: JsonObject): JsonObject {
        val response = httpClient.patch("$baseUrl/api/v1/risk/jobs/$jobId/label") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getOfficialEod(bookId: String, date: String): JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/jobs/$bookId/official-eod") {
            url { parameters.append("date", date) }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getMarketDataQuantDiff(
        bookId: String,
        dataType: String,
        instrumentId: String,
        baseManifestId: String,
        targetManifestId: String,
    ): JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/compare/$bookId/market-data-quant") {
            url {
                parameters.append("dataType", dataType)
                parameters.append("instrumentId", instrumentId)
                parameters.append("baseManifestId", baseManifestId)
                parameters.append("targetManifestId", targetManifestId)
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getChartData(bookId: String, from: Instant, to: Instant): ChartDataSummary {
        val response = httpClient.get("$baseUrl/api/v1/risk/jobs/$bookId/chart") {
            url {
                parameters.append("from", from.toString())
                parameters.append("to", to.toString())
            }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: ChartDataClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getEodTimeline(bookId: String, from: String, to: String): EodTimelineSummary? {
        val response = httpClient.get("$baseUrl/api/v1/risk/eod-timeline/$bookId") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: EodTimelineClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun calculateCrossBookVaR(
        params: com.kinetix.gateway.dtos.CrossBookVaRCalculationParams,
    ): CrossBookVaRResultSummary? {
        val response = httpClient.post("$baseUrl/api/v1/risk/var/cross-book") {
            contentType(ContentType.Application.Json)
            setBody(
                com.kinetix.gateway.client.dtos.CrossBookVaRRequestClientDto(
                    bookIds = params.bookIds,
                    portfolioGroupId = params.portfolioGroupId,
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                    timeHorizonDays = params.timeHorizonDays.toString(),
                    numSimulations = params.numSimulations.toString(),
                )
            )
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: com.kinetix.gateway.client.dtos.CrossBookVaRResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getCrossBookVaR(groupId: String): CrossBookVaRResultSummary? {
        val response = httpClient.get("$baseUrl/api/v1/risk/var/cross-book/$groupId")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: com.kinetix.gateway.client.dtos.CrossBookVaRResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun calculateStressedCrossBookVaR(
        params: com.kinetix.gateway.dtos.StressedCrossBookVaRParams,
    ): StressedCrossBookVaRResultSummary? {
        val response = httpClient.post("$baseUrl/api/v1/risk/var/cross-book/stressed") {
            contentType(ContentType.Application.Json)
            setBody(
                com.kinetix.gateway.client.dtos.StressedCrossBookVaRRequestClientDto(
                    bookIds = params.bookIds,
                    portfolioGroupId = params.portfolioGroupId,
                    stressCorrelation = params.stressCorrelation,
                    calculationType = params.calculationType,
                    confidenceLevel = params.confidenceLevel,
                    timeHorizonDays = params.timeHorizonDays.toString(),
                    numSimulations = params.numSimulations.toString(),
                )
            )
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: com.kinetix.gateway.client.dtos.StressedCrossBookVaRResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getIntradayPnl(bookId: String, from: String, to: String): JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/pnl/intraday/$bookId") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status == HttpStatusCode.BadRequest) {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalArgumentException(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun calculateLiquidityRisk(bookId: String, baseVar: Double): JsonObject? {
        val response = httpClient.post("$baseUrl/api/v1/books/$bookId/liquidity-risk") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("baseVar", baseVar) })
        }
        if (response.status == HttpStatusCode.NoContent) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getLatestLiquidityRisk(bookId: String): JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/books/$bookId/liquidity-risk/latest")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getLiquidityRiskHistory(bookId: String, limit: Int): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/books/$bookId/liquidity-risk") {
            url { parameters.append("limit", limit.toString()) }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getLatestFactorRisk(bookId: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/books/$bookId/factor-risk/latest")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getFactorRiskHistory(bookId: String, limit: Int): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/books/$bookId/factor-risk") {
            url { parameters.append("limit", limit.toString()) }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun runHistoricalReplay(params: HistoricalReplayParams): HistoricalReplayResultSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/stress/${params.bookId}/historical-replay") {
            contentType(ContentType.Application.Json)
            setBody(
                HistoricalReplayRequestClientDto(
                    instrumentReturns = params.instrumentReturns.map {
                        InstrumentDailyReturnsClientDto(
                            instrumentId = it.instrumentId,
                            dailyReturns = it.dailyReturns,
                        )
                    },
                    scenarioName = params.scenarioName,
                    windowStart = params.windowStart,
                    windowEnd = params.windowEnd,
                )
            )
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: HistoricalReplayResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun runReverseStress(params: ReverseStressParams): ReverseStressResultSummary {
        val response = httpClient.post("$baseUrl/api/v1/risk/stress/${params.bookId}/reverse") {
            contentType(ContentType.Application.Json)
            setBody(
                ReverseStressRequestClientDto(
                    targetLoss = params.targetLoss,
                    maxShock = params.maxShock,
                )
            )
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        val dto: ReverseStressResultClientDto = response.body()
        return dto.toDomain()
    }

    override suspend fun getHierarchyRisk(level: String, entityId: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/hierarchy/$level/$entityId")
        if (response.status == io.ktor.http.HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getCurrentRegime(): kotlinx.serialization.json.JsonObject {
        val response = httpClient.get("$baseUrl/api/v1/risk/regime/current")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getRiskBudgets(level: String?, entityId: String?): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/risk/budgets") {
            url {
                if (level != null) parameters.append("level", level)
                if (entityId != null) parameters.append("entityId", entityId)
            }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getRegimeHistory(limit: Int): kotlinx.serialization.json.JsonObject {
        val response = httpClient.get("$baseUrl/api/v1/risk/regime/history") {
            url { parameters.append("limit", limit.toString()) }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getRiskBudget(id: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/budgets/$id")
        if (response.status == io.ktor.http.HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun createRiskBudget(body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val response = httpClient.post("$baseUrl/api/v1/risk/budgets") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun deleteRiskBudget(id: String): Boolean {
        val response = httpClient.delete("$baseUrl/api/v1/risk/budgets/$id")
        if (response.status == io.ktor.http.HttpStatusCode.NotFound) return false
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return true
    }

    override suspend fun triggerCroReport(): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.post("$baseUrl/api/v1/risk/reports/cro")
        if (!response.status.isSuccess()) return null
        return response.body()
    }

    override suspend fun suggestHedge(bookId: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val response = httpClient.post("$baseUrl/api/v1/risk/hedge-suggest/$bookId") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getLatestHedgeRecommendations(bookId: String, limit: Int): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/risk/hedge-suggest/$bookId") {
            url { parameters.append("limit", limit.toString()) }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getHedgeRecommendation(bookId: String, id: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/hedge-suggest/$bookId/$id")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun acceptHedgeRecommendation(
        bookId: String,
        id: String,
        body: kotlinx.serialization.json.JsonObject,
    ): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.post("$baseUrl/api/v1/risk/hedge-suggest/$bookId/$id/accept") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun rejectHedgeRecommendation(
        bookId: String,
        id: String,
    ): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.post("$baseUrl/api/v1/risk/hedge-suggest/$bookId/$id/reject")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getAllCounterpartyExposures(): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/counterparty-risk/")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getCounterpartyExposure(counterpartyId: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/counterparty-risk/$counterpartyId")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getCounterpartyExposureHistory(counterpartyId: String, limit: Int): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/counterparty-risk/$counterpartyId/history") {
            url { parameters.append("limit", limit.toString()) }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun computeCounterpartyPFE(counterpartyId: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.post("$baseUrl/api/v1/counterparty-risk/$counterpartyId/pfe") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun computeCounterpartyCVA(counterpartyId: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.post("$baseUrl/api/v1/counterparty-risk/$counterpartyId/cva")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getKeyRateDurations(bookId: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/krd/$bookId")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun listReportTemplates(): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/reports/templates")
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun generateReport(body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val response = httpClient.post("$baseUrl/api/v1/reports/generate") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getReportOutput(outputId: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/reports/$outputId")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getReportOutputCsv(outputId: String): String? {
        val response = httpClient.get("$baseUrl/api/v1/reports/$outputId/csv")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.bodyAsText()
    }

    override suspend fun getRecentReports(limit: Int?): kotlinx.serialization.json.JsonArray {
        val response = httpClient.get("$baseUrl/api/v1/reports/recent") {
            if (limit != null) {
                url { parameters.append("limit", limit.toString()) }
            }
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getIntradayVaRTimeline(bookId: String, from: String, to: String): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/var/$bookId/intraday") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status == HttpStatusCode.BadRequest) {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalArgumentException(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getCounterpartySaCcr(
        counterpartyId: String,
        collateral: Double,
    ): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/counterparty/$counterpartyId/sa-ccr") {
            if (collateral != 0.0) {
                url { parameters.append("collateral", collateral.toString()) }
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getBrinsonAttribution(bookId: String, benchmarkId: String, asOfDate: String?): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/books/$bookId/attribution") {
            url {
                parameters.append("benchmarkId", benchmarkId)
                if (asOfDate != null) parameters.append("asOfDate", asOfDate)
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status == HttpStatusCode.BadRequest) {
            val body = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalArgumentException(body)
        }
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    // kx-wxy — canned stress-scenario tile passthroughs.
    override suspend fun runCannedStressScenario(
        bookId: String,
        scenarioName: String,
    ): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.post(
            "$baseUrl/api/v1/risk/stress/$bookId/canned/$scenarioName",
        ) { contentType(ContentType.Application.Json) }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }

    override suspend fun getCannedStressScenario(
        bookId: String,
    ): kotlinx.serialization.json.JsonObject? {
        val response = httpClient.get("$baseUrl/api/v1/risk/stress/$bookId/canned")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) handleErrorResponse(response)
        return response.body()
    }
}
