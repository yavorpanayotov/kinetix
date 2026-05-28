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
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

private fun minimalCrossBookResult(groupId: String = "firm") = CrossBookValuationResult(
    portfolioGroupId = groupId,
    bookIds = listOf(BookId("book-a"), BookId("book-b")),
    calculationType = CalculationType.PARAMETRIC,
    confidenceLevel = ConfidenceLevel.CL_95,
    varValue = 100_000.0,
    expectedShortfall = 125_000.0,
    componentBreakdown = listOf(
        ComponentBreakdown(AssetClass.EQUITY, 80_000.0, 80.0),
    ),
    bookContributions = listOf(
        BookVaRContribution(BookId("book-a"), 60_000.0, 60.0, 70_000.0, 10_000.0),
    ),
    totalStandaloneVar = 130_000.0,
    diversificationBenefit = 30_000.0,
    calculatedAt = Instant.parse("2026-05-28T08:00:00Z"),
    modelVersion = "0.1.0",
    monteCarloSeed = 42L,
    jobId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
)

class RedisCrossBookVaRCacheTest : FunSpec({

    test("cache key includes schema version and group id") {
        val key = "cross-book-var:v${RedisCrossBookVaRCache.CACHE_SCHEMA_VERSION}:firm"
        key shouldBe "cross-book-var:v1:firm"
    }

    test("put completes normally when Redis throws RedisConnectionException") {
        val commands = mockk<RedisCommands<String, String>>()
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.sync() } returns commands
        every { commands.set(any(), any(), any()) } throws RedisConnectionException("connection refused")

        val cache = RedisCrossBookVaRCache(connection)

        cache.put("firm", minimalCrossBookResult())
    }

    test("get returns null when Redis throws RedisConnectionException") {
        val commands = mockk<RedisCommands<String, String>>()
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.sync() } returns commands
        every { commands.get(any()) } throws RedisConnectionException("connection refused")

        val cache = RedisCrossBookVaRCache(connection)

        cache.get("firm").shouldBeNull()
    }

    test("get returns null when Redis returns malformed JSON") {
        val commands = mockk<RedisCommands<String, String>>()
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.sync() } returns commands
        every { commands.get(any()) } returns "not json"

        val cache = RedisCrossBookVaRCache(connection)

        cache.get("firm").shouldBeNull()
    }
})
