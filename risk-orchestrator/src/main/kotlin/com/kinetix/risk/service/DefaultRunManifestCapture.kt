package com.kinetix.risk.service

import com.kinetix.common.model.Position
import com.kinetix.risk.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class DefaultRunManifestCapture(
    private val manifestRepo: RunManifestRepository,
    private val blobStore: MarketDataBlobStore,
    private val auditPublisher: RiskAuditEventPublisher? = null,
) : RunManifestCapture {

    private val logger = LoggerFactory.getLogger(DefaultRunManifestCapture::class.java)

    override suspend fun captureInputs(
        jobId: UUID,
        request: VaRCalculationRequest,
        positions: List<Position>,
        fetchResults: List<FetchResult>,
        valuationDate: LocalDate,
    ): RunManifest {
        val manifestId = UUID.randomUUID()
        val capturedAt = Instant.now()

        // 1. Build position snapshot entries
        val positionEntries = positions.map { pos ->
            PositionSnapshotEntry(
                instrumentId = pos.instrumentId.value,
                assetClass = pos.assetClass.name,
                quantity = pos.quantity,
                averageCostAmount = pos.averageCost.amount,
                marketPriceAmount = pos.marketPrice.amount,
                currency = pos.currency.currencyCode,
                marketValueAmount = pos.marketValue.amount,
                unrealizedPnlAmount = pos.unrealizedPnl.amount,
                instrumentType = pos.instrumentType.name,
            )
        }

        // 2. Store market data blobs and build refs
        val marketDataRefs = mutableListOf<MarketDataRef>()
        val successValues = mutableListOf<MarketDataValue>()
        var hasFailures = false

        for (fetchResult in fetchResults) {
            when (fetchResult) {
                is FetchSuccess -> {
                    val blob = serializeMarketData(fetchResult.value)
                    val contentHash = InputDigest.digestMarketDataBlob(blob)
                    blobStore.putIfAbsent(
                        contentHash = contentHash,
                        dataType = fetchResult.value.dataType,
                        instrumentId = fetchResult.value.instrumentId,
                        assetClass = fetchResult.value.assetClass,
                        payload = blob,
                    )
                    successValues.add(fetchResult.value)
                    marketDataRefs.add(
                        MarketDataRef(
                            dataType = fetchResult.value.dataType,
                            instrumentId = fetchResult.value.instrumentId,
                            assetClass = fetchResult.value.assetClass,
                            contentHash = contentHash,
                            status = MarketDataSnapshotStatus.FETCHED,
                            sourceService = resolveServiceName(fetchResult.value.dataType),
                            sourcedAt = capturedAt,
                        )
                    )
                }
                is FetchFailure -> {
                    hasFailures = true
                    marketDataRefs.add(
                        MarketDataRef(
                            dataType = fetchResult.dependency.dataType,
                            instrumentId = fetchResult.dependency.instrumentId,
                            assetClass = fetchResult.dependency.assetClass,
                            contentHash = "",
                            status = MarketDataSnapshotStatus.MISSING,
                            sourceService = fetchResult.service,
                            sourcedAt = fetchResult.timestamp,
                        )
                    )
                }
            }
        }

        // 3. Compute digests (model version unknown at this stage — use empty string)
        val positionDigest = InputDigest.digestPositions(positions)
        val marketDataDigest = InputDigest.digestMarketData(successValues)
        val inputDigest = InputDigest.digestInputs(request, positionDigest, marketDataDigest, "")

        val status = if (hasFailures) ManifestStatus.PARTIAL else ManifestStatus.INPUTS_FROZEN

        val manifest = RunManifest(
            manifestId = manifestId,
            jobId = jobId,
            bookId = request.bookId.value,
            valuationDate = valuationDate,
            capturedAt = capturedAt,
            modelVersion = "",
            calculationType = request.calculationType.name,
            confidenceLevel = request.confidenceLevel.name,
            timeHorizonDays = request.timeHorizonDays,
            numSimulations = request.numSimulations,
            monteCarloSeed = request.monteCarloSeed,
            positionCount = positions.size,
            positionDigest = positionDigest,
            marketDataDigest = marketDataDigest,
            inputDigest = inputDigest,
            status = status,
        )

        // 4. Persist
        manifestRepo.save(manifest)
        manifestRepo.savePositionSnapshot(manifestId, positionEntries)
        if (marketDataRefs.isNotEmpty()) {
            manifestRepo.saveMarketDataRefs(manifestId, marketDataRefs)
        }

        logger.info(
            "Run manifest {} inputs captured for job {} ({} positions, {} market data refs, status={})",
            manifestId, jobId, positions.size, marketDataRefs.size, status,
        )

        return manifest
    }

    override suspend fun finaliseOutputs(
        manifestId: UUID,
        modelVersion: String,
        varValue: Double?,
        expectedShortfall: Double?,
        componentBreakdown: List<ComponentBreakdown>,
    ) {
        // 1. Load the manifest to get the digests for recomputation
        val manifest = manifestRepo.findByManifestId(manifestId)
        if (manifest == null) {
            logger.warn("Cannot finalise manifest {} — not found", manifestId)
            return
        }

        // 2. Recompute input digest with the real model version
        val request = VaRCalculationRequest(
            bookId = com.kinetix.common.model.BookId(manifest.bookId),
            calculationType = CalculationType.valueOf(manifest.calculationType),
            confidenceLevel = ConfidenceLevel.valueOf(manifest.confidenceLevel),
            timeHorizonDays = manifest.timeHorizonDays,
            numSimulations = manifest.numSimulations,
            monteCarloSeed = manifest.monteCarloSeed,
        )
        val finalInputDigest = InputDigest.digestInputs(
            request, manifest.positionDigest, manifest.marketDataDigest, modelVersion,
        )

        // 3. Compute output digest
        val outputDigest = InputDigest.digestOutputs(varValue, expectedShortfall)

        // 4. Determine final status
        val finalStatus = if (manifest.status == ManifestStatus.PARTIAL) {
            ManifestStatus.PARTIAL
        } else {
            ManifestStatus.COMPLETE
        }

        // 5. Persist the finalisation
        manifestRepo.finaliseManifest(
            manifestId = manifestId,
            modelVersion = modelVersion,
            varValue = varValue,
            expectedShortfall = expectedShortfall,
            outputDigest = outputDigest,
            inputDigest = finalInputDigest,
            status = finalStatus,
        )

        logger.info(
            "Run manifest {} finalised for job {}: modelVersion={}, VaR={}, ES={}, status={}",
            manifestId, manifest.jobId, modelVersion, varValue, expectedShortfall, finalStatus,
        )

        // 6. Emit audit event with final accurate data
        try {
            auditPublisher?.publish(
                ManifestFrozenEvent(
                    jobId = manifest.jobId.toString(),
                    bookId = manifest.bookId,
                    valuationDate = manifest.valuationDate.toString(),
                    manifestId = manifestId.toString(),
                    capturedAt = manifest.capturedAt.toString(),
                    positionCount = manifest.positionCount,
                    positionDigest = manifest.positionDigest,
                    marketDataDigest = manifest.marketDataDigest,
                    inputDigest = finalInputDigest,
                    modelVersion = modelVersion,
                    calculationType = manifest.calculationType,
                    status = finalStatus.name,
                )
            )
        } catch (e: Exception) {
            logger.warn("Failed to publish RISK_RUN_MANIFEST_FROZEN audit event for manifest {}", manifestId, e)
        }
    }

    private fun serializeMarketData(value: MarketDataValue): String = when (value) {
        is ScalarMarketData -> Json.encodeToString(buildJsonObject {
            put("dataType", value.dataType)
            put("instrumentId", value.instrumentId)
            put("assetClass", value.assetClass)
            put("value", value.value)
        })
        is TimeSeriesMarketData -> Json.encodeToString(buildJsonObject {
            put("dataType", value.dataType)
            put("instrumentId", value.instrumentId)
            put("assetClass", value.assetClass)
            putJsonArray("points") {
                for (p in value.points) {
                    add(buildJsonObject {
                        put("timestamp", p.timestamp.toString())
                        put("value", p.value)
                    })
                }
            }
        })
        is CurveMarketData -> Json.encodeToString(buildJsonObject {
            put("dataType", value.dataType)
            put("instrumentId", value.instrumentId)
            put("assetClass", value.assetClass)
            putJsonArray("points") {
                for (p in value.points) {
                    add(buildJsonObject {
                        put("tenor", p.tenor)
                        put("value", p.value)
                    })
                }
            }
        })
        is MatrixMarketData -> Json.encodeToString(buildJsonObject {
            put("dataType", value.dataType)
            put("instrumentId", value.instrumentId)
            put("assetClass", value.assetClass)
            putJsonArray("rows") { value.rows.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("columns") { value.columns.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("values") { value.values.forEach { add(JsonPrimitive(it)) } }
        })
    }

    private fun resolveServiceName(dataType: String): String = when (dataType) {
        "SPOT_PRICE", "HISTORICAL_PRICES" -> "price-service"
        "YIELD_CURVE", "RISK_FREE_RATE", "FORWARD_CURVE" -> "rates-service"
        "DIVIDEND_YIELD", "CREDIT_SPREAD" -> "reference-data-service"
        "VOLATILITY_SURFACE" -> "volatility-service"
        "CORRELATION_MATRIX" -> "correlation-service"
        else -> "unknown"
    }
}
