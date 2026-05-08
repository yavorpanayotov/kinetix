package com.kinetix.common.demo

/**
 * Demo-mode counterparty tiers — single source of truth shared between
 * reference-data-service (which seeds the counterparty / netting agreement records)
 * and position-service (which assigns counterparties to seed trades subject to
 * instrument-type plausibility).
 *
 * Per the demo-review.md plan and trader peer review:
 *   - Buy-side and corporate names trade OTC instruments only.
 *   - CCPs trade listed instruments only.
 *   - G-SIBs and mid-tier banks can appear on any instrument type.
 *
 * The trade tape generator must enforce these restrictions or a credit-risk buyer
 * will flag the demo within 30 seconds.
 */
object CounterpartyTiers {

    val G_SIB_IDS: List<String> = listOf("CP-GS", "CP-JPM", "CP-BARC", "CP-DB", "CP-UBS", "CP-CITI")

    val MID_TIER_BANK_IDS: List<String> = listOf(
        "CP-WFC", "CP-BNP", "CP-SOCG", "CP-MIZ", "CP-NMR", "CP-RBC",
        "CP-ING", "CP-SAN", "CP-HAND", "CP-BBVA",
    )

    val CCP_IDS: List<String> = listOf("CP-LCH", "CP-CME", "CP-EUREX", "CP-ICE")

    val BUY_SIDE_IDS: List<String> = listOf("CP-BLK", "CP-BRDG", "CP-CITDL", "CP-MIL")

    val CORPORATE_IDS: List<String> = listOf("CP-AAPL", "CP-SHEL", "CP-TM", "CP-NESN", "CP-MSFT", "CP-BA")

    val ALL_IDS: List<String> = G_SIB_IDS + MID_TIER_BANK_IDS + CCP_IDS + BUY_SIDE_IDS + CORPORATE_IDS

    val OTC_ONLY_IDS: Set<String> = (BUY_SIDE_IDS + CORPORATE_IDS).toSet()
    val LISTED_ONLY_IDS: Set<String> = CCP_IDS.toSet()

    /** Bank counterparties (G-SIBs + mid-tier) that can appear on any instrument type. */
    val UNIVERSAL_BANK_IDS: List<String> = G_SIB_IDS + MID_TIER_BANK_IDS

    /**
     * Which counterparties are eligible for a given instrument type during demo
     * trade generation. Banks are universal; CCPs only appear on cleared/listed
     * derivatives; buy-side and corporates only on OTC swaps/forwards/options.
     */
    fun eligibleFor(instrumentType: String): List<String> {
        val isListedDerivative = instrumentType in LISTED_DERIVATIVE_TYPES
        val isOtc = instrumentType in OTC_TYPES
        return when {
            isListedDerivative -> UNIVERSAL_BANK_IDS + CCP_IDS
            isOtc -> UNIVERSAL_BANK_IDS + BUY_SIDE_IDS + CORPORATE_IDS
            else -> UNIVERSAL_BANK_IDS // cash equity, govt bond, FX spot — banks only for the demo
        }
    }

    private val LISTED_DERIVATIVE_TYPES = setOf("EQUITY_FUTURE", "COMMODITY_FUTURE", "EQUITY_OPTION")
    private val OTC_TYPES = setOf(
        "INTEREST_RATE_SWAP",
        "FX_FORWARD",
        "FX_OPTION",
        "CORPORATE_BOND", // OTC-traded
    )
}
