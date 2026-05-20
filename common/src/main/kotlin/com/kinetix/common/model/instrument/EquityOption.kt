package com.kinetix.common.model.instrument

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.ExerciseStyle
import com.kinetix.common.model.OptionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("EQUITY_OPTION")
data class EquityOption(
    val underlyingId: String,
    val optionType: OptionType,
    val strike: Double,
    val expiryDate: String,
    val exerciseStyle: ExerciseStyle,
    val contractMultiplier: Double = 100.0,
    val dividendYield: Double = 0.0,
) : InstrumentType {
    override val instrumentTypeName: String get() = "EQUITY_OPTION"
    override fun assetClass(): AssetClass = AssetClass.EQUITY
}
