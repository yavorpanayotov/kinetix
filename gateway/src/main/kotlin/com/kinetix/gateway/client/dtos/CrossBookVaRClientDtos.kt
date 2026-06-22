package com.kinetix.gateway.client.dtos

import com.kinetix.gateway.client.BookVaRContributionSummary
import com.kinetix.gateway.client.ComponentBreakdownItem
import com.kinetix.gateway.client.CrossBookVaRResultSummary
import com.kinetix.gateway.client.GreeksResultDto
import com.kinetix.gateway.client.toDomain
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class CrossBookVaRRequestClientDto(
    val bookIds: List<String>,
    val portfolioGroupId: String,
    val calculationType: String? = null,
    val confidenceLevel: String? = null,
    val timeHorizonDays: String? = null,
    val numSimulations: String? = null,
)

@Serializable
data class BookVaRContributionClientDto(
    val bookId: String,
    val varContribution: String,
    val percentageOfTotal: String,
    val standaloneVar: String,
    val diversificationBenefit: String,
    val marginalVar: String = "0.0",
    val incrementalVar: String = "0.0",
)

@Serializable
data class CrossBookVaRResultClientDto(
    val portfolioGroupId: String,
    val bookIds: List<String>,
    val calculationType: String,
    val confidenceLevel: String,
    val varValue: String,
    val expectedShortfall: String,
    val componentBreakdown: List<ComponentBreakdownClientDto>,
    val bookContributions: List<BookVaRContributionClientDto>,
    val totalStandaloneVar: String,
    val diversificationBenefit: String,
    val calculatedAt: String,
    val greeks: GreeksResultDto? = null,
)

@Serializable
data class ComponentBreakdownClientDto(
    val assetClass: String,
    val varContribution: String,
    val percentageOfTotal: String,
)

fun CrossBookVaRResultClientDto.toDomain() = CrossBookVaRResultSummary(
    portfolioGroupId = portfolioGroupId,
    bookIds = bookIds,
    calculationType = calculationType,
    confidenceLevel = confidenceLevel,
    varValue = varValue.toDouble(),
    expectedShortfall = expectedShortfall.toDouble(),
    componentBreakdown = componentBreakdown.map {
        ComponentBreakdownItem(
            assetClass = it.assetClass,
            varContribution = it.varContribution.toDouble(),
            percentageOfTotal = it.percentageOfTotal.toDouble(),
        )
    },
    bookContributions = bookContributions.map {
        BookVaRContributionSummary(
            bookId = it.bookId,
            varContribution = it.varContribution.toDouble(),
            percentageOfTotal = it.percentageOfTotal.toDouble(),
            standaloneVar = it.standaloneVar.toDouble(),
            diversificationBenefit = it.diversificationBenefit.toDouble(),
            marginalVar = it.marginalVar.toDoubleOrNull() ?: 0.0,
            incrementalVar = it.incrementalVar.toDoubleOrNull() ?: 0.0,
        )
    },
    totalStandaloneVar = totalStandaloneVar.toDouble(),
    diversificationBenefit = diversificationBenefit.toDouble(),
    calculatedAt = Instant.parse(calculatedAt),
    greeks = greeks?.toDomain(),
)
