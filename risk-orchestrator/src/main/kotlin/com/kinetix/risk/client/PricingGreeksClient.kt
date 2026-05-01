package com.kinetix.risk.client

import com.kinetix.proto.risk.PricingGreekInstrumentInput
import com.kinetix.proto.risk.PricingGreeksRequest
import com.kinetix.proto.risk.RiskCalculationServiceGrpcKt.RiskCalculationServiceCoroutineStub
import java.util.concurrent.TimeUnit

/**
 * Per-instrument input to the pricing-Greek RPC. The orchestrator owns market data
 * fetching (per ADR-0029); the engine receives all inputs via this DTO.
 *
 * Fields are read selectively based on [assetClass]:
 *   OPTION       — strike/expiryDays/impliedVol/riskFreeRate/dividendYield/optionType
 *                  required; spotPrice is the underlying spot.
 *   BOND / SWAP  — faceValue/couponRate/couponFrequency/maturityYears/yieldRate.
 *   EQUITY / FX  — spotPrice only; the engine returns identity delta.
 *
 * Fields irrelevant to the asset class can be left at their defaults.
 */
data class PricingGreeksInstrumentInput(
    val instrumentId: String,
    val assetClass: String,
    val spotPrice: Double = 0.0,
    val strike: Double = 0.0,
    val expiryDays: Int = 0,
    val impliedVol: Double = 0.0,
    val riskFreeRate: Double = 0.0,
    val dividendYield: Double = 0.0,
    val optionType: String = "",
    val faceValue: Double = 0.0,
    val couponRate: Double = 0.0,
    val couponFrequency: Int = 2,
    val maturityYears: Double = 0.0,
    val yieldRate: Double = 0.0,
)

/** Per-instrument analytical pricing Greeks returned by the engine. */
data class PricingGreeksResult(
    val instrumentId: String,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
    val rho: Double,
    val vanna: Double,
    val volga: Double,
    val charm: Double,
    val bondDv01: Double,
    val swapDv01: Double,
)

interface PricingGreeksClient {
    /**
     * Returns analytical pricing Greeks for each instrument, in the same order as the request.
     * Instruments the engine could not price (unknown asset class, math failure) are silently
     * dropped from the response — callers tolerate partial population by falling back to VaR
     * Greeks in the consumer (see IntradayPnlService / PnlComputationService).
     */
    suspend fun calculatePricingGreeks(
        instruments: List<PricingGreeksInstrumentInput>,
    ): List<PricingGreeksResult>
}

class GrpcPricingGreeksClient(
    private val stub: RiskCalculationServiceCoroutineStub,
    private val deadlineMs: Long = 60_000,
) : PricingGreeksClient {

    override suspend fun calculatePricingGreeks(
        instruments: List<PricingGreeksInstrumentInput>,
    ): List<PricingGreeksResult> {
        if (instruments.isEmpty()) return emptyList()

        val protoInstruments = instruments.map { input ->
            PricingGreekInstrumentInput.newBuilder()
                .setInstrumentId(input.instrumentId)
                .setAssetClass(input.assetClass)
                .setSpotPrice(input.spotPrice)
                .setStrike(input.strike)
                .setExpiryDays(input.expiryDays)
                .setImpliedVol(input.impliedVol)
                .setRiskFreeRate(input.riskFreeRate)
                .setDividendYield(input.dividendYield)
                .setOptionType(input.optionType)
                .setFaceValue(input.faceValue)
                .setCouponRate(input.couponRate)
                .setCouponFrequency(input.couponFrequency)
                .setMaturityYears(input.maturityYears)
                .setYieldRate(input.yieldRate)
                .build()
        }

        val request = PricingGreeksRequest.newBuilder()
            .addAllInstruments(protoInstruments)
            .build()

        val response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .calculatePricingGreeks(request)

        return response.resultsList.map { r ->
            PricingGreeksResult(
                instrumentId = r.instrumentId,
                delta = r.delta,
                gamma = r.gamma,
                vega = r.vega,
                theta = r.theta,
                rho = r.rho,
                vanna = r.vanna,
                volga = r.volga,
                charm = r.charm,
                bondDv01 = r.bondDv01,
                swapDv01 = r.swapDv01,
            )
        }
    }
}
