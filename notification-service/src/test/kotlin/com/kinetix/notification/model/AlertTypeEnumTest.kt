package com.kinetix.notification.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain

/**
 * The four canonical alert categories that the alert routing layer keys
 * off must exist on the [AlertType] enum: RISK_LIMIT for limit breaches,
 * MARGIN_CALL for margin shortfalls, SETTLEMENT_FAILS for failed FX/bond
 * settlements, and DATA_STALE for stale market-data feeds. These four
 * map deterministically to escalation tiers (via [AlertSeverity]) and
 * to the dispatch templates that the delivery layer renders. The enum
 * may carry additional historical variants (VAR_BREACH, DELTA_BREACH,
 * etc.) — this test pins down the four routing-relevant categories.
 */
class AlertTypeEnumTest : FunSpec({
    test("AlertType declares RISK_LIMIT") {
        AlertType.entries shouldContain AlertType.RISK_LIMIT
    }
    test("AlertType declares MARGIN_CALL") {
        AlertType.entries shouldContain AlertType.MARGIN_CALL
    }
    test("AlertType declares SETTLEMENT_FAILS") {
        AlertType.entries shouldContain AlertType.SETTLEMENT_FAILS
    }
    test("AlertType declares DATA_STALE") {
        AlertType.entries shouldContain AlertType.DATA_STALE
    }
})
