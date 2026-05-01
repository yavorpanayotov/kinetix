package com.kinetix.risk.service

import com.kinetix.risk.service.WrongWayRiskSectorGroup.ENERGY_UTILITIES
import com.kinetix.risk.service.WrongWayRiskSectorGroup.FINANCIALS
import com.kinetix.risk.service.WrongWayRiskSectorGroup.OTHER
import com.kinetix.risk.service.WrongWayRiskSectorGroup.REAL_ESTATE
import com.kinetix.risk.service.WrongWayRiskSectorGroup.SOVEREIGN
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WrongWayRiskSectorGroupTest : FunSpec({

    test("financial counterparty sectors all map to FINANCIALS") {
        listOf("BANK", "Banks", "BROKER_DEALER", "Insurance", "ASSET_MANAGER", "FINANCIALS")
            .forEach { WrongWayRiskSectorGroup.fromSector(it) shouldBe FINANCIALS }
    }

    test("sovereign-related sectors all map to SOVEREIGN") {
        listOf("SOVEREIGN", "Government", "SUPRANATIONAL", "central_bank")
            .forEach { WrongWayRiskSectorGroup.fromSector(it) shouldBe SOVEREIGN }
    }

    test("energy and utility sectors map to ENERGY_UTILITIES") {
        listOf("ENERGY", "Oil_Gas", "Utilities", "GAS")
            .forEach { WrongWayRiskSectorGroup.fromSector(it) shouldBe ENERGY_UTILITIES }
    }

    test("real-estate sectors map to REAL_ESTATE") {
        listOf("REAL_ESTATE", "REIT", "REITs", "PROPERTY")
            .forEach { WrongWayRiskSectorGroup.fromSector(it) shouldBe REAL_ESTATE }
    }

    test("technology and consumer sectors map to OTHER") {
        listOf("TECHNOLOGY", "Consumer", "INDUSTRIALS", "MATERIALS", "HEALTHCARE")
            .forEach { WrongWayRiskSectorGroup.fromSector(it) shouldBe OTHER }
    }

    test("null and blank sector inputs map to OTHER") {
        WrongWayRiskSectorGroup.fromSector(null) shouldBe OTHER
        WrongWayRiskSectorGroup.fromSector("") shouldBe OTHER
        WrongWayRiskSectorGroup.fromSector("   ") shouldBe OTHER
    }

    test("mapping is case-insensitive and tolerates surrounding whitespace") {
        WrongWayRiskSectorGroup.fromSector("financials") shouldBe FINANCIALS
        WrongWayRiskSectorGroup.fromSector(" SOVEREIGN ") shouldBe SOVEREIGN
        WrongWayRiskSectorGroup.fromSector("Real Estate") shouldBe REAL_ESTATE
    }

    test("unknown sector strings map to OTHER rather than throwing") {
        WrongWayRiskSectorGroup.fromSector("UNRECOGNISED-SECTOR-XYZ") shouldBe OTHER
    }
})
