package com.kinetix.common.model.instrument

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.OptionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("COMMODITY_OPTION")
data class CommodityOption(
    val underlyingId: String,
    val optionType: OptionType,
    val strike: Double,
    val expiryDate: String,
    val contractMultiplier: Double = 1.0,
) : InstrumentType {
    override val instrumentTypeName: String get() = "COMMODITY_OPTION"
    override fun assetClass(): AssetClass = AssetClass.COMMODITY
}
