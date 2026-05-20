package com.kinetix.position.client

import com.kinetix.common.model.InstrumentId
import com.kinetix.position.client.dtos.PricePointDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * [PriceLookupClient] backed by price-service's HTTP API
 * (`GET /api/v1/prices/{instrumentId}/latest`).
 */
class HttpPriceLookupClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : PriceLookupClient {

    override suspend fun currentMidPrice(instrumentId: InstrumentId): MidPrice? {
        val response = httpClient.get("$baseUrl/api/v1/prices/${instrumentId.value}/latest")
        if (response.status == HttpStatusCode.NotFound) return null
        val dto = response.body<PricePointDto>()
        return MidPrice(
            price = BigDecimal(dto.price.amount),
            currency = Currency.getInstance(dto.price.currency),
            observedAt = Instant.parse(dto.timestamp),
        )
    }
}
