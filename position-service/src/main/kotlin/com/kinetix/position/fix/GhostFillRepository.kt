package com.kinetix.position.fix

interface GhostFillRepository {
    suspend fun save(fill: GhostFill)
    suspend fun findByOrderId(orderId: String): List<GhostFill>
}
