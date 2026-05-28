package com.kinetix.refdata.netting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A NettingAgreement specifies the haircut percentages and the
 * cross-product coverage ratio (`covered_exposure / gross_exposure`)
 * for a counterparty's collateralised relationship. The platform
 * relies on the ratio being in [0, 1] (anything else implies double-
 * counting) and the haircuts being in [0, 1] (a haircut > 100% would
 * make the collateral worth less than zero, which is nonsense).
 *
 * This test pins the constructor-time validation: out-of-range values
 * throw at construction so a malformed reference-data record cannot
 * propagate into the risk calc.
 */
class NettingAgreementCoverageValidationTest : FunSpec({

    test("accepts a typical agreement (70% coverage, 5% cash haircut, 8% bond haircut)") {
        val nettingAgreement = NettingAgreement(
            counterpartyId = "CP-A",
            coverageRatio = 0.70,
            haircuts = mapOf("cash" to 0.05, "govt-bond" to 0.08),
        )
        nettingAgreement.coverageRatio shouldBe 0.70
        nettingAgreement.haircuts["cash"] shouldBe 0.05
    }

    test("accepts 0% and 100% coverage at the boundaries") {
        NettingAgreement(counterpartyId = "CP-A", coverageRatio = 0.0, haircuts = emptyMap())
        NettingAgreement(counterpartyId = "CP-A", coverageRatio = 1.0, haircuts = emptyMap())
    }

    test("rejects negative coverage ratio") {
        shouldThrow<IllegalArgumentException> {
            NettingAgreement(counterpartyId = "CP-A", coverageRatio = -0.01, haircuts = emptyMap())
        }
    }

    test("rejects coverage ratio above 1") {
        val ex = shouldThrow<IllegalArgumentException> {
            NettingAgreement(counterpartyId = "CP-A", coverageRatio = 1.05, haircuts = emptyMap())
        }
        ex.message!!.contains("coverageRatio") shouldBe true
    }

    test("rejects a haircut above 100% (collateral would be worth less than zero)") {
        shouldThrow<IllegalArgumentException> {
            NettingAgreement(
                counterpartyId = "CP-A",
                coverageRatio = 0.5,
                haircuts = mapOf("cash" to 1.5),
            )
        }
    }

    test("rejects a negative haircut (collateral cannot be worth more than face)") {
        shouldThrow<IllegalArgumentException> {
            NettingAgreement(
                counterpartyId = "CP-A",
                coverageRatio = 0.5,
                haircuts = mapOf("cash" to -0.05),
            )
        }
    }

    test("rejects an empty counterparty id") {
        shouldThrow<IllegalArgumentException> {
            NettingAgreement(counterpartyId = "", coverageRatio = 0.5, haircuts = emptyMap())
        }
    }
})
