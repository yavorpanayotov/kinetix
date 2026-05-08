package com.kinetix.risk.model

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Side
import com.kinetix.common.model.instrument.InstrumentTypeCode
import java.math.BigDecimal

data class RebalancingTrade(
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val side: Side,
    val quantity: BigDecimal,
    val price: Money,
    val instrumentType: InstrumentTypeCode,
    val bidAskSpreadBps: Double = 5.0,
)
