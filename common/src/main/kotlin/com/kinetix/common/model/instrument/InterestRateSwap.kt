package com.kinetix.common.model.instrument

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.SwapDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("INTEREST_RATE_SWAP")
data class InterestRateSwap(
    val notional: Double,
    val currency: String,
    val fixedRate: Double,
    val floatIndex: String,
    val floatSpread: Double = 0.0,
    val maturityDate: String,
    val effectiveDate: String,
    val payReceive: SwapDirection,
    val fixedFrequency: Int = 2,
    val floatFrequency: Int = 4,
    val dayCountConvention: String = "ACT/360",
) : InstrumentType {
    override val instrumentTypeName: String get() = "INTEREST_RATE_SWAP"
    override fun assetClass(): AssetClass = AssetClass.DERIVATIVE
}
