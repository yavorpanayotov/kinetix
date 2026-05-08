package com.kinetix.position.service

import com.kinetix.common.model.*
import com.kinetix.position.kafka.TradeEventPublisher
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import org.slf4j.LoggerFactory

class TradeLifecycleService(
    private val tradeEventRepository: TradeEventRepository,
    private val positionRepository: PositionRepository,
    private val transactional: TransactionalRunner,
    private val tradeEventPublisher: TradeEventPublisher,
    private val nettingSetAssigner: NettingSetAssigner? = null,
) {
    private val logger = LoggerFactory.getLogger(TradeLifecycleService::class.java)

    suspend fun handleAmend(command: AmendTradeCommand): BookTradeResult {
        logger.info("Amending trade: originalTradeId={}, newTradeId={}, book={}",
            command.originalTradeId.value, command.newTradeId.value, command.bookId.value)

        val originalTrade = tradeEventRepository.findByTradeId(command.originalTradeId)
            ?: throw TradeNotFoundException(command.originalTradeId.value)

        if (originalTrade.status == TradeStatus.AMENDED) {
            val existingAmend = tradeEventRepository.findByTradeId(command.newTradeId)
            if (existingAmend != null) {
                logger.info("Trade already amended (idempotent): originalTradeId={}, newTradeId={}",
                    command.originalTradeId.value, command.newTradeId.value)
                val currentPosition = positionRepository.findByKey(originalTrade.bookId, originalTrade.instrumentId)
                    ?: Position.fromFirstTrade(originalTrade)
                return BookTradeResult(existingAmend, currentPosition)
            }
            throw InvalidTradeStateException(command.originalTradeId.value, originalTrade.status, "amend")
        }
        if (originalTrade.status != TradeStatus.LIVE) {
            throw InvalidTradeStateException(command.originalTradeId.value, originalTrade.status, "amend")
        }

        val result = transactional.run {
            tradeEventRepository.updateStatus(command.originalTradeId, TradeStatus.AMENDED)

            val currentPosition = positionRepository.findByKey(originalTrade.bookId, originalTrade.instrumentId)
                ?: Position.fromFirstTrade(originalTrade)

            val reverseTrade = createReverseTrade(originalTrade)
            val positionAfterReversal = currentPosition.applyTrade(reverseTrade)

            val amendTrade = Trade(
                tradeId = command.newTradeId,
                bookId = command.bookId,
                instrumentId = command.instrumentId,
                assetClass = command.assetClass,
                side = command.side,
                quantity = command.quantity,
                price = command.price,
                tradedAt = command.tradedAt,
                eventType = TradeEventType.AMEND,
                status = TradeStatus.LIVE,
                originalTradeId = command.originalTradeId,
                counterpartyId = command.counterpartyId,
                instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.fromString(command.instrumentType),
            )

            val finalPosition = positionAfterReversal.applyTrade(amendTrade)

            tradeEventRepository.save(amendTrade)
            positionRepository.save(finalPosition)

            BookTradeResult(amendTrade, finalPosition)
        }

        tradeEventPublisher.publish(TradeEvent(trade = result.trade, userId = command.userId, userRole = command.userRole))
        logger.info("Trade amended: originalTradeId={}, newTradeId={}", command.originalTradeId.value, command.newTradeId.value)
        nettingSetAssigner?.assignIfApplicable(
            tradeId = result.trade.tradeId.value,
            counterpartyId = command.counterpartyId,
        )
        return result
    }

    suspend fun handleCancel(command: CancelTradeCommand): BookTradeResult {
        logger.info("Cancelling trade: tradeId={}", command.tradeId.value)

        val trade = tradeEventRepository.findByTradeId(command.tradeId)
            ?: throw TradeNotFoundException(command.tradeId.value)

        if (trade.status == TradeStatus.CANCELLED) {
            logger.info("Trade already cancelled (idempotent): tradeId={}", command.tradeId.value)
            val currentPosition = positionRepository.findByKey(trade.bookId, trade.instrumentId)
                ?: Position.fromFirstTrade(trade)
            return BookTradeResult(trade, currentPosition)
        }
        if (trade.status != TradeStatus.LIVE) {
            throw InvalidTradeStateException(command.tradeId.value, trade.status, "cancel")
        }

        val result = transactional.run {
            tradeEventRepository.updateStatus(command.tradeId, TradeStatus.CANCELLED)

            val currentPosition = positionRepository.findByKey(trade.bookId, trade.instrumentId)
                ?: Position.fromFirstTrade(trade)

            val reverseTrade = createReverseTrade(trade)
            val updatedPosition = currentPosition.applyTrade(reverseTrade)

            positionRepository.save(updatedPosition)

            val cancelledTrade = trade.copy(status = TradeStatus.CANCELLED)
            BookTradeResult(cancelledTrade, updatedPosition)
        }

        tradeEventPublisher.publish(TradeEvent(trade = result.trade))
        logger.info("Trade cancelled: tradeId={}", command.tradeId.value)
        return result
    }

    private fun createReverseTrade(trade: Trade): Trade {
        val reverseSide = when (trade.side) {
            Side.BUY -> Side.SELL
            Side.SELL -> Side.BUY
        }
        return Trade(
            tradeId = TradeId("${trade.tradeId.value}-reverse"),
            bookId = trade.bookId,
            instrumentId = trade.instrumentId,
            assetClass = trade.assetClass,
            side = reverseSide,
            quantity = trade.quantity,
            price = trade.price,
            tradedAt = trade.tradedAt,
            eventType = trade.eventType,
            status = trade.status,
            instrumentType = trade.instrumentType,
        )
    }
}
