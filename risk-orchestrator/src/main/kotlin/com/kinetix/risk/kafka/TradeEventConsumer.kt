package com.kinetix.risk.kafka

import com.kinetix.common.kafka.RetryableConsumer
import com.kinetix.common.kafka.events.TradeEventMessage
import com.kinetix.common.model.BookId
import com.kinetix.risk.cache.VaRCache
import com.kinetix.risk.client.PositionProvider
import com.kinetix.risk.model.CalculationType
import com.kinetix.risk.model.ConfidenceLevel
import com.kinetix.risk.model.TriggerType
import com.kinetix.risk.model.VaRCalculationRequest
import com.kinetix.risk.model.PnlTrigger
import com.kinetix.risk.service.IntradayPnlService
import com.kinetix.risk.service.LiquidityRiskService
import com.kinetix.risk.service.VaRCalculationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Duration
import kotlin.coroutines.coroutineContext

class TradeEventConsumer(
    private val consumer: KafkaConsumer<String, String>,
    private val varCalculationService: VaRCalculationService,
    private val varCache: VaRCache? = null,
    private val intradayPnlService: IntradayPnlService? = null,
    private val liquidityRiskService: LiquidityRiskService? = null,
    private val positionProvider: PositionProvider? = null,
    private val liquidityRecomputeThresholdPct: Double = 0.10,
    private val topic: String = "trades.lifecycle",
    private val retryableConsumer: RetryableConsumer = RetryableConsumer(topic = topic),
) {
    private val logger = LoggerFactory.getLogger(TradeEventConsumer::class.java)

    suspend fun start() {
        withContext(Dispatchers.IO) {
            consumer.subscribe(listOf(topic))
        }
        try {
            while (coroutineContext.isActive) {
                val records = withContext(Dispatchers.IO) {
                    consumer.poll(Duration.ofMillis(100))
                }
                for (record in records) {
                    try {
                        retryableConsumer.process(
                            record.key() ?: "",
                            record.value(),
                            originalHeaders = record.headers(),
                        ) {
                            val event = Json.decodeFromString<TradeEventMessage>(record.value())
                            MDC.put("correlationId", event.correlationId ?: "")
                            try {
                                val bookId = BookId(event.bookId)

                                logger.info("Trade event received for portfolio {}, triggering VaR recalculation", bookId.value)

                                val result = varCalculationService.calculateVaR(
                                    VaRCalculationRequest(
                                        bookId = bookId,
                                        calculationType = CalculationType.PARAMETRIC,
                                        confidenceLevel = ConfidenceLevel.CL_95,
                                    ),
                                    triggerType = TriggerType.TRADE_EVENT,
                                    correlationId = event.correlationId,
                                    triggeredBy = "SYSTEM",
                                )
                                if (result != null) {
                                    varCache?.put(bookId.value, result)
                                }
                                try {
                                    intradayPnlService?.recompute(
                                        bookId = bookId,
                                        trigger = PnlTrigger.TRADE_BOOKED,
                                        correlationId = event.correlationId,
                                    )
                                } catch (e: Exception) {
                                    logger.warn(
                                        "Intraday P&L recompute failed after trade event for book {}: {}",
                                        bookId.value, e.message,
                                    )
                                }
                                try {
                                    maybeRecomputeLiquidityRisk(bookId, event, result)
                                } catch (e: Exception) {
                                    logger.warn(
                                        "Liquidity risk recompute failed after trade event for book {}: {}",
                                        bookId.value, e.message,
                                    )
                                }
                            } finally {
                                MDC.remove("correlationId")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to process trade event after retries: offset={}, partition={}",
                            record.offset(), record.partition(), e,
                        )
                    }
                }
                if (!records.isEmpty) {
                    withContext(Dispatchers.IO) { consumer.commitSync() }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                logger.info("Closing trade event Kafka consumer")
                consumer.close(Duration.ofSeconds(10))
            }
        }
    }

    private suspend fun maybeRecomputeLiquidityRisk(
        bookId: BookId,
        event: TradeEventMessage,
        varResult: com.kinetix.risk.model.ValuationResult?,
    ) {
        val service = liquidityRiskService ?: return
        val provider = positionProvider ?: return

        val tradeNotional = event.quantity.toBigDecimalOrNull()
            ?.multiply(event.priceAmount.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            ?.abs()
            ?: return

        if (tradeNotional == BigDecimal.ZERO) return

        val positions = provider.getPositions(bookId)
        val existingNotional = positions.sumOf { pos ->
            pos.quantity.abs().multiply(pos.marketPrice.amount).toDouble()
        }

        if (existingNotional <= 0.0) {
            logger.debug("Skipping liquidity recompute for book {}: no existing positions", bookId.value)
            return
        }

        val tradeNotionalPct = tradeNotional.toDouble() / existingNotional
        if (tradeNotionalPct >= liquidityRecomputeThresholdPct) {
            logger.info(
                "Trade notional {:.2f}% of existing notional {:.2f} exceeds threshold {:.0f}% for book {}, triggering liquidity recompute",
                tradeNotionalPct * 100, existingNotional, liquidityRecomputeThresholdPct * 100, bookId.value,
            )
            val baseVar = varResult?.varValue?.toDouble() ?: 0.0
            service.calculateAndSave(bookId, baseVar)
        } else {
            logger.debug(
                "Trade notional {:.2f}% of existing notional below threshold, skipping liquidity recompute for book {}",
                tradeNotionalPct * 100, bookId.value,
            )
        }
    }
}
