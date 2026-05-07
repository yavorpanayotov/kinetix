package com.kinetix.fix.session

interface FixSessionStateRepository {
    suspend fun findByVenue(venue: String): FixSessionState?
    suspend fun upsert(state: FixSessionState)
    suspend fun all(): List<FixSessionState>
}
