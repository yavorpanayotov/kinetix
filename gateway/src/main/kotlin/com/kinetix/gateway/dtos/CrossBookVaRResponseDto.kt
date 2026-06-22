package com.kinetix.gateway.dtos

import com.kinetix.gateway.client.CrossBookVaRResultSummary
import kotlinx.serialization.Serializable

@Serializable
data class CrossBookVaRResponseDto(
    val portfolioGroupId: String,
    val bookIds: List<String>,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: String,
    val expectedShortfall: String,
    val componentBreakdown: List<ComponentBreakdownDto>,
    val bookContributions: List<BookVaRContributionDto>,
    val totalStandaloneVar: String,
    val diversificationBenefit: String,
    val calculatedAt: String,
    /** Firm-level aggregate greeks across the group; null when unavailable. */
    val greeks: GreeksResponse? = null,
)

fun CrossBookVaRResultSummary.toResponse(): CrossBookVaRResponseDto = CrossBookVaRResponseDto(
    portfolioGroupId = portfolioGroupId,
    bookIds = bookIds,
    calculationType = calculationType,
    confidenceLevel = confidenceLevel,
    varValue = "%.2f".format(varValue),
    expectedShortfall = "%.2f".format(expectedShortfall),
    componentBreakdown = componentBreakdown.map { it.toDto() },
    bookContributions = bookContributions.map {
        BookVaRContributionDto(
            bookId = it.bookId,
            varContribution = "%.2f".format(it.varContribution),
            percentageOfTotal = "%.2f".format(it.percentageOfTotal),
            standaloneVar = "%.2f".format(it.standaloneVar),
            diversificationBenefit = "%.2f".format(it.diversificationBenefit),
            marginalVar = "%.6f".format(it.marginalVar),
            incrementalVar = "%.2f".format(it.incrementalVar),
        )
    },
    totalStandaloneVar = "%.2f".format(totalStandaloneVar),
    diversificationBenefit = "%.2f".format(diversificationBenefit),
    calculatedAt = calculatedAt.toString(),
    greeks = greeks?.toResponse(),
)
