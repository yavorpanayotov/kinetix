package com.kinetix.risk.service

import com.kinetix.risk.model.ManifestStatus
import com.kinetix.risk.model.MarketDataInputChangeType
import com.kinetix.risk.model.MarketDataRef
import com.kinetix.risk.model.MarketDataSnapshotStatus
import com.kinetix.risk.model.PositionInputChangeType
import com.kinetix.risk.model.PositionSnapshotEntry
import com.kinetix.risk.model.RunManifest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun manifest(
    positionDigest: String = "pos-digest-a",
    marketDataDigest: String = "mkt-digest-a",
    modelVersion: String = "v1.0",
) = RunManifest(
    manifestId = UUID.randomUUID(),
    jobId = UUID.randomUUID(),
    bookId = "PORT-1",
    valuationDate = LocalDate.of(2024, 1, 15),
    capturedAt = Instant.now(),
    modelVersion = modelVersion,
    calculationType = "MONTE_CARLO",
    confidenceLevel = "0.99",
    timeHorizonDays = 1,
    numSimulations = 10000,
    monteCarloSeed = 42L,
    positionCount = 1,
    positionDigest = positionDigest,
    marketDataDigest = marketDataDigest,
    inputDigest = "input-digest",
    status = ManifestStatus.COMPLETE,
)

private fun position(
    instrumentId: String,
    assetClass: String = "EQUITY",
    quantity: String = "100",
    marketPrice: String = "50.00",
    currency: String = "USD",
) = PositionSnapshotEntry(
    instrumentId = instrumentId,
    assetClass = assetClass,
    quantity = BigDecimal(quantity),
    averageCostAmount = BigDecimal("45.00"),
    marketPriceAmount = BigDecimal(marketPrice),
    currency = currency,
    marketValueAmount = BigDecimal(quantity) * BigDecimal(marketPrice),
    unrealizedPnlAmount = BigDecimal("500.00"),
    instrumentType = "CASH_EQUITY",
)

private fun marketDataRef(
    dataType: String,
    instrumentId: String,
    assetClass: String = "EQUITY",
    contentHash: String = "hash-abc",
    status: MarketDataSnapshotStatus = MarketDataSnapshotStatus.FETCHED,
) = MarketDataRef(
    dataType = dataType,
    instrumentId = instrumentId,
    assetClass = assetClass,
    contentHash = contentHash,
    status = status,
    sourceService = "price-service",
    sourcedAt = Instant.now(),
)

class InputChangeDifferTest : FunSpec({

    val differ = InputChangeDiffer()

    test("returns positionsChanged false when position digests match") {
        val base = manifest(positionDigest = "same-digest")
        val target = manifest(positionDigest = "same-digest")
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), emptyList(), emptyList())
        result.positionsChanged shouldBe false
    }

    test("returns marketDataChanged false when market data digests match") {
        val base = manifest(marketDataDigest = "same-mkt")
        val target = manifest(marketDataDigest = "same-mkt")
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), emptyList(), emptyList())
        result.marketDataChanged shouldBe false
    }

    test("returns both flags false when all digests match") {
        val base = manifest(positionDigest = "same-pos", marketDataDigest = "same-mkt")
        val target = manifest(positionDigest = "same-pos", marketDataDigest = "same-mkt")
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), emptyList(), emptyList())
        result.positionsChanged shouldBe false
        result.marketDataChanged shouldBe false
    }

    test("returns both flags true when all digests differ") {
        val base = manifest(positionDigest = "pos-a", marketDataDigest = "mkt-a")
        val target = manifest(positionDigest = "pos-b", marketDataDigest = "mkt-b")
        val basePositions = listOf(position("AAPL"))
        val targetPositions = listOf(position("AAPL", quantity = "200"))
        val baseRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "hash-1"))
        val targetRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "hash-2"))
        val result = differ.computeInputChanges(base, target, basePositions, targetPositions, baseRefs, targetRefs)
        result.positionsChanged shouldBe true
        result.marketDataChanged shouldBe true
    }

    test("classifies position as ADDED when only in target") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val targetPositions = listOf(position("MSFT"))
        val result = differ.computeInputChanges(base, target, emptyList(), targetPositions, emptyList(), emptyList())
        result.positionChanges shouldHaveSize 1
        result.positionChanges[0].instrumentId shouldBe "MSFT"
        result.positionChanges[0].changeType shouldBe PositionInputChangeType.ADDED
        result.positionChanges[0].baseQuantity.shouldBeNull()
        result.positionChanges[0].targetQuantity shouldBe BigDecimal("100")
    }

    test("classifies position as REMOVED when only in base") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val basePositions = listOf(position("TSLA"))
        val result = differ.computeInputChanges(base, target, basePositions, emptyList(), emptyList(), emptyList())
        result.positionChanges shouldHaveSize 1
        result.positionChanges[0].instrumentId shouldBe "TSLA"
        result.positionChanges[0].changeType shouldBe PositionInputChangeType.REMOVED
        result.positionChanges[0].targetQuantity.shouldBeNull()
        result.positionChanges[0].baseQuantity shouldBe BigDecimal("100")
    }

    test("classifies QUANTITY_CHANGED when only quantity differs") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val basePositions = listOf(position("AAPL", quantity = "100", marketPrice = "50.00"))
        val targetPositions = listOf(position("AAPL", quantity = "200", marketPrice = "50.00"))
        val result = differ.computeInputChanges(base, target, basePositions, targetPositions, emptyList(), emptyList())
        result.positionChanges shouldHaveSize 1
        result.positionChanges[0].changeType shouldBe PositionInputChangeType.QUANTITY_CHANGED
    }

    test("classifies PRICE_CHANGED when only market price differs") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val basePositions = listOf(position("AAPL", quantity = "100", marketPrice = "50.00"))
        val targetPositions = listOf(position("AAPL", quantity = "100", marketPrice = "55.00"))
        val result = differ.computeInputChanges(base, target, basePositions, targetPositions, emptyList(), emptyList())
        result.positionChanges shouldHaveSize 1
        result.positionChanges[0].changeType shouldBe PositionInputChangeType.PRICE_CHANGED
    }

    test("classifies BOTH_CHANGED when quantity and price both differ") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val basePositions = listOf(position("AAPL", quantity = "100", marketPrice = "50.00"))
        val targetPositions = listOf(position("AAPL", quantity = "200", marketPrice = "55.00"))
        val result = differ.computeInputChanges(base, target, basePositions, targetPositions, emptyList(), emptyList())
        result.positionChanges shouldHaveSize 1
        result.positionChanges[0].changeType shouldBe PositionInputChangeType.BOTH_CHANGED
    }

    test("computes quantityDelta as target minus base") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val basePositions = listOf(position("AAPL", quantity = "100"))
        val targetPositions = listOf(position("AAPL", quantity = "250"))
        val result = differ.computeInputChanges(base, target, basePositions, targetPositions, emptyList(), emptyList())
        result.positionChanges[0].quantityDelta shouldBe BigDecimal("150")
    }

    test("classifies market data as CHANGED when content hash differs") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        val baseRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "hash-1"))
        val targetRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "hash-2"))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), baseRefs, targetRefs)
        result.marketDataChanges shouldHaveSize 1
        result.marketDataChanges[0].changeType shouldBe MarketDataInputChangeType.CHANGED
        result.marketDataChanges[0].dataType shouldBe "PRICE"
        result.marketDataChanges[0].instrumentId shouldBe "AAPL"
    }

    test("ignores market data refs with identical content hash") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        val baseRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "same-hash"))
        val targetRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "same-hash"))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), baseRefs, targetRefs)
        result.marketDataChanges.shouldBeEmpty()
    }

    test("classifies market data as ADDED when only in target") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        val targetRefs = listOf(marketDataRef("VOL_SURFACE", "AAPL"))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), emptyList(), targetRefs)
        result.marketDataChanges shouldHaveSize 1
        result.marketDataChanges[0].changeType shouldBe MarketDataInputChangeType.ADDED
        result.marketDataChanges[0].baseContentHash.shouldBeNull()
    }

    test("classifies market data as REMOVED when only in base") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        val baseRefs = listOf(marketDataRef("YIELD_CURVE", "USD"))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), baseRefs, emptyList())
        result.marketDataChanges shouldHaveSize 1
        result.marketDataChanges[0].changeType shouldBe MarketDataInputChangeType.REMOVED
        result.marketDataChanges[0].targetContentHash.shouldBeNull()
    }

    test("classifies BECAME_AVAILABLE when base MISSING and target FETCHED") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        val baseRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "", status = MarketDataSnapshotStatus.MISSING))
        val targetRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "hash-new", status = MarketDataSnapshotStatus.FETCHED))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), baseRefs, targetRefs)
        result.marketDataChanges shouldHaveSize 1
        result.marketDataChanges[0].changeType shouldBe MarketDataInputChangeType.BECAME_AVAILABLE
    }

    test("classifies BECAME_MISSING when base FETCHED and target MISSING") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        val baseRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "hash-old", status = MarketDataSnapshotStatus.FETCHED))
        val targetRefs = listOf(marketDataRef("PRICE", "AAPL", contentHash = "", status = MarketDataSnapshotStatus.MISSING))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), baseRefs, targetRefs)
        result.marketDataChanges shouldHaveSize 1
        result.marketDataChanges[0].changeType shouldBe MarketDataInputChangeType.BECAME_MISSING
    }

    test("treats empty-string contentHash as MISSING status") {
        val base = manifest(marketDataDigest = "mkt-a")
        val target = manifest(marketDataDigest = "mkt-b")
        // base has empty hash (effectively MISSING), target has real hash (FETCHED)
        val baseRefs = listOf(marketDataRef("PRICE", "GOOG", contentHash = "", status = MarketDataSnapshotStatus.MISSING))
        val targetRefs = listOf(marketDataRef("PRICE", "GOOG", contentHash = "hash-xyz", status = MarketDataSnapshotStatus.FETCHED))
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), baseRefs, targetRefs)
        result.marketDataChanges shouldHaveSize 1
        result.marketDataChanges[0].changeType shouldBe MarketDataInputChangeType.BECAME_AVAILABLE
    }

    test("handles empty position and market data lists") {
        val base = manifest(positionDigest = "pos-a", marketDataDigest = "mkt-a")
        val target = manifest(positionDigest = "pos-b", marketDataDigest = "mkt-b")
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), emptyList(), emptyList())
        result.positionChanges.shouldBeEmpty()
        result.marketDataChanges.shouldBeEmpty()
        result.positionsChanged shouldBe true
        result.marketDataChanged shouldBe true
    }

    test("detects model version change") {
        val base = manifest(modelVersion = "v1.0")
        val target = manifest(modelVersion = "v2.0")
        val result = differ.computeInputChanges(base, target, emptyList(), emptyList(), emptyList(), emptyList())
        result.modelVersionChanged shouldBe true
        result.baseModelVersion shouldBe "v1.0"
        result.targetModelVersion shouldBe "v2.0"
    }

    test("sorts position changes by absolute quantity delta descending") {
        val base = manifest(positionDigest = "pos-a")
        val target = manifest(positionDigest = "pos-b")
        val basePositions = listOf(
            position("AAPL", quantity = "100"),
            position("MSFT", quantity = "100"),
            position("GOOG", quantity = "100"),
        )
        val targetPositions = listOf(
            position("AAPL", quantity = "110"),   // delta = 10
            position("MSFT", quantity = "500"),   // delta = 400
            position("GOOG", quantity = "150"),   // delta = 50
        )
        val result = differ.computeInputChanges(base, target, basePositions, targetPositions, emptyList(), emptyList())
        result.positionChanges shouldHaveSize 3
        result.positionChanges[0].instrumentId shouldBe "MSFT"
        result.positionChanges[1].instrumentId shouldBe "GOOG"
        result.positionChanges[2].instrumentId shouldBe "AAPL"
    }
})
