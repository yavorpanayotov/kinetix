package com.kinetix.position.service

import com.kinetix.common.kafka.events.LimitBreachEvent
import com.kinetix.common.model.*
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.kafka.LimitBreachEventPublisher
import com.kinetix.position.kafka.NoOpLimitBreachEventPublisher
import com.kinetix.position.kafka.TradeEventPublisher
import com.kinetix.position.model.LimitBreach
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BookTradeCommand(
    val tradeId: TradeId,
    val bookId: BookId,
    val instrumentId: InstrumentId,
    val assetClass: AssetClass,
    val side: Side,
    val quantity: BigDecimal,
    val price: Money,
    val tradedAt: Instant,
    val instrumentType: String,
    val userId: String? = null,
    val userRole: String? = null,
    val strategyId: String? = null,
    val counterpartyId: String? = null,
    /**
     * Identifier of the originating action (e.g. order id for FIX-driven fills) so the
     * correlation chain Order -> Trade -> downstream events stays intact.
     * Spec: execution.allium TradeBookedFromFill (correlation_id: order.order_id).
     */
    val correlationId: String? = null,
)

data class BookTradeResult(
    val trade: Trade,
    val position: Position,
    val warnings: List<LimitBreach> = emptyList(),
)

class TradeBookingService(
    private val tradeEventRepository: TradeEventRepository,
    private val positionRepository: PositionRepository,
    private val transactional: TransactionalRunner,
    private val tradeEventPublisher: TradeEventPublisher,
    private val limitCheckService: PreTradeCheckService? = null,
    private val nettingSetAssigner: NettingSetAssigner? = null,
    private val limitBreachEventPublisher: LimitBreachEventPublisher = NoOpLimitBreachEventPublisher(),
) {
    private val logger = LoggerFactory.getLogger(TradeBookingService::class.java)

    suspend fun handle(command: BookTradeCommand): BookTradeResult {
        logger.info("Booking trade: tradeId={}, book={}, instrument={}, side={}, qty={}, price={}",
            command.tradeId.value, command.bookId.value, command.instrumentId.value,
            command.side, command.quantity, command.price.amount)
        val limitResult = limitCheckService?.check(command)
        if (limitResult != null && limitResult.blocked) {
            // Publish each breach as its own event before throwing so the synchronous
            // 422 response is preserved AND the breach becomes durable + observable.
            val breachedAt = Instant.now().toString()
            limitResult.breaches
                .filter { it.severity.name == "HARD" }
                .forEach { breach ->
                    limitBreachEventPublisher.publish(
                        LimitBreachEvent(
                            eventId = UUID.randomUUID().toString(),
                            tradeId = command.tradeId.value,
                            bookId = command.bookId.value,
                            limitType = breach.limitType,
                            severity = breach.severity.name,
                            currentValue = breach.currentValue,
                            limitValue = breach.limitValue,
                            message = breach.message,
                            breachedAt = breachedAt,
                        ),
                    )
                }
            throw LimitBreachException(limitResult)
        }
        val warnings = limitResult?.breaches ?: emptyList()

        val trade = Trade(
            tradeId = command.tradeId,
            bookId = command.bookId,
            instrumentId = command.instrumentId,
            assetClass = command.assetClass,
            side = command.side,
            quantity = command.quantity,
            price = command.price,
            tradedAt = command.tradedAt,
            instrumentType = InstrumentTypeCode.fromString(command.instrumentType),
            strategyId = command.strategyId,
            counterpartyId = command.counterpartyId,
        )

        val (result, isNewTrade) = transactional.run {
            val existing = tradeEventRepository.findByTradeId(trade.tradeId)
            if (existing != null) {
                val position = positionRepository.findByKey(trade.bookId, trade.instrumentId)
                    ?: Position.fromFirstTrade(trade)
                return@run Pair(BookTradeResult(existing, position, warnings), false)
            }

            tradeEventRepository.save(trade)

            val currentPosition = positionRepository.findByKey(trade.bookId, trade.instrumentId)
                ?: Position.fromFirstTrade(trade)

            val updatedPosition = currentPosition.applyTrade(trade)
                .let { pos -> if (trade.strategyId != null) pos.copy(strategyId = trade.strategyId) else pos }
            positionRepository.save(updatedPosition)

            Pair(BookTradeResult(trade, updatedPosition, warnings), true)
        }

        if (isNewTrade) {
            val tradeEvent = if (command.correlationId != null) {
                TradeEvent(
                    trade = result.trade,
                    correlationId = command.correlationId,
                    userId = command.userId,
                    userRole = command.userRole,
                )
            } else {
                TradeEvent(trade = result.trade, userId = command.userId, userRole = command.userRole)
            }
            tradeEventPublisher.publish(tradeEvent)
            logger.info("Trade booked: tradeId={}, book={}, newPosition={}",
                result.trade.tradeId.value, result.trade.bookId.value, result.position.quantity)
            nettingSetAssigner?.assignIfApplicable(
                tradeId = result.trade.tradeId.value,
                counterpartyId = command.counterpartyId,
            )
        }

        return result
    }
}
