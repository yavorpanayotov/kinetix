package com.kinetix.demo.profile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class DemoBookProfilesTest : FunSpec({

    // Canonical book ids seeded by risk-orchestrator's DevDataSeeder. The plan and
    // DevDataSeeder must agree on these — if they ever drift, this test fails loudly.
    val seededBookIds = setOf(
        "equity-growth",
        "tech-momentum",
        "emerging-markets",
        "fixed-income",
        "multi-asset",
        "macro-hedge",
        "balanced-income",
        "derivatives-book",
    )

    test("exposes exactly one profile per seeded book") {
        DemoBookProfiles.all().size shouldBe seededBookIds.size
    }

    test("covers every seeded book id with no duplicates") {
        val ids = DemoBookProfiles.all().map { it.bookId }
        ids.toSet().size shouldBe ids.size
        ids shouldContainExactlyInAnyOrder seededBookIds.toList()
    }

    test("every profile has trade probability within [0, 1]") {
        DemoBookProfiles.all().forEach { profile ->
            withClue(profile.bookId) {
                (profile.tradeProbability in 0.0..1.0) shouldBe true
            }
        }
    }

    test("every profile has a non-empty instrument list") {
        DemoBookProfiles.all().forEach { profile ->
            withClue(profile.bookId) {
                profile.instrumentIds.isNotEmpty() shouldBe true
            }
        }
    }

    test("every profile has a non-inverted notional range") {
        DemoBookProfiles.all().forEach { profile ->
            withClue(profile.bookId) {
                (profile.notionalRangeUsd.start <= profile.notionalRangeUsd.endInclusive) shouldBe true
            }
        }
    }

    test("every profile has a positive minimum notional") {
        DemoBookProfiles.all().forEach { profile ->
            withClue(profile.bookId) {
                (profile.notionalRangeUsd.start > 0L) shouldBe true
            }
        }
    }

    test("every profile declares a non-blank asset class") {
        DemoBookProfiles.all().forEach { profile ->
            withClue(profile.bookId) {
                profile.assetClass.isNotBlank() shouldBe true
            }
        }
    }

    test("rate-oriented books carry bond instruments so KRD has fixed-income positions to compute (kx-l8s7)") {
        // KRD is only computed for FIXED_INCOME (bond) positions. The
        // rate-oriented demo books must therefore trade at least one instrument
        // that the taxonomy classifies as a government bond, otherwise
        // GET /api/v1/risk/krd/{book} returns empty instruments/aggregated.
        val rateBooks = listOf("fixed-income", "macro-hedge")
        rateBooks.forEach { bookId ->
            val profile = DemoBookProfiles.forBook(bookId)
            profile.shouldNotBeNull()
            val bondInstruments = profile.instrumentIds.filter { id ->
                com.kinetix.demo.schedule.DemoInstrumentTaxonomy.classify(id)
                    ?.instrumentType == "GOVERNMENT_BOND"
            }
            withClue("$bookId must trade at least one government bond for KRD") {
                bondInstruments.isNotEmpty() shouldBe true
            }
        }
    }

    test("forBook returns the matching profile for a known book") {
        val profile = DemoBookProfiles.forBook("tech-momentum")
        profile.shouldNotBeNull()
        profile.bookId shouldBe "tech-momentum"
    }

    test("forBook returns null for an unknown book id") {
        DemoBookProfiles.forBook("nonexistent-book") shouldBe null
    }

    test("DemoBookProfile rejects a trade probability above 1.0") {
        shouldThrow<IllegalArgumentException> {
            DemoBookProfile(
                bookId = "x",
                tradeProbability = 1.5,
                instrumentIds = listOf("FOO"),
                notionalRangeUsd = 1L..2L,
                assetClass = "EQUITY",
            )
        }
    }

    test("DemoBookProfile rejects a negative trade probability") {
        shouldThrow<IllegalArgumentException> {
            DemoBookProfile(
                bookId = "x",
                tradeProbability = -0.1,
                instrumentIds = listOf("FOO"),
                notionalRangeUsd = 1L..2L,
                assetClass = "EQUITY",
            )
        }
    }
})
