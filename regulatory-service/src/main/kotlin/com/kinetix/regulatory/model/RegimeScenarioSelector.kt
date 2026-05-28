package com.kinetix.regulatory.model

/**
 * Selects the stress-scenario library appropriate to the current market
 * regime. Lower-tier regimes include only the standard scenarios because
 * adding GFC / SVB-style replays in a calm market mostly burns compute;
 * higher-tier regimes pull in additional historical replays so the risk
 * picture matches what regulators expect to see during stress.
 *
 * The selection is strictly monotonic: scenariosFor(ELEVATED) is a strict
 * superset of scenariosFor(NORMAL), and so on up to CRISIS. The contract
 * is pinned by [RegimeBasedScenarioSelectionTest].
 */
object RegimeScenarioSelector {

    private val BASE: List<String> = listOf(
        "base-1pct-rates",
        "base-equity-shock",
    )

    private val ELEVATED_ADD: List<String> = listOf(
        "taper-tantrum-2013",
        "rates-spike-200bp",
    )

    private val STRESSED_ADD: List<String> = listOf(
        "gfc-2008",
        "covid-2020",
    )

    private val CRISIS_ADD: List<String> = listOf(
        "volmageddon-2018",
        "svb-2023",
        "ltcm-1998",
    )

    fun scenariosFor(regime: MarketRegime): List<String> = when (regime) {
        MarketRegime.NORMAL -> BASE
        MarketRegime.ELEVATED -> BASE + ELEVATED_ADD
        MarketRegime.STRESSED -> BASE + ELEVATED_ADD + STRESSED_ADD
        MarketRegime.CRISIS -> BASE + ELEVATED_ADD + STRESSED_ADD + CRISIS_ADD
    }
}
