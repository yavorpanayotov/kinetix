package com.kinetix.risk.kafka

import com.kinetix.common.kafka.KafkaCorrelationIdHeaderWriter
import com.kinetix.common.kafka.events.ConcentrationItem
import com.kinetix.common.kafka.events.RiskResultEvent
import com.kinetix.risk.model.BudgetUtilisation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

/**
 * Publishes a [RiskResultEvent] to `risk.results` when a hierarchy entity's VaR
 * breaches its allocated risk budget.
 *
 * The event carries a sentinel [ConcentrationItem] with
 * [instrumentId] = "VAR_BUDGET" and percentage = utilisation %. The notification-service
 * `BudgetBreachExtractor` recognises this sentinel and fires the
 * RISK_BUDGET_EXCEEDED alert type for any matching alert rules.
 *
 * Spec: hierarchy-risk.allium AlertOnBudgetBreach.
 */
class KafkaBudgetBreachAlertPublisher(
    private val producer: KafkaProducer<String, String>,
    private val topic: String = "risk.results",
) : BudgetBreachAlertPublisher {

    private val logger = LoggerFactory.getLogger(KafkaBudgetBreachAlertPublisher::class.java)

    override suspend fun publishBreach(utilisation: BudgetUtilisation) {
        val partitionKey = "${utilisation.entityLevel.name}:${utilisation.entityId}"
        val event = RiskResultEvent(
            bookId = partitionKey,
            varValue = utilisation.currentVar.toString(),
            expectedShortfall = "0.0",
            calculationType = "BUDGET_BREACH",
            calculatedAt = utilisation.updatedAt.toString(),
            concentrationByInstrument = listOf(
                ConcentrationItem(
                    instrumentId = BUDGET_BREACH_SENTINEL_ID,
                    percentage = utilisation.utilisationPct.toDouble(),
                ),
            ),
        )
        val json = Json.encodeToString(event)
        val record = KafkaCorrelationIdHeaderWriter.withCorrelationId(
            ProducerRecord(topic, partitionKey, json)
        )
        try {
            withContext(Dispatchers.IO) {
                producer.send(record).get()
            }
            logger.info(
                "Published RISK_BUDGET_EXCEEDED alert for {} {}: utilisation={}%, currentVar={}",
                utilisation.entityLevel, utilisation.entityId, utilisation.utilisationPct, utilisation.currentVar,
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to publish RISK_BUDGET_EXCEEDED alert for {} {}: {}",
                utilisation.entityLevel, utilisation.entityId, e.message,
            )
        }
    }

    companion object {
        const val BUDGET_BREACH_SENTINEL_ID = "VAR_BUDGET"
    }
}
