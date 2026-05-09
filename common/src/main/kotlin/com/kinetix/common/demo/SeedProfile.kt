package com.kinetix.common.demo

sealed class SeedProfile(val id: String, val implemented: Boolean) {

    object MultiAsset : SeedProfile("multi-asset", implemented = true)
    object EquityLS : SeedProfile("equity-ls", implemented = true)
    object OptionsBook : SeedProfile("options-book", implemented = true)
    object Stress : SeedProfile("stress", implemented = true)
    object Regulatory : SeedProfile("regulatory", implemented = false)

    companion object {
        fun all(): List<SeedProfile> = listOf(MultiAsset, EquityLS, OptionsBook, Stress, Regulatory)

        fun default(): SeedProfile = MultiAsset

        fun parse(id: String): SeedProfile {
            val normalised = id.trim().lowercase()
            return all().firstOrNull { it.id == normalised } ?: throw UnknownScenarioException(id)
        }

        fun parseOrDefault(id: String?): SeedProfile {
            if (id.isNullOrBlank()) return default()
            return parse(id)
        }
    }
}

class UnknownScenarioException(val scenario: String) :
    IllegalArgumentException("Unknown scenario: $scenario")
