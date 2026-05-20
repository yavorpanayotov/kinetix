package com.kinetix.risk.mapper

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.*
import com.kinetix.risk.client.dtos.InstrumentDto
import com.kinetix.proto.common.AssetClass as ProtoAssetClass
import com.kinetix.proto.common.BondAttributes as ProtoBondAttributes
import com.kinetix.proto.common.FutureAttributes as ProtoFutureAttributes
import com.kinetix.proto.common.FxAttributes as ProtoFxAttributes
import com.kinetix.proto.common.InstrumentId as ProtoInstrumentId
import com.kinetix.proto.common.InstrumentTypeEnum as ProtoInstrumentTypeEnum
import com.kinetix.proto.common.Money as ProtoMoney
import com.kinetix.proto.common.OptionAttributes as ProtoOptionAttributes
import com.kinetix.proto.common.BookId as ProtoBookId
import com.kinetix.proto.common.Position as ProtoPosition
import com.kinetix.proto.common.SwapAttributes as ProtoSwapAttributes

private val ASSET_CLASS_TO_PROTO = mapOf(
    AssetClass.EQUITY to ProtoAssetClass.EQUITY,
    AssetClass.FIXED_INCOME to ProtoAssetClass.FIXED_INCOME,
    AssetClass.FX to ProtoAssetClass.FX,
    AssetClass.COMMODITY to ProtoAssetClass.COMMODITY,
    AssetClass.DERIVATIVE to ProtoAssetClass.DERIVATIVE,
)

private val INSTRUMENT_TYPE_NAME_TO_PROTO = mapOf(
    "CASH_EQUITY" to ProtoInstrumentTypeEnum.CASH_EQUITY,
    "EQUITY_OPTION" to ProtoInstrumentTypeEnum.EQUITY_OPTION,
    "EQUITY_FUTURE" to ProtoInstrumentTypeEnum.EQUITY_FUTURE,
    "GOVERNMENT_BOND" to ProtoInstrumentTypeEnum.GOVERNMENT_BOND,
    "CORPORATE_BOND" to ProtoInstrumentTypeEnum.CORPORATE_BOND,
    "INTEREST_RATE_SWAP" to ProtoInstrumentTypeEnum.INTEREST_RATE_SWAP,
    "FX_SPOT" to ProtoInstrumentTypeEnum.FX_SPOT,
    "FX_FORWARD" to ProtoInstrumentTypeEnum.FX_FORWARD,
    "FX_OPTION" to ProtoInstrumentTypeEnum.FX_OPTION,
    "COMMODITY_FUTURE" to ProtoInstrumentTypeEnum.COMMODITY_FUTURE,
    "COMMODITY_OPTION" to ProtoInstrumentTypeEnum.COMMODITY_OPTION,
)

fun Position.toProto(): ProtoPosition = ProtoPosition.newBuilder()
    .setBookId(ProtoBookId.newBuilder().setValue(bookId.value))
    .setInstrumentId(ProtoInstrumentId.newBuilder().setValue(instrumentId.value))
    .setAssetClass(ASSET_CLASS_TO_PROTO.getValue(assetClass))
    .setQuantity(quantity.toDouble())
    .setMarketValue(
        ProtoMoney.newBuilder()
            .setAmount(marketValue.amount.toPlainString())
            .setCurrency(marketValue.currency.currencyCode)
    )
    .build()

fun Position.toProto(instrument: InstrumentDto?): ProtoPosition {
    val builder = ProtoPosition.newBuilder()
        .setBookId(ProtoBookId.newBuilder().setValue(bookId.value))
        .setInstrumentId(ProtoInstrumentId.newBuilder().setValue(instrumentId.value))
        .setAssetClass(ASSET_CLASS_TO_PROTO.getValue(assetClass))
        .setQuantity(quantity.toDouble())
        .setMarketValue(
            ProtoMoney.newBuilder()
                .setAmount(marketValue.amount.toPlainString())
                .setCurrency(marketValue.currency.currencyCode)
        )

    if (instrument == null) return builder.build()

    val protoType = INSTRUMENT_TYPE_NAME_TO_PROTO[instrument.instrumentType]
    if (protoType != null) {
        builder.setInstrumentType(protoType)
    }

    val instType = instrument.toInstrumentType()
    when (instType) {
        is EquityOption -> builder.setOptionAttrs(instType.toProtoOptionAttrs())
        is FxOption -> builder.setOptionAttrs(instType.toProtoOptionAttrs())
        is CommodityOption -> builder.setOptionAttrs(instType.toProtoOptionAttrs())
        is GovernmentBond -> builder.setBondAttrs(instType.toProtoBondAttrs())
        is CorporateBond -> builder.setBondAttrs(instType.toProtoBondAttrs())
        is EquityFuture -> builder.setFutureAttrs(instType.toProtoFutureAttrs())
        is CommodityFuture -> builder.setFutureAttrs(instType.toProtoFutureAttrs())
        is FxSpot -> builder.setFxAttrs(instType.toProtoFxAttrs())
        is FxForward -> builder.setFxAttrs(instType.toProtoFxAttrs())
        is InterestRateSwap -> builder.setSwapAttrs(instType.toProtoSwapAttrs())
        is CashEquity -> {}
    }

    return builder.build()
}

private fun EquityOption.toProtoOptionAttrs() = ProtoOptionAttributes.newBuilder()
    .setUnderlyingId(underlyingId)
    .setOptionType(optionType.name)
    .setStrike(strike)
    .setExpiryDate(expiryDate)
    .setExerciseStyle(exerciseStyle.name)
    .setContractMultiplier(contractMultiplier)
    .setDividendYield(dividendYield)
    .build()

private fun FxOption.toProtoOptionAttrs() = ProtoOptionAttributes.newBuilder()
    .setUnderlyingId("${baseCurrency}${quoteCurrency}")
    .setOptionType(optionType.name)
    .setStrike(strike)
    .setExpiryDate(expiryDate)
    .setContractMultiplier(1.0)
    .build()

private fun CommodityOption.toProtoOptionAttrs() = ProtoOptionAttributes.newBuilder()
    .setUnderlyingId(underlyingId)
    .setOptionType(optionType.name)
    .setStrike(strike)
    .setExpiryDate(expiryDate)
    .setContractMultiplier(contractMultiplier)
    .build()

private fun GovernmentBond.toProtoBondAttrs() = ProtoBondAttributes.newBuilder()
    .setFaceValue(faceValue)
    .setCouponRate(couponRate)
    .setCouponFrequency(couponFrequency)
    .setMaturityDate(maturityDate)
    .setDayCountConvention(dayCountConvention ?: "")
    .build()

private fun CorporateBond.toProtoBondAttrs() = ProtoBondAttributes.newBuilder()
    .setFaceValue(faceValue)
    .setCouponRate(couponRate)
    .setCouponFrequency(couponFrequency)
    .setMaturityDate(maturityDate)
    .setIssuer(issuer)
    .setCreditRating(creditRating ?: "")
    .setSeniority(seniority?.name ?: "")
    .setDayCountConvention(dayCountConvention ?: "")
    .build()

private fun EquityFuture.toProtoFutureAttrs() = ProtoFutureAttributes.newBuilder()
    .setUnderlyingId(underlyingId)
    .setExpiryDate(expiryDate)
    .setContractSize(contractSize)
    .build()

private fun CommodityFuture.toProtoFutureAttrs() = ProtoFutureAttributes.newBuilder()
    .setUnderlyingId(commodity)
    .setExpiryDate(expiryDate)
    .setContractSize(contractSize)
    .build()

private fun FxSpot.toProtoFxAttrs() = ProtoFxAttributes.newBuilder()
    .setBaseCurrency(baseCurrency)
    .setQuoteCurrency(quoteCurrency)
    .build()

private fun FxForward.toProtoFxAttrs() = ProtoFxAttributes.newBuilder()
    .setBaseCurrency(baseCurrency)
    .setQuoteCurrency(quoteCurrency)
    .setDeliveryDate(deliveryDate)
    .setForwardRate(forwardRate ?: 0.0)
    .build()

private fun InterestRateSwap.toProtoSwapAttrs() = ProtoSwapAttributes.newBuilder()
    .setNotional(notional)
    .setFixedRate(fixedRate)
    .setFloatIndex(floatIndex)
    .setFloatSpread(floatSpread)
    .setEffectiveDate(effectiveDate)
    .setMaturityDate(maturityDate)
    .setPayReceive(payReceive.name)
    .setFixedFrequency(fixedFrequency)
    .setFloatFrequency(floatFrequency)
    .setDayCountConvention(dayCountConvention)
    .build()
