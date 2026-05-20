package com.kinetix.common.model.instrument

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BondSeniority
import com.kinetix.common.model.ExerciseStyle
import com.kinetix.common.model.OptionType
import com.kinetix.common.model.SwapDirection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InstrumentTypeTest : FunSpec({

    val json = Json { prettyPrint = false }

    test("CashEquity derives EQUITY asset class") {
        val inst = CashEquity(currency = "USD", exchange = "NYSE", sector = "Technology", countryCode = "US")
        inst.assetClass() shouldBe AssetClass.EQUITY
        inst.instrumentTypeName shouldBe "CASH_EQUITY"
    }

    test("EquityOption derives EQUITY asset class") {
        val inst = EquityOption(
            underlyingId = "AAPL",
            optionType = OptionType.CALL,
            strike = 150.0,
            expiryDate = "2026-06-20",
            exerciseStyle = ExerciseStyle.EUROPEAN,
            contractMultiplier = 100.0,
            dividendYield = 0.005,
        )
        inst.assetClass() shouldBe AssetClass.EQUITY
        inst.instrumentTypeName shouldBe "EQUITY_OPTION"
    }

    test("EquityFuture derives EQUITY asset class") {
        val inst = EquityFuture(underlyingId = "SPX", expiryDate = "2026-06-20", contractSize = 50.0, currency = "USD")
        inst.assetClass() shouldBe AssetClass.EQUITY
        inst.instrumentTypeName shouldBe "EQUITY_FUTURE"
    }

    test("GovernmentBond derives FIXED_INCOME asset class") {
        val inst = GovernmentBond(
            currency = "USD",
            couponRate = 0.025,
            couponFrequency = 2,
            maturityDate = "2036-05-15",
            faceValue = 1000.0,
            dayCountConvention = "ACT/ACT",
        )
        inst.assetClass() shouldBe AssetClass.FIXED_INCOME
        inst.instrumentTypeName shouldBe "GOVERNMENT_BOND"
    }

    test("CorporateBond derives FIXED_INCOME asset class") {
        val inst = CorporateBond(
            currency = "USD",
            couponRate = 0.045,
            couponFrequency = 2,
            maturityDate = "2031-03-15",
            faceValue = 1000.0,
            issuer = "JPM",
            creditRating = "A",
            seniority = BondSeniority.SENIOR_UNSECURED,
        )
        inst.assetClass() shouldBe AssetClass.FIXED_INCOME
        inst.instrumentTypeName shouldBe "CORPORATE_BOND"
    }

    test("InterestRateSwap derives DERIVATIVE asset class") {
        val inst = InterestRateSwap(
            notional = 10_000_000.0,
            currency = "USD",
            fixedRate = 0.035,
            floatIndex = "SOFR",
            maturityDate = "2031-06-15",
            effectiveDate = "2026-06-15",
            payReceive = SwapDirection.PAY_FIXED,
        )
        inst.assetClass() shouldBe AssetClass.DERIVATIVE
        inst.instrumentTypeName shouldBe "INTEREST_RATE_SWAP"
    }

    test("FxSpot derives FX asset class") {
        val inst = FxSpot(baseCurrency = "EUR", quoteCurrency = "USD")
        inst.assetClass() shouldBe AssetClass.FX
        inst.instrumentTypeName shouldBe "FX_SPOT"
    }

    test("FxForward derives FX asset class") {
        val inst = FxForward(baseCurrency = "GBP", quoteCurrency = "USD", deliveryDate = "2026-09-15", forwardRate = 1.28)
        inst.assetClass() shouldBe AssetClass.FX
        inst.instrumentTypeName shouldBe "FX_FORWARD"
    }

    test("FxOption derives FX asset class") {
        val inst = FxOption(baseCurrency = "EUR", quoteCurrency = "USD", optionType = OptionType.PUT, strike = 1.10, expiryDate = "2026-09-15")
        inst.assetClass() shouldBe AssetClass.FX
        inst.instrumentTypeName shouldBe "FX_OPTION"
    }

    test("CommodityFuture derives COMMODITY asset class") {
        val inst = CommodityFuture(commodity = "WTI", expiryDate = "2026-08-20", contractSize = 1000.0, currency = "USD")
        inst.assetClass() shouldBe AssetClass.COMMODITY
        inst.instrumentTypeName shouldBe "COMMODITY_FUTURE"
    }

    test("CommodityOption derives COMMODITY asset class") {
        val inst = CommodityOption(underlyingId = "GC", optionType = OptionType.CALL, strike = 2000.0, expiryDate = "2026-08-20")
        inst.assetClass() shouldBe AssetClass.COMMODITY
        inst.instrumentTypeName shouldBe "COMMODITY_OPTION"
    }

    test("serialization round-trip preserves CashEquity") {
        val original: InstrumentType = CashEquity(currency = "USD", exchange = "NYSE", sector = "Tech", countryCode = "US")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves EquityOption") {
        val original: InstrumentType = EquityOption(
            underlyingId = "AAPL",
            optionType = OptionType.CALL,
            strike = 150.0,
            expiryDate = "2026-06-20",
            exerciseStyle = ExerciseStyle.EUROPEAN,
            contractMultiplier = 100.0,
            dividendYield = 0.005,
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves EquityFuture") {
        val original: InstrumentType = EquityFuture(underlyingId = "SPX", expiryDate = "2026-06-20", contractSize = 50.0, currency = "USD")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves GovernmentBond") {
        val original: InstrumentType = GovernmentBond(
            currency = "USD", couponRate = 0.025, couponFrequency = 2,
            maturityDate = "2036-05-15", faceValue = 1000.0, dayCountConvention = "ACT/ACT",
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves CorporateBond") {
        val original: InstrumentType = CorporateBond(
            currency = "USD", couponRate = 0.045, couponFrequency = 2,
            maturityDate = "2031-03-15", faceValue = 1000.0, issuer = "JPM",
            creditRating = "A", seniority = BondSeniority.SENIOR_UNSECURED,
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves InterestRateSwap") {
        val original: InstrumentType = InterestRateSwap(
            notional = 10_000_000.0, currency = "USD", fixedRate = 0.035,
            floatIndex = "SOFR", maturityDate = "2031-06-15", effectiveDate = "2026-06-15",
            payReceive = SwapDirection.PAY_FIXED, fixedFrequency = 2, floatFrequency = 4,
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves FxSpot") {
        val original: InstrumentType = FxSpot(baseCurrency = "EUR", quoteCurrency = "USD")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves FxForward") {
        val original: InstrumentType = FxForward(baseCurrency = "GBP", quoteCurrency = "USD", deliveryDate = "2026-09-15", forwardRate = 1.28)
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves FxOption") {
        val original: InstrumentType = FxOption(baseCurrency = "EUR", quoteCurrency = "USD", optionType = OptionType.PUT, strike = 1.10, expiryDate = "2026-09-15")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves CommodityFuture") {
        val original: InstrumentType = CommodityFuture(commodity = "WTI", expiryDate = "2026-08-20", contractSize = 1000.0, currency = "USD")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialization round-trip preserves CommodityOption") {
        val original: InstrumentType = CommodityOption(underlyingId = "GC", optionType = OptionType.CALL, strike = 2000.0, expiryDate = "2026-08-20")
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<InstrumentType>(serialized)
        deserialized shouldBe original
    }

    test("serialized JSON contains type discriminator") {
        val inst: InstrumentType = CashEquity(currency = "USD")
        val serialized = json.encodeToString(inst)
        serialized.contains("\"type\":\"CASH_EQUITY\"") shouldBe true
    }

    test("all 11 instrument types have unique names") {
        val types: List<InstrumentType> = listOf(
            CashEquity(currency = "USD"),
            EquityOption(underlyingId = "X", optionType = OptionType.CALL, strike = 1.0, expiryDate = "2026-01-01", exerciseStyle = ExerciseStyle.EUROPEAN),
            EquityFuture(underlyingId = "X", expiryDate = "2026-01-01", contractSize = 1.0, currency = "USD"),
            GovernmentBond(currency = "USD", couponRate = 0.02, couponFrequency = 2, maturityDate = "2036-01-01", faceValue = 1000.0),
            CorporateBond(currency = "USD", couponRate = 0.04, couponFrequency = 2, maturityDate = "2031-01-01", faceValue = 1000.0, issuer = "X"),
            InterestRateSwap(notional = 1e6, currency = "USD", fixedRate = 0.03, floatIndex = "SOFR", maturityDate = "2031-01-01", effectiveDate = "2026-01-01", payReceive = SwapDirection.PAY_FIXED),
            FxSpot(baseCurrency = "EUR", quoteCurrency = "USD"),
            FxForward(baseCurrency = "GBP", quoteCurrency = "USD", deliveryDate = "2026-09-15"),
            FxOption(baseCurrency = "EUR", quoteCurrency = "USD", optionType = OptionType.CALL, strike = 1.10, expiryDate = "2026-09-15"),
            CommodityFuture(commodity = "WTI", expiryDate = "2026-08-20", contractSize = 1000.0, currency = "USD"),
            CommodityOption(underlyingId = "GC", optionType = OptionType.CALL, strike = 2000.0, expiryDate = "2026-08-20"),
        )
        val names = types.map { it.instrumentTypeName }
        names.toSet().size shouldBe 11
    }
})
