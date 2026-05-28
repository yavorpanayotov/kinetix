package com.kinetix.refdata.netting

/**
 * Collateralised netting agreement for a counterparty relationship.
 *
 * [coverageRatio] is `covered_exposure / gross_exposure` — must be in
 * `[0, 1]`. Anything outside that range implies double-counting in
 * the upstream feed.
 *
 * [haircuts] are per-collateral-type discount fractions applied to
 * the posted collateral notional. Each must be in `[0, 1]`: a haircut
 * above 100% would make the collateral worth less than zero, which is
 * nonsense; a negative haircut would mean the collateral is worth
 * more than face value, also nonsense.
 *
 * The validation runs at construction so a malformed reference-data
 * record cannot propagate into the risk calc.
 */
data class NettingAgreement(
    val counterpartyId: String,
    val coverageRatio: Double,
    val haircuts: Map<String, Double>,
) {
    init {
        require(counterpartyId.isNotEmpty()) {
            "NettingAgreement: counterpartyId must not be empty"
        }
        require(coverageRatio in 0.0..1.0) {
            "NettingAgreement: coverageRatio $coverageRatio is outside [0, 1]"
        }
        for ((collateral, haircut) in haircuts) {
            require(haircut in 0.0..1.0) {
                "NettingAgreement: haircut for $collateral ($haircut) is outside [0, 1]"
            }
        }
    }
}
