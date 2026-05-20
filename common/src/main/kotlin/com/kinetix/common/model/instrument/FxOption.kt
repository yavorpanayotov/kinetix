package com.kinetix.common.model.instrument

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.OptionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("FX_OPTION")
data class FxOption(
    val baseCurrency: String,
    val quoteCurrency: String,
    val optionType: OptionType,
    val strike: Double,
    val expiryDate: String,
) : InstrumentType {
    override val instrumentTypeName: String get() = "FX_OPTION"
    override fun assetClass(): AssetClass = AssetClass.FX
}
