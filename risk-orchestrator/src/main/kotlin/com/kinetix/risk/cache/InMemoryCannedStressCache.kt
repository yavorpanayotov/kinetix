package com.kinetix.risk.cache

import com.kinetix.risk.routes.dtos.CannedStressResultResponse
import java.util.concurrent.ConcurrentHashMap

class InMemoryCannedStressCache : CannedStressCache {
    private val cache = ConcurrentHashMap<String, CannedStressResultResponse>()

    override fun put(bookId: String, result: CannedStressResultResponse) {
        cache[bookId] = result
    }

    override fun get(bookId: String): CannedStressResultResponse? = cache[bookId]
}
