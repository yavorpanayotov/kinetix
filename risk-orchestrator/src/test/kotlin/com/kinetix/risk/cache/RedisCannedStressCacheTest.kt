package com.kinetix.risk.cache

import com.kinetix.risk.routes.dtos.CannedStressResultResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk

/**
 * Unit tests for [RedisCannedStressCache] — verifies the cache survives Redis
 * connection failures gracefully (mirrors the [RedisVaRCache] safety
 * contract: writes are best-effort, reads return null on failure).
 */
class RedisCannedStressCacheTest : FunSpec({

    val sample = CannedStressResultResponse(
        bookId = "equity-growth",
        scenario = "+100BPS_PARALLEL",
        deltaPv = "-12345.67",
        asOf = "2026-05-28T08:00:00Z",
    )

    test("cache key includes schema version") {
        val key = "canned-stress:v${RedisCannedStressCache.CACHE_SCHEMA_VERSION}:equity-growth"
        key shouldBe "canned-stress:v1:equity-growth"
    }

    test("put completes normally when Redis throws RedisConnectionException") {
        val commands = mockk<RedisCommands<String, String>>()
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.sync() } returns commands
        every { commands.set(any(), any(), any()) } throws RedisConnectionException("connection refused")

        val cache = RedisCannedStressCache(connection)

        // Must not throw
        cache.put("equity-growth", sample)
    }

    test("get returns null when Redis throws RedisConnectionException") {
        val commands = mockk<RedisCommands<String, String>>()
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.sync() } returns commands
        every { commands.get(any()) } throws RedisConnectionException("connection refused")

        val cache = RedisCannedStressCache(connection)

        cache.get("equity-growth").shouldBeNull()
    }

    test("get returns null when Redis returns malformed JSON") {
        val commands = mockk<RedisCommands<String, String>>()
        val connection = mockk<StatefulRedisConnection<String, String>>()
        every { connection.sync() } returns commands
        every { commands.get(any()) } returns "{ not valid json"

        val cache = RedisCannedStressCache(connection)

        cache.get("equity-growth").shouldBeNull()
    }
})
