package com.kinetix.risk.service

import com.kinetix.common.model.*
import com.kinetix.risk.client.InstrumentServiceClient
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.client.RiskEngineClient
import com.kinetix.risk.kafka.RiskResultPublisher
import com.kinetix.risk.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

private val USD = Currency.getInstance("USD")

private fun position(
    bookId: String = "port-1",
    instrumentId: String = "AAPL",
    assetClass: AssetClass = AssetClass.EQUITY,
    quantity: String = "100",
    marketPrice: String = "170.00",
) = Position(
    bookId = BookId(bookId),
    instrumentId = InstrumentId(instrumentId),
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCost = Money(BigDecimal("150.00"), USD),
    marketPrice = Money(BigDecimal(marketPrice), USD),
    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
)

private fun varResult(
    bookId: String = "port-1",
    calculationType: CalculationType = CalculationType.PARAMETRIC,
    varValue: Double = 5000.0,
    componentBreakdown: List<ComponentBreakdown> = listOf(
        ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0),
    ),
) = ValuationResult(
    bookId = BookId(bookId),
    calculationType = calculationType,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    componentBreakdown = componentBreakdown,
    greeks = null,
    calculatedAt = Instant.now(),
    computedOutputs = setOf(ValuationOutput.VAR, ValuationOutput.EXPECTED_SHORTFALL),
)

class VaRCalculationServiceTest : FunSpec({

    val positionProvider = mockk<PositionProvider>()
    val riskEngineClient = mockk<RiskEngineClient>()
    val resultPublisher = mockk<RiskResultPublisher>()
    val jobRecorder = mockk<ValuationJobRecorder>()
    val service = VaRCalculationService(
        positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
        jobRecorder = jobRecorder,
    )
    val serviceNoRecorder = VaRCalculationService(positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry())

    beforeEach {
        clearMocks(positionProvider, riskEngineClient, resultPublisher, jobRecorder)
        coEvery { jobRecorder.save(any()) } just Runs
        coEvery { jobRecorder.update(any()) } just Runs
        coEvery { jobRecorder.updateCurrentPhase(any(), any()) } just Runs
    }

    test("fetches positions, calls risk engine, and publishes result") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result!!.copy(positionRisk = emptyList(), jobId = null, valuationDate = null) shouldBe expectedResult

        coVerify(ordering = Ordering.ORDERED) {
            positionProvider.getPositions(BookId("port-1"))
            riskEngineClient.valuate(any(), positions)
            resultPublisher.publish(expectedResult)
        }
    }

    test("returns null and does not call risk engine for empty portfolio") {
        coEvery { positionProvider.getPositions(BookId("empty")) } returns emptyList()

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("empty"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result shouldBe null

        coVerify(exactly = 0) { riskEngineClient.valuate(any(), any()) }
        coVerify(exactly = 0) { resultPublisher.publish(any()) }
    }

    test("passes correct calculation type to risk engine") {
        val positions = listOf(position())

        for (calcType in CalculationType.entries) {
            val expectedResult = varResult(calculationType = calcType)

            coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
            coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
            coEvery { resultPublisher.publish(any()) } just Runs

            val result = serviceNoRecorder.calculateVaR(
                VaRCalculationRequest(
                    bookId = BookId("port-1"),
                    calculationType = calcType,
                    confidenceLevel = ConfidenceLevel.CL_95,
                )
            )

            result!!.calculationType shouldBe calcType
        }
    }

    test("handles multi-asset portfolio") {
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY),
            position(instrumentId = "UST10Y", assetClass = AssetClass.FIXED_INCOME),
            position(instrumentId = "EURUSD", assetClass = AssetClass.FX),
        )
        val expectedResult = varResult(
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 3000.0, 50.0),
                ComponentBreakdown(AssetClass.FIXED_INCOME, 1500.0, 25.0),
                ComponentBreakdown(AssetClass.FX, 1500.0, 25.0),
            ),
        )

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result!!.componentBreakdown.size shouldBe 3
    }

    test("saves a RUNNING skeleton job at the beginning of calculation") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val saveSlot = slot<ValuationJob>()
        coVerify { jobRecorder.save(capture(saveSlot)) }

        val startedJob = saveSlot.captured
        startedJob.bookId shouldBe "port-1"
        startedJob.status shouldBe RunStatus.RUNNING
        startedJob.triggerType shouldBe TriggerType.ON_DEMAND
        startedJob.calculationType shouldBe "PARAMETRIC"
        startedJob.confidenceLevel shouldBe "CL_95"
        startedJob.completedAt shouldBe null
        startedJob.durationMs shouldBe null
        startedJob.varValue shouldBe null
        startedJob.expectedShortfall shouldBe null
        startedJob.phases shouldHaveSize 0
        startedJob.error shouldBe null
    }

    test("updates the job to COMPLETED with all job steps after calculation") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val updateSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(updateSlot)) }

        val job = updateSlot.captured
        job.bookId shouldBe "port-1"
        job.status shouldBe RunStatus.COMPLETED
        job.triggerType shouldBe TriggerType.ON_DEMAND
        job.calculationType shouldBe "PARAMETRIC"
        job.confidenceLevel shouldBe "CL_95"
        job.varValue shouldBe expectedResult.varValue
        job.expectedShortfall shouldBe expectedResult.expectedShortfall
        job.completedAt.shouldNotBeNull()
        job.durationMs.shouldNotBeNull()
        job.error shouldBe null

        job.phases shouldHaveSize 5
        job.phases[0].name shouldBe JobPhaseName.FETCH_POSITIONS
        job.phases[1].name shouldBe JobPhaseName.DISCOVER_DEPENDENCIES
        job.phases[2].name shouldBe JobPhaseName.FETCH_MARKET_DATA
        job.phases[3].name shouldBe JobPhaseName.VALUATION
        job.phases[4].name shouldBe JobPhaseName.PUBLISH_RESULT

        job.phases[0].details["positionCount"] shouldBe 1

        val positionsJson = job.phases[0].details["positions"]
        positionsJson.shouldBeInstanceOf<String>()
        positionsJson shouldContain "AAPL"
        positionsJson shouldContain "EQUITY"
        positionsJson shouldContain "100"
    }

    test("updates the job to FAILED when risk engine throws") {
        val positions = listOf(position())

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } throws RuntimeException("Engine down")

        try {
            service.calculateVaR(
                VaRCalculationRequest(
                    bookId = BookId("port-1"),
                    calculationType = CalculationType.PARAMETRIC,
                    confidenceLevel = ConfidenceLevel.CL_95,
                )
            )
        } catch (_: RuntimeException) {
            // expected
        }

        val saveSlot = slot<ValuationJob>()
        coVerify { jobRecorder.save(capture(saveSlot)) }
        saveSlot.captured.status shouldBe RunStatus.RUNNING

        val updateSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(updateSlot)) }
        updateSlot.captured.status shouldBe RunStatus.FAILED
        updateSlot.captured.error shouldBe "Engine down"
    }

    test("does not fail the calculation if job recorder save throws") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs
        coEvery { jobRecorder.save(any()) } throws RuntimeException("DB connection failed")

        val result = service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result!!.copy(positionRisk = emptyList(), jobId = null, valuationDate = null) shouldBe expectedResult
    }

    test("does not fail the calculation if job recorder update throws") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs
        coEvery { jobRecorder.update(any()) } throws RuntimeException("DB connection failed")

        val result = service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result!!.copy(positionRisk = emptyList(), jobId = null, valuationDate = null) shouldBe expectedResult
    }

    test("passes trigger type through to the recorded job") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            triggerType = TriggerType.TRADE_EVENT,
        )

        val saveSlot = slot<ValuationJob>()
        coVerify { jobRecorder.save(capture(saveSlot)) }
        saveSlot.captured.triggerType shouldBe TriggerType.TRADE_EVENT

        val updateSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(updateSlot)) }
        updateSlot.captured.triggerType shouldBe TriggerType.TRADE_EVENT
    }

    test("captures discovered dependencies in step details") {
        val positions = listOf(position())
        val expectedResult = varResult()
        val dependencies = listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"),
            DiscoveredDependency("HISTORICAL_PRICES", "AAPL", "EQUITY", mapOf("lookback" to "252")),
        )

        val discoverer = mockk<DependenciesDiscoverer>()
        coEvery { discoverer.discover(any(), any(), any()) } returns dependencies
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions, any()) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        val serviceWithDiscoverer = VaRCalculationService(
            positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
            dependenciesDiscoverer = discoverer,
            jobRecorder = jobRecorder,
        )

        serviceWithDiscoverer.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val discoverStep = jobSlot.captured.phases.first { it.name == JobPhaseName.DISCOVER_DEPENDENCIES }
        discoverStep.details["dependencyCount"] shouldBe 2

        val depsJson = discoverStep.details["dependencies"]
        depsJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(depsJson).jsonArray
        parsed shouldHaveSize 2

        parsed[0].jsonObject["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
        parsed[0].jsonObject["dataType"]!!.jsonPrimitive.content shouldBe "SPOT_PRICE"
        parsed[0].jsonObject["assetClass"]!!.jsonPrimitive.content shouldBe "EQUITY"

        parsed[1].jsonObject["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
        parsed[1].jsonObject["dataType"]!!.jsonPrimitive.content shouldBe "HISTORICAL_PRICES"
        parsed[1].jsonObject["parameters"]!!.jsonPrimitive.content shouldContain "lookback=252"
    }

    test("captures market data items with fetch status in step details") {
        val positions = listOf(position())
        val expectedResult = varResult()
        val dependencies = listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"),
            DiscoveredDependency("YIELD_CURVE", "USD_SOFR", "RATES"),
        )
        val spotDep = dependencies[0]
        val yieldDep = dependencies[1]
        val spotValue = ScalarMarketData("SPOT_PRICE", "AAPL", "EQUITY", 170.5)
        val fetchResults = listOf<FetchResult>(
            FetchSuccess(spotDep, spotValue),
            FetchFailure(
                dependency = yieldDep,
                reason = "CLIENT_UNAVAILABLE",
                url = "http://rates:8088/api/rates/yield-curves/USD_SOFR/latest",
                httpStatus = null,
                errorMessage = null,
                service = "rates-service",
                timestamp = Instant.parse("2026-02-24T10:00:00Z"),
                durationMs = 5,
            ),
        )

        val discoverer = mockk<DependenciesDiscoverer>()
        val fetcher = mockk<MarketDataFetcher>()
        coEvery { discoverer.discover(any(), any(), any()) } returns dependencies
        coEvery { fetcher.fetch(dependencies) } returns fetchResults
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions, listOf(spotValue)) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        val serviceWithFetcher = VaRCalculationService(
            positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
            dependenciesDiscoverer = discoverer,
            marketDataFetcher = fetcher,
            jobRecorder = jobRecorder,
        )

        serviceWithFetcher.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val mdStep = jobSlot.captured.phases.first { it.name == JobPhaseName.FETCH_MARKET_DATA }
        mdStep.details["requested"] shouldBe 2
        mdStep.details["fetched"] shouldBe 1

        val itemsJson = mdStep.details["marketDataItems"]
        itemsJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(itemsJson).jsonArray
        parsed shouldHaveSize 2

        val spotItem = parsed[0].jsonObject
        spotItem["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
        spotItem["dataType"]!!.jsonPrimitive.content shouldBe "SPOT_PRICE"
        spotItem["status"]!!.jsonPrimitive.content shouldBe "FETCHED"
        spotItem["value"]!!.jsonPrimitive.content shouldBe "170.5"

        val curveItem = parsed[1].jsonObject
        curveItem["instrumentId"]!!.jsonPrimitive.content shouldBe "USD_SOFR"
        curveItem["dataType"]!!.jsonPrimitive.content shouldBe "YIELD_CURVE"
        curveItem["status"]!!.jsonPrimitive.content shouldBe "MISSING"
    }

    test("MISSING market data items contain error diagnostics with all seven fields") {
        val positions = listOf(position())
        val expectedResult = varResult()
        val dependencies = listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"),
            DiscoveredDependency("YIELD_CURVE", "USD_SOFR", "RATES"),
        )
        val spotDep = dependencies[0]
        val yieldDep = dependencies[1]
        val spotValue = ScalarMarketData("SPOT_PRICE", "AAPL", "EQUITY", 170.5)
        val failureTimestamp = Instant.parse("2026-02-24T10:00:00Z")
        val fetchResults = listOf<FetchResult>(
            FetchSuccess(spotDep, spotValue),
            FetchFailure(
                dependency = yieldDep,
                reason = "NOT_FOUND",
                url = "http://rates:8088/api/rates/yield-curves/USD_SOFR/latest",
                httpStatus = 404,
                errorMessage = "Yield curve not found for USD_SOFR",
                service = "rates-service",
                timestamp = failureTimestamp,
                durationMs = 42,
            ),
        )

        val discoverer = mockk<DependenciesDiscoverer>()
        val fetcher = mockk<MarketDataFetcher>()
        coEvery { discoverer.discover(any(), any(), any()) } returns dependencies
        coEvery { fetcher.fetch(dependencies) } returns fetchResults
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions, listOf(spotValue)) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        val serviceWithFetcher = VaRCalculationService(
            positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
            dependenciesDiscoverer = discoverer,
            marketDataFetcher = fetcher,
            jobRecorder = jobRecorder,
        )

        serviceWithFetcher.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val mdStep = jobSlot.captured.phases.first { it.name == JobPhaseName.FETCH_MARKET_DATA }
        val itemsJson = mdStep.details["marketDataItems"]
        itemsJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(itemsJson).jsonArray
        val missingItem = parsed[1].jsonObject
        missingItem["status"]!!.jsonPrimitive.content shouldBe "MISSING"

        val issue = missingItem["issue"]!!.jsonObject
        issue["reason"]!!.jsonPrimitive.content shouldBe "NOT_FOUND"
        issue["url"]!!.jsonPrimitive.content shouldBe "http://rates:8088/api/rates/yield-curves/USD_SOFR/latest"
        issue["httpStatus"]!!.jsonPrimitive.content shouldBe "404"
        issue["errorMessage"]!!.jsonPrimitive.content shouldBe "Yield curve not found for USD_SOFR"
        issue["service"]!!.jsonPrimitive.content shouldBe "rates-service"
        issue["timestamp"]!!.jsonPrimitive.content shouldBe "2026-02-24T10:00:00Z"
        issue["durationMs"]!!.jsonPrimitive.content shouldBe "42"

        // FETCHED item should NOT have issue key
        val fetchedItem = parsed[0].jsonObject
        fetchedItem.containsKey("issue") shouldBe false
    }

    test("FETCH_POSITIONS step contains dependenciesByPosition when dependencies are discovered") {
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY),
            position(instrumentId = "UST10Y", assetClass = AssetClass.FIXED_INCOME),
        )
        val expectedResult = varResult()
        val dependencies = listOf(
            DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY"),
            DiscoveredDependency("YIELD_CURVE", "", "FIXED_INCOME"),
            DiscoveredDependency("CORRELATION_MATRIX", "", ""),
        )

        val discoverer = mockk<DependenciesDiscoverer>()
        coEvery { discoverer.discover(any(), any(), any()) } returns dependencies
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions, any()) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        val serviceWithDiscoverer = VaRCalculationService(
            positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
            dependenciesDiscoverer = discoverer,
            jobRecorder = jobRecorder,
        )

        serviceWithDiscoverer.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val fetchPosStep = jobSlot.captured.phases.first { it.name == JobPhaseName.FETCH_POSITIONS }
        val groupedJson = fetchPosStep.details["dependenciesByPosition"]
        groupedJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(groupedJson).jsonObject
        parsed.keys shouldBe setOf("AAPL", "UST10Y")

        val aaplDeps = parsed["AAPL"]!!.jsonArray
        aaplDeps shouldHaveSize 2
        aaplDeps.map { it.jsonObject["dataType"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("SPOT_PRICE", "CORRELATION_MATRIX")

        val ustDeps = parsed["UST10Y"]!!.jsonArray
        ustDeps shouldHaveSize 2
        ustDeps.map { it.jsonObject["dataType"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("YIELD_CURVE", "CORRELATION_MATRIX")
    }

    test("includes per-position VaR breakdown in CALCULATE_VAR step details") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val calcStep = jobSlot.captured.phases.first { it.name == JobPhaseName.VALUATION }
        val breakdownJson = calcStep.details["positionBreakdown"]
        breakdownJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(breakdownJson).jsonArray
        parsed shouldHaveSize 1

        val item = parsed[0].jsonObject
        item["instrumentId"]!!.jsonPrimitive.content shouldBe "AAPL"
        item["assetClass"]!!.jsonPrimitive.content shouldBe "EQUITY"
        item["varContribution"]!!.jsonPrimitive.content shouldBe "5000.00"
        item["esContribution"]!!.jsonPrimitive.content shouldBe "6250.00"
        item["percentageOfTotal"]!!.jsonPrimitive.content shouldBe "100.00"
    }

    test("distributes VaR proportionally across positions in the same asset class") {
        val positions = listOf(
            position(instrumentId = "AAPL", marketPrice = "200.00"),
            position(instrumentId = "TSLA", marketPrice = "100.00"),
        )
        val expectedResult = varResult(
            varValue = 6000.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 6000.0, 100.0),
            ),
        )

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val calcStep = jobSlot.captured.phases.first { it.name == JobPhaseName.VALUATION }
        val parsed = Json.parseToJsonElement(calcStep.details["positionBreakdown"] as String).jsonArray
        parsed shouldHaveSize 2

        val aapl = parsed.first { it.jsonObject["instrumentId"]!!.jsonPrimitive.content == "AAPL" }.jsonObject
        aapl["varContribution"]!!.jsonPrimitive.content shouldBe "4000.00"
        aapl["esContribution"]!!.jsonPrimitive.content shouldBe "5000.00"
        aapl["percentageOfTotal"]!!.jsonPrimitive.content shouldBe "66.67"

        val tsla = parsed.first { it.jsonObject["instrumentId"]!!.jsonPrimitive.content == "TSLA" }.jsonObject
        tsla["varContribution"]!!.jsonPrimitive.content shouldBe "2000.00"
        tsla["esContribution"]!!.jsonPrimitive.content shouldBe "2500.00"
        tsla["percentageOfTotal"]!!.jsonPrimitive.content shouldBe "33.33"
    }

    test("dependenciesByPosition absent when no dependencies are discovered") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val fetchPosStep = jobSlot.captured.phases.first { it.name == JobPhaseName.FETCH_POSITIONS }
        fetchPosStep.details.containsKey("dependenciesByPosition") shouldBe false
    }

    test("includes per-position Greeks in CALCULATE_VAR step details when greeks are computed") {
        // Two cash-equity positions with DIFFERENT share counts must surface
        // DISTINCT per-instrument deltas — this is the trader-review P0 fix.
        // Cash-equity gamma/vega are zero regardless of any asset-class
        // aggregate the engine returns (only options have them).
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY, quantity = "100", marketPrice = "170.00"),
            position(instrumentId = "JNJ", assetClass = AssetClass.EQUITY, quantity = "50", marketPrice = "160.00"),
        )
        val greeks = GreeksResult(
            // Aggregate is intentionally non-zero so any future regression that
            // smears it onto each row trips this test loudly.
            assetClassGreeks = listOf(
                GreekValues(AssetClass.EQUITY, delta = 0.85, gamma = 0.02, vega = 1500.0),
            ),
            theta = -45.0,
            rho = 120.0,
        )
        val expectedResult = varResult(
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0),
            ),
        ).copy(greeks = greeks)

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val calcStep = jobSlot.captured.phases.first { it.name == JobPhaseName.VALUATION }
        val breakdownJson = calcStep.details["positionBreakdown"]
        breakdownJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(breakdownJson).jsonArray
        parsed shouldHaveSize 2

        // 100 shares * $170 = $17,000 dollar delta.
        val aapl = parsed.first { it.jsonObject["instrumentId"]!!.jsonPrimitive.content == "AAPL" }.jsonObject
        aapl["delta"]!!.jsonPrimitive.content shouldBe "17000.000000"
        aapl["gamma"]!!.jsonPrimitive.content shouldBe "0.000000"
        aapl["vega"]!!.jsonPrimitive.content shouldBe "0.000000"

        // 50 shares * $160 = $8,000 dollar delta. Distinct from AAPL — proving
        // the row is per-position, not per-asset-class.
        val jnj = parsed.first { it.jsonObject["instrumentId"]!!.jsonPrimitive.content == "JNJ" }.jsonObject
        jnj["delta"]!!.jsonPrimitive.content shouldBe "8000.000000"
        jnj["gamma"]!!.jsonPrimitive.content shouldBe "0.000000"
        jnj["vega"]!!.jsonPrimitive.content shouldBe "0.000000"
    }

    test("omits Greeks from position breakdown when greeks are null") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val calcStep = jobSlot.captured.phases.first { it.name == JobPhaseName.VALUATION }
        val breakdownJson = calcStep.details["positionBreakdown"]
        breakdownJson.shouldBeInstanceOf<String>()

        val parsed = Json.parseToJsonElement(breakdownJson).jsonArray
        val item = parsed[0].jsonObject
        item.containsKey("delta") shouldBe false
        item.containsKey("gamma") shouldBe false
        item.containsKey("vega") shouldBe false
    }

    test("includes Greeks summary in CALCULATE_VAR step when greeks are present") {
        val positions = listOf(position())
        val greeks = GreeksResult(
            assetClassGreeks = listOf(
                GreekValues(AssetClass.EQUITY, delta = 0.85, gamma = 0.02, vega = 1500.0),
            ),
            theta = -45.0,
            rho = 120.0,
        )
        val expectedResult = varResult().copy(greeks = greeks)

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        val jobSlot = slot<ValuationJob>()
        coVerify { jobRecorder.update(capture(jobSlot)) }

        val job = jobSlot.captured
        job.phases shouldHaveSize 5
        job.phases[3].name shouldBe JobPhaseName.VALUATION

        val calcStep = job.phases[3]
        calcStep.details["greeksAssetClassCount"] shouldBe 1
        calcStep.details["theta"] shouldBe -45.0
        calcStep.details["rho"] shouldBe 120.0
    }

    test("calculateVaR returns positionRisk in the ValuationResult") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        result.positionRisk shouldHaveSize 1

        val risk = result.positionRisk[0]
        risk.instrumentId shouldBe InstrumentId("AAPL")
        risk.assetClass shouldBe AssetClass.EQUITY
        risk.varContribution.toDouble() shouldBe 5000.0
        risk.percentageOfTotal.toDouble() shouldBe 100.0
    }

    test("positionRisk allocates VaR proportionally by absolute market value within asset class") {
        val positions = listOf(
            position(instrumentId = "AAPL", marketPrice = "200.00"),
            position(instrumentId = "TSLA", marketPrice = "100.00"),
        )
        val expectedResult = varResult(
            varValue = 6000.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 6000.0, 100.0),
            ),
        )

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        result.positionRisk shouldHaveSize 2

        val aapl = result.positionRisk.first { it.instrumentId == InstrumentId("AAPL") }
        aapl.varContribution.setScale(2, java.math.RoundingMode.HALF_UP).toDouble() shouldBe 4000.0
        aapl.percentageOfTotal.setScale(2, java.math.RoundingMode.HALF_UP).toDouble() shouldBe 66.67

        val tsla = result.positionRisk.first { it.instrumentId == InstrumentId("TSLA") }
        tsla.varContribution.setScale(2, java.math.RoundingMode.HALF_UP).toDouble() shouldBe 2000.0
        tsla.percentageOfTotal.setScale(2, java.math.RoundingMode.HALF_UP).toDouble() shouldBe 33.33
    }

    test("positionRisk includes per-position greeks when greeks are present") {
        // Per-instrument Greeks must scale with the position, not the asset
        // class. Trader-review P0 regression: the table previously showed the
        // same delta on every equity row.
        val positions = listOf(
            position(instrumentId = "AAPL", assetClass = AssetClass.EQUITY, quantity = "100", marketPrice = "170.00"),
            position(instrumentId = "JNJ", assetClass = AssetClass.EQUITY, quantity = "50", marketPrice = "160.00"),
        )
        val greeks = GreeksResult(
            assetClassGreeks = listOf(
                GreekValues(AssetClass.EQUITY, delta = 0.85, gamma = 0.02, vega = 1500.0),
            ),
            theta = -45.0,
            rho = 120.0,
        )
        val expectedResult = varResult(
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 5000.0, 100.0),
            ),
        ).copy(greeks = greeks)

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        result.positionRisk shouldHaveSize 2

        val aapl = result.positionRisk.first { it.instrumentId == InstrumentId("AAPL") }
        // Dollar delta = qty * spot = 100 * 170.
        aapl.delta shouldBe 17000.0
        aapl.gamma shouldBe 0.0
        aapl.vega shouldBe 0.0

        val jnj = result.positionRisk.first { it.instrumentId == InstrumentId("JNJ") }
        // 50 * 160 — distinct from AAPL, proving per-position attribution.
        jnj.delta shouldBe 8000.0
        jnj.gamma shouldBe 0.0
        jnj.vega shouldBe 0.0
    }

    test("positionRisk has null greeks when greeks are not computed") {
        val positions = listOf(position())
        val expectedResult = varResult()

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        val risk = result.positionRisk[0]
        risk.delta.shouldBeNull()
        risk.gamma.shouldBeNull()
        risk.vega.shouldBeNull()
    }

    test("passes run label through to both RUNNING and COMPLETED recorded jobs") {
        val savedJob = slot<ValuationJob>()
        val updatedJob = slot<ValuationJob>()
        coEvery { jobRecorder.save(capture(savedJob)) } just Runs
        coEvery { jobRecorder.update(capture(updatedJob)) } just Runs
        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any()) } returns varResult()
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            ),
            runLabel = RunLabel.SOD,
        )

        savedJob.captured.runLabel shouldBe RunLabel.SOD
        updatedJob.captured.runLabel shouldBe RunLabel.SOD
    }

    test("passes run label through to FAILED job when risk engine throws") {
        val updatedJob = slot<ValuationJob>()
        coEvery { jobRecorder.update(capture(updatedJob)) } just Runs
        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any()) } throws RuntimeException("Engine down")

        try {
            service.calculateVaR(
                VaRCalculationRequest(
                    bookId = BookId("port-1"),
                    calculationType = CalculationType.PARAMETRIC,
                    confidenceLevel = ConfidenceLevel.CL_95,
                ),
                runLabel = RunLabel.SOD,
            )
        } catch (_: RuntimeException) {
            // expected
        }

        updatedJob.captured.runLabel shouldBe RunLabel.SOD
    }

    test("defaults run label to null when not provided") {
        val savedJob = slot<ValuationJob>()
        val updatedJob = slot<ValuationJob>()
        coEvery { jobRecorder.save(capture(savedJob)) } just Runs
        coEvery { jobRecorder.update(capture(updatedJob)) } just Runs
        coEvery { positionProvider.getPositions(any()) } returns listOf(position())
        coEvery { riskEngineClient.valuate(any(), any()) } returns varResult()
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        savedJob.captured.runLabel shouldBe null
        updatedJob.captured.runLabel shouldBe null
    }

    test("positionRisk uses absolute market value for weighting when positions have mixed directions") {
        val positions = listOf(
            position(instrumentId = "AAPL", marketPrice = "200.00", quantity = "100"),
            position(instrumentId = "TSLA", marketPrice = "100.00", quantity = "-50"),
        )
        val expectedResult = varResult(
            varValue = 6000.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 6000.0, 100.0),
            ),
        )

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        val result = serviceNoRecorder.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        result.positionRisk shouldHaveSize 2

        // AAPL: |100 * 200| = 20000, TSLA: |-50 * 100| = 5000
        // AAPL weight: 20000/25000 = 0.8, TSLA weight: 5000/25000 = 0.2
        val aapl = result.positionRisk.first { it.instrumentId == InstrumentId("AAPL") }
        aapl.varContribution.setScale(2, java.math.RoundingMode.HALF_UP).toDouble() shouldBe 4800.0

        val tsla = result.positionRisk.first { it.instrumentId == InstrumentId("TSLA") }
        // Short position gets negative VaR contribution (it's a hedge)
        tsla.varContribution.setScale(2, java.math.RoundingMode.HALF_UP).toDouble() shouldBe -1200.0
    }

    test("records currentPhase transitions in order through all phases then null on completion") {
        val positions = listOf(position())
        val expectedResult = varResult()
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs

        service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        coVerify(ordering = Ordering.ORDERED) {
            jobRecorder.updateCurrentPhase(any(), JobPhaseName.FETCH_POSITIONS)
            jobRecorder.updateCurrentPhase(any(), JobPhaseName.DISCOVER_DEPENDENCIES)
            jobRecorder.updateCurrentPhase(any(), JobPhaseName.FETCH_MARKET_DATA)
            jobRecorder.updateCurrentPhase(any(), JobPhaseName.VALUATION)
            jobRecorder.updateCurrentPhase(any(), JobPhaseName.PUBLISH_RESULT)
        }
    }

    test("does not fail the calculation if updateCurrentPhase throws") {
        val positions = listOf(position())
        val expectedResult = varResult()
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(any()) } just Runs
        coEvery { jobRecorder.updateCurrentPhase(any(), any()) } throws RuntimeException("DB down")

        val result = service.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        result.varValue shouldBe 5000.0
    }

    test("sets marketDataComplete to true when all market data fetches succeed") {
        val positions = listOf(position())
        val expectedResult = varResult()
        val dep = com.kinetix.risk.model.DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY")
        val fetchResults = listOf(
            com.kinetix.risk.model.FetchSuccess(dep, com.kinetix.risk.model.ScalarMarketData("SPOT_PRICE", "AAPL", "EQUITY", 170.0)),
        )

        val mockDiscoverer = mockk<com.kinetix.risk.service.DependenciesDiscoverer>()
        val mockFetcher = mockk<com.kinetix.risk.service.MarketDataFetcher>()
        val svc = VaRCalculationService(positionProvider, riskEngineClient, resultPublisher, dependenciesDiscoverer = mockDiscoverer, marketDataFetcher = mockFetcher, jobRecorder = jobRecorder)

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { mockDiscoverer.discover(any(), any(), any()) } returns listOf(dep)
        coEvery { mockFetcher.fetch(any()) } returns fetchResults
        coEvery { riskEngineClient.valuate(any(), positions, any(), any()) } returns expectedResult
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val result = svc.calculateVaR(
            VaRCalculationRequest(bookId = BookId("port-1"), calculationType = CalculationType.PARAMETRIC, confidenceLevel = ConfidenceLevel.CL_95),
        )

        result.shouldNotBeNull()
        result.marketDataComplete shouldBe true
    }

    test("sets marketDataComplete to false when any market data fetch fails") {
        val positions = listOf(position())
        val expectedResult = varResult()
        val dep = com.kinetix.risk.model.DiscoveredDependency("SPOT_PRICE", "AAPL", "EQUITY")
        val fetchResults = listOf(
            com.kinetix.risk.model.FetchFailure(
                dep, "NOT_FOUND", null, 404, null, "price-service",
                java.time.Instant.now(), 5L,
            ),
        )

        val mockDiscoverer = mockk<com.kinetix.risk.service.DependenciesDiscoverer>()
        val mockFetcher = mockk<com.kinetix.risk.service.MarketDataFetcher>()
        val svc = VaRCalculationService(positionProvider, riskEngineClient, resultPublisher, dependenciesDiscoverer = mockDiscoverer, marketDataFetcher = mockFetcher, jobRecorder = jobRecorder)

        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { mockDiscoverer.discover(any(), any(), any()) } returns listOf(dep)
        coEvery { mockFetcher.fetch(any()) } returns fetchResults
        coEvery { riskEngineClient.valuate(any(), positions, any(), any()) } returns expectedResult
        coEvery { resultPublisher.publish(any(), any()) } just Runs

        val result = svc.calculateVaR(
            VaRCalculationRequest(bookId = BookId("port-1"), calculationType = CalculationType.PARAMETRIC, confidenceLevel = ConfidenceLevel.CL_95),
        )

        result.shouldNotBeNull()
        result.marketDataComplete shouldBe false
    }

    // ---- Phase 6.1: computePositionRisk dedicated tests ----

    test("computePositionRisk produces negative varContribution for short positions") {
        val longPos = position(instrumentId = "AAPL", marketPrice = "170.00")
        val shortPos = position(instrumentId = "TSLA", marketPrice = "-200.00")
        val result = varResult(
            varValue = 10000.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 10000.0, 100.0),
            ),
        )

        val risk = service.computePositionRisk(listOf(longPos, shortPos), result)

        risk shouldHaveSize 2
        val longRisk = risk.first { it.instrumentId.value == "AAPL" }
        val shortRisk = risk.first { it.instrumentId.value == "TSLA" }

        // Short position should have negative contribution
        (shortRisk.varContribution.toDouble() < 0) shouldBe true
        (shortRisk.percentageOfTotal.toDouble() < 0) shouldBe true
        // Long position should have positive contribution
        (longRisk.varContribution.toDouble() > 0) shouldBe true
    }

    test("computePositionRisk handles zero total VaR without exception") {
        val pos = position()
        val result = varResult(
            varValue = 0.0,
            componentBreakdown = listOf(
                ComponentBreakdown(AssetClass.EQUITY, 0.0, 0.0),
            ),
        )

        val risk = service.computePositionRisk(listOf(pos), result)

        risk shouldHaveSize 1
        risk[0].varContribution.toDouble() shouldBeExactly 0.0
        risk[0].percentageOfTotal.toDouble() shouldBeExactly 0.0
        risk[0].esContribution.toDouble() shouldBeExactly 0.0
    }

    test("computePositionRisk handles null Greeks gracefully") {
        val pos = position()
        val result = varResult().copy(greeks = null)

        val risk = service.computePositionRisk(listOf(pos), result)

        risk shouldHaveSize 1
        risk[0].delta.shouldBeNull()
        risk[0].gamma.shouldBeNull()
        risk[0].vega.shouldBeNull()
    }

    test("computePositionRisk applies instrument enrichment from instrumentMap") {
        val pos = position()
        val result = varResult()
        val instrumentMap = mapOf(
            "AAPL" to com.kinetix.risk.client.dtos.InstrumentDto(
                instrumentId = "AAPL",
                instrumentType = "CASH_EQUITY",
                displayName = "Apple Inc.",
                assetClass = "EQUITY",
                currency = "USD",
                attributes = kotlinx.serialization.json.JsonObject(emptyMap()),
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
            ),
        )

        val risk = service.computePositionRisk(listOf(pos), result, instrumentMap)

        risk shouldHaveSize 1
        risk[0].instrumentType shouldBe "CASH_EQUITY"
        risk[0].displayName shouldBe "Apple Inc."
    }

    // ---- Phase 6.3: Instrument enrichment failure test ----

    test("handles instrument enrichment failure gracefully and still returns a result") {
        val positions = listOf(position())
        val expectedResult = varResult()

        val instrumentClient = mockk<InstrumentServiceClient>()
        coEvery { instrumentClient.getInstruments(any()) } throws RuntimeException("Reference data service unreachable")
        coEvery { positionProvider.getPositions(BookId("port-1")) } returns positions
        coEvery { riskEngineClient.valuate(any(), positions) } returns expectedResult
        coEvery { resultPublisher.publish(expectedResult) } just Runs

        val serviceWithInstruments = VaRCalculationService(
            positionProvider, riskEngineClient, resultPublisher, SimpleMeterRegistry(),
            instrumentServiceClient = instrumentClient,
        )

        val result = serviceWithInstruments.calculateVaR(
            VaRCalculationRequest(
                bookId = BookId("port-1"),
                calculationType = CalculationType.PARAMETRIC,
                confidenceLevel = ConfidenceLevel.CL_95,
            )
        )

        result.shouldNotBeNull()
        // Calculation completes despite instrument service failure
        result.varValue shouldBe 5000.0
        // No instrument enrichment available
        result.positionRisk.forEach { pr ->
            pr.instrumentType.shouldBeNull()
        }
    }
})
