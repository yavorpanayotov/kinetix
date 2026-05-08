package com.kinetix.common.model.instrument

enum class InstrumentTypeCode {
    CASH_EQUITY,
    EQUITY_OPTION,
    EQUITY_FUTURE,
    GOVERNMENT_BOND,
    CORPORATE_BOND,
    FX_SPOT,
    FX_FORWARD,
    FX_OPTION,
    COMMODITY_FUTURE,
    COMMODITY_OPTION,
    INTEREST_RATE_SWAP;

    companion object {
        /**
         * Strict parser. Every persisted/transported instrument-type value must match
         * a real enum entry; the legacy 'UNKNOWN' sentinel is rejected so it cannot
         * round-trip through code that should produce a meaningful type.
         *
         * Throws [IllegalArgumentException] for null, blank, "UNKNOWN", or any value
         * that does not match an enum entry. Use [fromStringOrNull] only at wire-format
         * boundaries that must tolerate older messages without an instrumentType.
         */
        fun fromString(value: String?): InstrumentTypeCode {
            require(!value.isNullOrBlank()) { "instrumentType is required, was null or blank" }
            require(value != "UNKNOWN") { "instrumentType 'UNKNOWN' is not allowed; provide a real type code" }
            return entries.find { it.name == value }
                ?: throw IllegalArgumentException("Unknown instrumentType: '$value'")
        }

        /**
         * Lenient parser for wire-format ingress where older messages may carry no
         * instrumentType. Returns null for null/blank/"UNKNOWN" and any value not in
         * the enum. Production paths that build domain objects must use [fromString].
         */
        fun fromStringOrNull(value: String?): InstrumentTypeCode? = when {
            value.isNullOrBlank() -> null
            value == "UNKNOWN" -> null
            else -> entries.find { it.name == value }
        }
    }
}
