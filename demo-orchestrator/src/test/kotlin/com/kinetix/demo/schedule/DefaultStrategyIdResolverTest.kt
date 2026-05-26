package com.kinetix.demo.schedule

import com.kinetix.demo.client.PositionServiceClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [DefaultStrategyIdResolver] — the HTTP-backed strategy
 * lookup that replaces the synthesized `"{bookId}-default"` id used before
 * kx-bg3.
 */
class DefaultStrategyIdResolverTest : FunSpec({

    test("returns the strategies listed by position-service for a known book") {
        val client = mockk<PositionServiceClient>()
        coEvery { client.listStrategies("equity-growth") } returns listOf(
            "equity-growth-core",
            "equity-growth-satellite",
        )
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            resolver.strategiesFor("equity-growth") shouldContainExactly listOf(
                "equity-growth-core",
                "equity-growth-satellite",
            )
        }
    }

    test("returns at least 2 strategies for every book that the seeder populates") {
        // Spec-level expectation: every seeded book gets >=2 named strategies
        // so the simulator can distribute trades. Bind a fake client to the
        // exact catalogue position-service's DevDataSeeder seeds (kx-bg3).
        val seeded: Map<String, List<String>> = mapOf(
            "equity-growth" to listOf("equity-growth-core", "equity-growth-satellite"),
            "tech-momentum" to listOf("tech-momentum-momentum", "tech-momentum-mean-reversion"),
            "fixed-income" to listOf("fixed-income-duration", "fixed-income-curve", "fixed-income-credit-overlay"),
        )
        val client = mockk<PositionServiceClient>()
        seeded.forEach { (book, ids) ->
            coEvery { client.listStrategies(book) } returns ids
        }
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            seeded.keys.forEach { book ->
                val result = resolver.strategiesFor(book)
                (result.size >= 2) shouldBe true
            }
        }
    }

    test("falls back to {bookId}-default when position-service throws") {
        val client = mockk<PositionServiceClient>()
        coEvery { client.listStrategies("equity-growth") } throws RuntimeException("connect timeout")
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            resolver.strategiesFor("equity-growth") shouldContainExactly listOf("equity-growth-default")
        }
    }

    test("falls back to {bookId}-default when position-service returns an empty list") {
        val client = mockk<PositionServiceClient>()
        coEvery { client.listStrategies("equity-growth") } returns emptyList()
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            resolver.strategiesFor("equity-growth") shouldContainExactly listOf("equity-growth-default")
        }
    }

    test("caches a successful response so subsequent calls do not hit the API") {
        val client = mockk<PositionServiceClient>()
        coEvery { client.listStrategies("equity-growth") } returns listOf(
            "equity-growth-core",
            "equity-growth-satellite",
        )
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            resolver.strategiesFor("equity-growth")
            resolver.strategiesFor("equity-growth")
            resolver.strategiesFor("equity-growth")
        }

        coVerify(exactly = 1) { client.listStrategies("equity-growth") }
    }

    test("does NOT cache the fallback so a future call can recover when the API comes back") {
        val client = mockk<PositionServiceClient>()
        // First call fails, second call succeeds.
        coEvery { client.listStrategies("equity-growth") } throws RuntimeException("connect timeout") andThen listOf(
            "equity-growth-core",
            "equity-growth-satellite",
        )
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            resolver.strategiesFor("equity-growth") shouldContainExactly listOf("equity-growth-default")
            // Second call gets the real list now that the service is back.
            resolver.strategiesFor("equity-growth") shouldContainExactly listOf(
                "equity-growth-core",
                "equity-growth-satellite",
            )
        }

        coVerify(exactly = 2) { client.listStrategies("equity-growth") }
    }

    test("overrides take precedence over the API and are not cached against the API") {
        val client = mockk<PositionServiceClient>()
        // Override for `derivatives-book`; everything else goes to the client.
        val resolver = DefaultStrategyIdResolver(
            positionClient = client,
            overrides = mapOf("derivatives-book" to listOf("manual-1", "manual-2")),
        )

        runTest {
            resolver.strategiesFor("derivatives-book") shouldContainExactly listOf("manual-1", "manual-2")
        }

        coVerify(exactly = 0) { client.listStrategies("derivatives-book") }
    }

    test("caches per-book independently") {
        val client = mockk<PositionServiceClient>()
        coEvery { client.listStrategies("equity-growth") } returns listOf("equity-growth-core")
        coEvery { client.listStrategies("fixed-income") } returns listOf("fixed-income-duration", "fixed-income-curve")
        val resolver = DefaultStrategyIdResolver(positionClient = client)

        runTest {
            resolver.strategiesFor("equity-growth") shouldHaveSize 1
            resolver.strategiesFor("fixed-income") shouldHaveSize 2
            // Re-query: both should hit the cache.
            resolver.strategiesFor("equity-growth") shouldHaveSize 1
            resolver.strategiesFor("fixed-income") shouldHaveSize 2
        }

        coVerify(exactly = 1) { client.listStrategies("equity-growth") }
        coVerify(exactly = 1) { client.listStrategies("fixed-income") }
    }
})
