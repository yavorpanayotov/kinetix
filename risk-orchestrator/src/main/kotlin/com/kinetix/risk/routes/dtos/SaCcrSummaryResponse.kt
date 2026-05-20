package com.kinetix.risk.routes.dtos

import kotlinx.serialization.Serializable

/**
 * HTTP response for a counterparty-wide SA-CCR (BCBS 279) calculation.
 *
 * Per spec rule `CalculateSaCcr` (counterparty-risk.allium), SA-CCR runs per
 * netting set: trades are partitioned by their real ISDA/GMRA netting agreement
 * and EAD is computed independently for each.  This response carries one
 * [SaCcrResponse] per netting set, plus the total EAD summed across them — the
 * counterparty's aggregate regulatory exposure at default.
 */
@Serializable
data class SaCcrSummaryResponse(
    val counterpartyId: String,
    val totalEad: Double,
    val nettingSets: List<SaCcrResponse>,
)
