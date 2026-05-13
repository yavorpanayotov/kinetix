package com.kinetix.referencedata.service

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId
import com.kinetix.referencedata.persistence.DeskRepository
import com.kinetix.referencedata.persistence.TraderRepository

class TraderService(
    private val traderRepository: TraderRepository,
    private val deskRepository: DeskRepository,
) {
    suspend fun create(trader: Trader) {
        require(deskRepository.findById(trader.deskId) != null) {
            "Desk '${trader.deskId.value}' does not exist"
        }
        traderRepository.save(trader)
    }

    suspend fun findById(id: TraderId): Trader? =
        traderRepository.findById(id)

    suspend fun findAll(): List<Trader> =
        traderRepository.findAll()

    suspend fun findByDesk(deskId: DeskId): List<Trader> =
        traderRepository.findByDeskId(deskId)
}
