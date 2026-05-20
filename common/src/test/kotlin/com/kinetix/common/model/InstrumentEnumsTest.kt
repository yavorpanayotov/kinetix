package com.kinetix.common.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Specifies the four shared instrument enums declared in `core.allium:136-144`.
 *
 * These types are the canonical core enums so OptionType / ExerciseStyle /
 * BondSeniority / SwapDirection stop being typo-prone raw strings. Each block
 * proves the declared member set and a JSON serialization round-trip.
 */
class InstrumentEnumsTest : FunSpec({

    test("OptionType declares exactly the core.allium members") {
        OptionType.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("CALL", "PUT")
    }

    test("OptionType resolves every declared member by name") {
        OptionType.entries.forEach { member ->
            OptionType.valueOf(member.name) shouldBe member
        }
    }

    test("OptionType round-trips through JSON serialization") {
        OptionType.entries.forEach { member ->
            val json = Json.encodeToString(member)
            Json.decodeFromString<OptionType>(json) shouldBe member
        }
    }

    test("ExerciseStyle declares exactly the core.allium members") {
        ExerciseStyle.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("AMERICAN", "EUROPEAN")
    }

    test("ExerciseStyle resolves every declared member by name") {
        ExerciseStyle.entries.forEach { member ->
            ExerciseStyle.valueOf(member.name) shouldBe member
        }
    }

    test("ExerciseStyle round-trips through JSON serialization") {
        ExerciseStyle.entries.forEach { member ->
            val json = Json.encodeToString(member)
            Json.decodeFromString<ExerciseStyle>(json) shouldBe member
        }
    }

    test("BondSeniority declares exactly the core.allium members") {
        BondSeniority.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("SENIOR_SECURED", "SENIOR_UNSECURED", "SUBORDINATED")
    }

    test("BondSeniority resolves every declared member by name") {
        BondSeniority.entries.forEach { member ->
            BondSeniority.valueOf(member.name) shouldBe member
        }
    }

    test("BondSeniority round-trips through JSON serialization") {
        BondSeniority.entries.forEach { member ->
            val json = Json.encodeToString(member)
            Json.decodeFromString<BondSeniority>(json) shouldBe member
        }
    }

    test("SwapDirection declares exactly the core.allium members") {
        SwapDirection.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("PAY_FIXED", "RECEIVE_FIXED")
    }

    test("SwapDirection resolves every declared member by name") {
        SwapDirection.entries.forEach { member ->
            SwapDirection.valueOf(member.name) shouldBe member
        }
    }

    test("SwapDirection round-trips through JSON serialization") {
        SwapDirection.entries.forEach { member ->
            val json = Json.encodeToString(member)
            Json.decodeFromString<SwapDirection>(json) shouldBe member
        }
    }
})
