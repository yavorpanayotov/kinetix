package com.kinetix.risk.cache

import com.kinetix.risk.routes.dtos.CannedStressResultResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun sample(
    bookId: String = "equity-growth",
    deltaPv: String = "-12345.67",
) = CannedStressResultResponse(
    bookId = bookId,
    scenario = "+100BPS_PARALLEL",
    deltaPv = deltaPv,
    asOf = "2026-05-28T08:00:00Z",
)

class RedisCannedStressCacheIntegrationTest : FunSpec({

    val connection = RedisTestSetup.start()
    val cache: CannedStressCache = RedisCannedStressCache(connection)

    beforeEach {
        connection.sync().flushall()
    }

    test("should store and retrieve a canned-stress result") {
        cache.put("equity-growth", sample(deltaPv = "-12345.67"))

        val cached = cache.get("equity-growth")
        cached.shouldNotBeNull()
        cached.bookId shouldBe "equity-growth"
        cached.scenario shouldBe "+100BPS_PARALLEL"
        cached.deltaPv shouldBe "-12345.67"
        cached.asOf shouldBe "2026-05-28T08:00:00Z"
    }

    test("should return null for missing book") {
        cache.get("does-not-exist").shouldBeNull()
    }

    test("should overwrite existing entry for the same book") {
        cache.put("equity-growth", sample(deltaPv = "-100.00"))
        cache.put("equity-growth", sample(deltaPv = "-200.00"))

        cache.get("equity-growth")!!.deltaPv shouldBe "-200.00"
    }

    test("should respect TTL") {
        val shortTtlCache = RedisCannedStressCache(connection, ttlSeconds = 1L)
        shortTtlCache.put("equity-growth", sample())
        shortTtlCache.get("equity-growth").shouldNotBeNull()

        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (shortTtlCache.get("equity-growth") == null) break
            Thread.sleep(50)
        }
        shortTtlCache.get("equity-growth").shouldBeNull()
    }

    test("should isolate entries per book") {
        cache.put("equity-growth", sample(bookId = "equity-growth", deltaPv = "-100.00"))
        cache.put("macro-hedge", sample(bookId = "macro-hedge", deltaPv = "-2000.00"))

        cache.get("equity-growth")!!.deltaPv shouldBe "-100.00"
        cache.get("macro-hedge")!!.deltaPv shouldBe "-2000.00"
    }
})
