package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.events.BookVaRContributionEvent
import com.kinetix.common.kafka.events.ComponentBreakdownEvent
import com.kinetix.common.kafka.events.CrossBookRiskResultEvent
import com.kinetix.risk.model.CrossBookValuationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class KafkaCrossBookRiskResultPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.cross-book-results",
) : CrossBookRiskResultPublisher {

    private val logger = LoggerFactory.getLogger(KafkaCrossBookRiskResultPublisher::class.java)

    override suspend fun publish(result: CrossBookValuationResult, correlationId: String?) {
        val event = CrossBookRiskResultEvent(
            portfolioGroupId = result.portfolioGroupId,
            bookIds = result.bookIds.map { it.value },
            varValue = "%.2f".format(result.varValue),
            expectedShortfall = "%.2f".format(result.expectedShortfall),
            calculationType = result.calculationType.name,
            confidenceLevel = result.confidenceLevel.name,
            componentBreakdown = result.componentBreakdown.map {
                ComponentBreakdownEvent(
                    assetClass = it.assetClass.name,
                    varContribution = it.varContribution.toString(),
                    percentageOfTotal = it.percentageOfTotal.toString(),
                )
            },
            bookContributions = result.bookContributions.map {
                BookVaRContributionEvent(
                    bookId = it.bookId.value,
                    varContribution = "%.2f".format(it.varContribution),
                    percentageOfTotal = "%.2f".format(it.percentageOfTotal),
                    standaloneVar = "%.2f".format(it.standaloneVar),
                    diversificationBenefit = "%.2f".format(it.diversificationBenefit),
                    marginalVar = "%.6f".format(it.marginalVar),
                    incrementalVar = "%.2f".format(it.incrementalVar),
                )
            },
            totalStandaloneVar = "%.2f".format(result.totalStandaloneVar),
            diversificationBenefit = "%.2f".format(result.diversificationBenefit),
            calculatedAt = result.calculatedAt.toString(),
            correlationId = correlationId,
        )
        val json = Json.encodeToString(event)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, result.portfolioGroupId, json)
        )

        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to publish cross-book risk result to Kafka: groupId={}, topic={}",
                result.portfolioGroupId, topic, e,
            )
        }
    }
}
