package com.kinetix.referencedata.persistence

import com.kinetix.common.model.DeskId
import com.kinetix.common.model.Trader
import com.kinetix.common.model.TraderId

interface TraderRepository {
    suspend fun save(trader: Trader)
    suspend fun findById(id: TraderId): Trader?
    suspend fun findAll(): List<Trader>
    suspend fun findByDeskId(deskId: DeskId): List<Trader>
}
