package com.kinetix.risk.cache

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.risk.model.BookVaRContribution
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ComponentBreakdown
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.CrossBookValuationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

private fun crossBookResult(
    groupId: String = "firm",
    varValue: Double = 100_000.0,
    bookIds: List<BookId> = listOf(BookId("book-a"), BookId("book-b")),
) = CrossBookValuationResult(
    portfolioGroupId = groupId,
    bookIds = bookIds,
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = varValue,
    expectedShortfall = varValue * 1.25,
    componentBreakdown = listOf(
        ComponentBreakdown(AssetClass.EQUITY, varValue * 0.7, 70.0),
        ComponentBreakdown(AssetClass.FIXED_INCOME, varValue * 0.3, 30.0),
    ),
    bookContributions = listOf(
        BookVaRContribution(
            bookId = BookId("book-a"),
            varContribution = varValue * 0.6,
            percentageOfTotal = 60.0,
            standaloneVar = varValue * 0.7,
            diversificationBenefit = varValue * 0.1,
            marginalVar = 0.0007,
            incrementalVar = varValue * 0.55,
        ),
    ),
    totalStandaloneVar = varValue * 1.3,
    diversificationBenefit = varValue * 0.3,
    calculatedAt = Instant.parse("2026-05-28T08:00:00Z"),
    modelVersion = "0.1.0",
    monteCarloSeed = 42L,
    jobId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
)

class RedisCrossBookVaRCacheIntegrationTest : FunSpec({

    val connection = RedisTestSetup.start()
    val cache: CrossBookVaRCache = RedisCrossBookVaRCache(connection)

    beforeEach {
        connection.sync().flushall()
    }

    test("should store and retrieve a cross-book result with full fidelity") {
        val original = crossBookResult(groupId = "firm", varValue = 182_293_572.06)

        cache.put("firm", original)

        val cached = cache.get("firm")
        cached.shouldNotBeNull()
        cached.portfolioGroupId shouldBe "firm"
        cached.bookIds shouldBe listOf(BookId("book-a"), BookId("book-b"))
        cached.calculationType shouldBe CalculationType.PARAMETRIC
        cached.confidenceLevel shouldBe ConfidenceLevel.CL_95
        cached.varValue shouldBe 182_293_572.06
        cached.expectedShortfall shouldBe 182_293_572.06 * 1.25
        cached.componentBreakdown.size shouldBe 2
        cached.componentBreakdown[0].assetClass shouldBe AssetClass.EQUITY
        cached.bookContributions.size shouldBe 1
        cached.bookContributions[0].bookId shouldBe BookId("book-a")
        cached.bookContributions[0].marginalVar shouldBe 0.0007
        cached.totalStandaloneVar shouldBe 182_293_572.06 * 1.3
        cached.calculatedAt shouldBe Instant.parse("2026-05-28T08:00:00Z")
        cached.modelVersion shouldBe "0.1.0"
        cached.monteCarloSeed shouldBe 42L
        cached.jobId shouldBe UUID.fromString("00000000-0000-0000-0000-000000000001")
    }

    test("should return null for missing group") {
        cache.get("does-not-exist").shouldBeNull()
    }

    test("should overwrite existing entry for the same group") {
        cache.put("firm", crossBookResult(varValue = 1_000.0))
        cache.put("firm", crossBookResult(varValue = 9_999.0))

        cache.get("firm")!!.varValue shouldBe 9_999.0
    }

    test("should respect TTL") {
        val shortTtlCache = RedisCrossBookVaRCache(connection, ttlSeconds = 1L)
        shortTtlCache.put("firm", crossBookResult())

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (shortTtlCache.get("firm") == null) break
            Thread.sleep(50)
        }
        shortTtlCache.get("firm").shouldBeNull()
    }

    test("should isolate entries per group") {
        cache.put("firm", crossBookResult(groupId = "firm", varValue = 1_000.0))
        cache.put("desk-fx", crossBookResult(groupId = "desk-fx", varValue = 2_000.0))

        cache.get("firm")!!.varValue shouldBe 1_000.0
        cache.get("desk-fx")!!.varValue shouldBe 2_000.0
    }
})
