package com.kinetix.risk.mapper

import com.kinetix.common.model.*
import com.kinetix.risk.client.dtos.InstrumentDto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.util.Currency
import com.kinetix.proto.common.InstrumentTypeEnum as ProtoInstrumentTypeEnum

private val USD = Currency.getInstance("USD")

private fun instrumentDto(
    instrumentId: String,
    instrumentType: String,
    attributes: String = "{}",
) = InstrumentDto(
    instrumentId = instrumentId,
    instrumentType = instrumentType,
    displayName = instrumentId,
    assetClass = "EQUITY",
    currency = "USD",
    attributes = Json.decodeFromString<JsonObject>(attributes),
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z",
)

class PositionMapperTest : FunSpec({

    test("maps domain position to proto position with correct fields") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = Money(BigDecimal("150.00"), USD),
            marketPrice = Money(BigDecimal("170.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )

        val proto = position.toProto()

        proto.bookId.value shouldBe "port-1"
        proto.instrumentId.value shouldBe "AAPL"
        proto.assetClass shouldBe com.kinetix.proto.common.AssetClass.EQUITY
        proto.quantity shouldBe 100.0
        proto.marketValue.amount shouldBe "17000.00"
        proto.marketValue.currency shouldBe "USD"
    }

    test("maps all asset classes correctly") {
        val assetClassMappings = mapOf(
            AssetClass.EQUITY to com.kinetix.proto.common.AssetClass.EQUITY,
            AssetClass.FIXED_INCOME to com.kinetix.proto.common.AssetClass.FIXED_INCOME,
            AssetClass.FX to com.kinetix.proto.common.AssetClass.FX,
            AssetClass.COMMODITY to com.kinetix.proto.common.AssetClass.COMMODITY,
            AssetClass.DERIVATIVE to com.kinetix.proto.common.AssetClass.DERIVATIVE,
        )

        for ((domain, proto) in assetClassMappings) {
            val position = Position(
                bookId = BookId("port-1"),
                instrumentId = InstrumentId("TEST"),
                assetClass = domain,
                quantity = BigDecimal("10"),
                averageCost = Money(BigDecimal("100.00"), USD),
                marketPrice = Money(BigDecimal("100.00"), USD),
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
            )
            position.toProto().assetClass shouldBe proto
        }
    }

    test("maps position with negative quantity") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("-50"),
            averageCost = Money(BigDecimal("150.00"), USD),
            marketPrice = Money(BigDecimal("170.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )

        val proto = position.toProto()
        proto.quantity shouldBe -50.0
        proto.marketValue.amount shouldBe "-8500.00"
    }

    test("maps equity option to proto with option_attrs") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("AAPL-C-150"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("10"),
            averageCost = Money(BigDecimal("5.00"), USD),
            marketPrice = Money(BigDecimal("8.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val instrument = instrumentDto(
            "AAPL-C-150",
            "EQUITY_OPTION",
            """{"type":"EQUITY_OPTION","underlyingId":"AAPL","optionType":"CALL","strike":150.0,"expiryDate":"2026-06-20","exerciseStyle":"AMERICAN","contractMultiplier":100.0,"dividendYield":0.01}""",
        )

        val proto = position.toProto(instrument)

        proto.instrumentType shouldBe ProtoInstrumentTypeEnum.EQUITY_OPTION
        proto.optionAttrs.underlyingId shouldBe "AAPL"
        proto.optionAttrs.strike shouldBe 150.0
        proto.optionAttrs.optionType shouldBe "CALL"
        proto.optionAttrs.expiryDate shouldBe "2026-06-20"
        proto.optionAttrs.contractMultiplier shouldBe 100.0
        proto.optionAttrs.dividendYield shouldBe 0.01
    }

    test("maps government bond to proto with bond_attrs") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("UST-10Y"),
            assetClass = AssetClass.FIXED_INCOME,
            quantity = BigDecimal("100"),
            averageCost = Money(BigDecimal("98.00"), USD),
            marketPrice = Money(BigDecimal("99.50"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val instrument = instrumentDto(
            "UST-10Y",
            "GOVERNMENT_BOND",
            """{"type":"GOVERNMENT_BOND","currency":"USD","couponRate":0.025,"couponFrequency":2,"maturityDate":"2036-03-15","faceValue":1000.0}""",
        )

        val proto = position.toProto(instrument)

        proto.instrumentType shouldBe ProtoInstrumentTypeEnum.GOVERNMENT_BOND
        proto.bondAttrs.couponRate shouldBe 0.025
        proto.bondAttrs.faceValue shouldBe 1000.0
        proto.bondAttrs.maturityDate shouldBe "2036-03-15"
    }

    test("maps interest rate swap to proto with swap_attrs") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("IRS-5Y"),
            assetClass = AssetClass.DERIVATIVE,
            quantity = BigDecimal("1"),
            averageCost = Money(BigDecimal("0.00"), USD),
            marketPrice = Money(BigDecimal("25000.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )
        val instrument = instrumentDto(
            "IRS-5Y",
            "INTEREST_RATE_SWAP",
            """{"type":"INTEREST_RATE_SWAP","notional":10000000.0,"currency":"USD","fixedRate":0.035,"floatIndex":"SOFR","floatSpread":0.001,"maturityDate":"2031-03-15","effectiveDate":"2026-03-15","payReceive":"PAY_FIXED","fixedFrequency":2,"floatFrequency":4,"dayCountConvention":"ACT/360"}""",
        )

        val proto = position.toProto(instrument)

        proto.instrumentType shouldBe ProtoInstrumentTypeEnum.INTEREST_RATE_SWAP
        proto.swapAttrs.notional shouldBe 10000000.0
        proto.swapAttrs.fixedRate shouldBe 0.035
        proto.swapAttrs.floatIndex shouldBe "SOFR"
        proto.swapAttrs.payReceive shouldBe "PAY_FIXED"
    }

    test("maps null instrument to basic proto without instrument type") {
        val position = Position(
            bookId = BookId("port-1"),
            instrumentId = InstrumentId("UNKNOWN"),
            assetClass = AssetClass.EQUITY,
            quantity = BigDecimal("100"),
            averageCost = Money(BigDecimal("100.00"), USD),
            marketPrice = Money(BigDecimal("100.00"), USD),
            instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
        )

        val proto = position.toProto(null)

        proto.instrumentType shouldBe ProtoInstrumentTypeEnum.INSTRUMENT_TYPE_UNSPECIFIED
        proto.instrumentId.value shouldBe "UNKNOWN"
        proto.quantity shouldBe 100.0
    }
})
