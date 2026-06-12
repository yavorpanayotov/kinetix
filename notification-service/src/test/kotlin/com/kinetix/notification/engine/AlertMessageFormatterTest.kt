package com.kinetix.notification.engine

import com.kinetix.notification.model.AlertType
import com.kinetix.notification.model.ComparisonOperator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AlertMessageFormatterTest : FunSpec({

    test("formats a VaR breach with thousands separators instead of raw enums and scientific notation") {
        formatAlertMessage(
            ruleName = "VaR Breach Alert",
            type = AlertType.VAR_BREACH,
            operator = ComparisonOperator.GREATER_THAN,
            threshold = 1_000_000.0,
            currentValue = 3.0447680258134153E7,
            bookId = "macro-hedge",
        ) shouldBe "VaR Breach Alert: VaR \$30,447,680 above \$1,000,000 limit (book macro-hedge)"
    }

    test("formats a P&L threshold breach below the limit") {
        formatAlertMessage(
            ruleName = "P&L Warning",
            type = AlertType.PNL_THRESHOLD,
            operator = ComparisonOperator.LESS_THAN,
            threshold = -150_000.0,
            currentValue = -180_000.0,
            bookId = "multi-asset",
        ) shouldBe "P&L Warning: P&L -\$180,000 below -\$150,000 limit (book multi-asset)"
    }

    test("formats ratio-valued concentration alerts as percentages") {
        formatAlertMessage(
            ruleName = "Concentration notice",
            type = AlertType.CONCENTRATION,
            operator = ComparisonOperator.GREATER_THAN,
            threshold = 0.4,
            currentValue = 0.42,
            bookId = "tech-momentum",
        ) shouldBe "Concentration notice: Concentration 42.0% above 40.0% limit (book tech-momentum)"
    }

    test("keeps two decimals for small monetary values") {
        formatAlertMessage(
            ruleName = "Delta watch",
            type = AlertType.DELTA_BREACH,
            operator = ComparisonOperator.GREATER_THAN,
            threshold = 500.5,
            currentValue = 612.25,
            bookId = "fx-desk",
        ) shouldBe "Delta watch: Delta \$612.25 above \$500.50 limit (book fx-desk)"
    }
})
