package com.kinetix.risk.service

import com.kinetix.risk.client.ClientResponse
import com.kinetix.risk.client.PositionServiceClient
import com.kinetix.risk.client.ReferenceDataServiceClient
import com.kinetix.risk.client.SaCcrClient
import com.kinetix.risk.client.SaCcrPositionInput
import com.kinetix.risk.client.SaCcrResult
import com.kinetix.risk.client.dtos.CounterpartyDto
import com.kinetix.risk.client.dtos.CounterpartyTradeDto
import com.kinetix.risk.client.dtos.NettingAgreementDto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk

private val COUNTERPARTY = CounterpartyDto(
    counterpartyId = "CP-GS",
    legalName = "Goldman Sachs",
    ratingSp = "A+",
    sector = "FINANCIALS",
    lgd = 0.4,
    cdsSpreadBps = 65.0,
)

private fun isda(nettingSetId: String) = NettingAgreementDto(
    nettingSetId = nettingSetId,
    counterpartyId = "CP-GS",
    agreementType = "ISDA",
    closeOutNetting = true,
    currency = "USD",
)

private fun gmra(nettingSetId: String) = NettingAgreementDto(
    nettingSetId = nettingSetId,
    counterpartyId = "CP-GS",
    agreementType = "GMRA",
    closeOutNetting = true,
    currency = "USD",
)

private fun trade(tradeId: String, instrumentId: String) = CounterpartyTradeDto(
    tradeId = tradeId,
    instrumentId = instrumentId,
    assetClass = "RATES",
    side = "BUY",
    quantity = "100",
    priceAmount = "1000",
    priceCurrency = "USD",
    counterpartyId = "CP-GS",
)

private fun saCcrResult(
    nettingSetId: String = "NS-GS-001",
    ead: Double = 875_000.0,
    rc: Double = 100_000.0,
    pfeAddon: Double = 525_000.0,
    multiplier: Double = 1.0,
) = SaCcrResult(
    nettingSetId = nettingSetId,
    counterpartyId = "CP-GS",
    replacementCost = rc,
    pfeAddon = pfeAddon,
    multiplier = multiplier,
    ead = ead,
    alpha = 1.4,
)

class SaCcrServiceTest : FunSpec({

    val referenceDataClient = mockk<ReferenceDataServiceClient>()
    val saCcrClient = mockk<SaCcrClient>()
    val positionServiceClient = mockk<PositionServiceClient>()
    val service = SaCcrService(
        referenceDataClient = referenceDataClient,
        saCcrClient = saCcrClient,
        positionServiceClient = positionServiceClient,
    )

    fun stubCounterparty() {
        coEvery { referenceDataClient.getCounterparty("CP-GS") } returns
            ClientResponse.Success(COUNTERPARTY)
    }

    context("calculateAllSaCcr — per netting set") {

        test("a counterparty with two netting agreements produces two SA-CCR results") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA"), gmra("NS-GMRA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(listOf(trade("T1", "INS-1"), trade("T2", "INS-2")))
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(mapOf("INS-1" to "NS-ISDA", "INS-2" to "NS-GMRA"))
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers { saCcrResult(nettingSetId = firstArg()) }

            val results = service.calculateAllSaCcr("CP-GS")

            results shouldHaveSize 2
            results.map { it.nettingSetId } shouldContainExactlyInAnyOrder listOf("NS-ISDA", "NS-GMRA")
        }

        test("trades are not netted across netting-set boundaries") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA"), gmra("NS-GMRA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(
                    listOf(
                        trade("T1", "INS-1"),
                        trade("T2", "INS-2"),
                        trade("T3", "INS-3"),
                    ),
                )
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(
                    mapOf("INS-1" to "NS-ISDA", "INS-2" to "NS-ISDA", "INS-3" to "NS-GMRA"),
                )
            val positionsBySet = mutableMapOf<String, List<SaCcrPositionInput>>()
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers {
                positionsBySet[firstArg()] = thirdArg()
                saCcrResult(nettingSetId = firstArg())
            }

            service.calculateAllSaCcr("CP-GS")

            // ISDA set sees only the two ISDA-assigned trades; GMRA set sees only its one trade.
            positionsBySet["NS-ISDA"]!!.map { it.instrumentId } shouldContainExactlyInAnyOrder
                listOf("INS-1", "INS-2")
            positionsBySet["NS-GMRA"]!!.map { it.instrumentId } shouldContainExactlyInAnyOrder
                listOf("INS-3")
        }

        test("uses each netting set's real agreement assignment, not a synthetic counterparty bucket") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA"), gmra("NS-GMRA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(listOf(trade("T1", "INS-1"), trade("T2", "INS-2")))
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(mapOf("INS-1" to "NS-ISDA", "INS-2" to "NS-GMRA"))
            val requestedSetIds = mutableListOf<String>()
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers {
                requestedSetIds += firstArg<String>()
                saCcrResult(nettingSetId = firstArg())
            }

            service.calculateAllSaCcr("CP-GS")

            // The legacy implementation always used "$counterpartyId-SA-CCR"; the new one uses real set IDs.
            requestedSetIds shouldContainExactlyInAnyOrder listOf("NS-ISDA", "NS-GMRA")
            requestedSetIds.none { it == "CP-GS-SA-CCR" } shouldBe true
        }

        test("trades with no netting-set assignment fall into an UNASSIGNED set") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(listOf(trade("T1", "INS-1"), trade("T2", "INS-UNASSIGNED")))
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(mapOf("INS-1" to "NS-ISDA"))
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers { saCcrResult(nettingSetId = firstArg()) }

            val results = service.calculateAllSaCcr("CP-GS")

            results.map { it.nettingSetId } shouldContainExactlyInAnyOrder listOf("NS-ISDA", "CP-GS-UNASSIGNED")
        }

        test("counterparty with no trades yields a single result for the primary netting set") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(emptyList())
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(emptyMap())
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers { saCcrResult(nettingSetId = firstArg()) }

            val results = service.calculateAllSaCcr("CP-GS")

            results shouldHaveSize 1
            results.single().nettingSetId shouldBe "NS-ISDA"
        }

        test("throws IllegalArgumentException when counterparty not found") {
            coEvery { referenceDataClient.getCounterparty("CP-UNKNOWN") } returns
                ClientResponse.NotFound(404)

            try {
                service.calculateAllSaCcr("CP-UNKNOWN")
                throw AssertionError("Expected exception not thrown")
            } catch (e: IllegalArgumentException) {
                e.message shouldNotBe null
            }
        }
    }

    context("calculateSaCcr — single netting set") {

        test("returns the result for the requested netting set only") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA"), gmra("NS-GMRA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(listOf(trade("T1", "INS-1"), trade("T2", "INS-2")))
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(mapOf("INS-1" to "NS-ISDA", "INS-2" to "NS-GMRA"))
            val requestedSetIds = mutableListOf<String>()
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers {
                requestedSetIds += firstArg<String>()
                saCcrResult(nettingSetId = firstArg())
            }

            val result = service.calculateSaCcr("CP-GS", nettingSetId = "NS-GMRA")

            result.nettingSetId shouldBe "NS-GMRA"
            requestedSetIds shouldBe listOf("NS-GMRA")
        }

        test("throws IllegalArgumentException when the netting set is unknown for the counterparty") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(emptyList())
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(emptyMap())

            try {
                service.calculateSaCcr("CP-GS", nettingSetId = "NS-DOES-NOT-EXIST")
                throw AssertionError("Expected exception not thrown")
            } catch (e: IllegalArgumentException) {
                e.message shouldNotBe null
            }
        }

        test("passes collateral_net through to the gRPC client") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(listOf(trade("T1", "INS-1")))
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(mapOf("INS-1" to "NS-ISDA"))
            var capturedCollateral = Double.NaN
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers {
                capturedCollateral = arg(3)
                saCcrResult(nettingSetId = firstArg())
            }

            service.calculateSaCcr("CP-GS", nettingSetId = "NS-ISDA", collateralNet = 500_000.0)

            capturedCollateral shouldBe 500_000.0
        }

        test("result alpha is 1.4 as per BCBS 279") {
            stubCounterparty()
            coEvery { referenceDataClient.getNettingAgreements("CP-GS") } returns
                ClientResponse.Success(listOf(isda("NS-ISDA")))
            coEvery { positionServiceClient.getTradesByCounterparty("CP-GS") } returns
                ClientResponse.Success(emptyList())
            coEvery { positionServiceClient.getInstrumentNettingSets("CP-GS") } returns
                ClientResponse.Success(emptyMap())
            coEvery {
                saCcrClient.calculateSaCcr(nettingSetId = any(), counterpartyId = any(), positions = any(), collateralNet = any())
            } answers { saCcrResult(nettingSetId = firstArg()) }

            val result = service.calculateSaCcr("CP-GS", nettingSetId = "NS-ISDA")

            result.alpha shouldBe 1.4
        }
    }
})
