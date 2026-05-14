package com.kinetix.gateway.routes

import com.kinetix.gateway.dtos.YieldCurvePointResponse
import com.kinetix.gateway.dtos.YieldCurveResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * UI-facing aggregated yield curve. Fetches the latest curve from
 * rates-service, then for each canonical tenor that is missing from the
 * stored curve issues a per-tenor request to obtain the interpolated value.
 * The merged response carries `interpolated: true` for any node that was
 * filled in — the chart uses this flag to render a hollow marker plus the
 * tooltip "Interpolated — source node unavailable".
 *
 * Phase 3 Gap 8 surface: GBP seed omits 5Y, so GET
 * /api/v1/rates/yield-curves/GBP returns the 5Y point with interpolated=true.
 */
fun Route.yieldCurveProxyRoutes(httpClient: HttpClient, ratesBaseUrl: String) {
    route("/api/v1/rates/yield-curves/{curveId}") {
        get({
            summary = "Get the latest yield curve with interpolated nodes filled in"
            tags = listOf("Yield Curves")
            request {
                pathParameter<String>("curveId") { description = "Yield curve identifier (currency code)" }
            }
        }) {
            val curveId = call.requirePathParam("curveId")

            val latestResponse = httpClient.get("$ratesBaseUrl/api/v1/rates/yield-curves/$curveId/latest")
            if (latestResponse.status == HttpStatusCode.NotFound) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val latest: UpstreamYieldCurveLatest = latestResponse.body()

            val presentLabels = latest.tenors.map { it.label }.toSet()
            val missing = CANONICAL_TENORS.filterNot { it in presentLabels }

            val interpolatedPoints = missing.mapNotNull { tenorLabel ->
                val tenorResponse = httpClient.get(
                    "$ratesBaseUrl/api/v1/rates/yield-curves/$curveId/tenor/$tenorLabel"
                )
                if (tenorResponse.status == HttpStatusCode.NotFound) {
                    null
                } else {
                    val body: UpstreamYieldCurveTenor = tenorResponse.body()
                    YieldCurvePointResponse(
                        label = tenorLabel,
                        days = TENOR_DAYS.getValue(tenorLabel),
                        rate = body.value,
                        interpolated = body.interpolated,
                    )
                }
            }

            val storedPoints = latest.tenors.map {
                YieldCurvePointResponse(
                    label = it.label,
                    days = it.days,
                    rate = it.rate,
                    interpolated = false,
                )
            }

            val merged = (storedPoints + interpolatedPoints).sortedBy { it.days }

            call.respond(
                YieldCurveResponse(
                    curveId = latest.curveId,
                    currency = latest.currency,
                    asOfDate = latest.asOfDate,
                    source = latest.source,
                    points = merged,
                )
            )
        }
    }
}

private val CANONICAL_TENORS: List<String> = listOf(
    "O/N", "1W", "1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y", "30Y",
)

private val TENOR_DAYS: Map<String, Int> = mapOf(
    "O/N" to 1, "1W" to 7, "1M" to 30, "3M" to 90, "6M" to 180,
    "1Y" to 365, "2Y" to 730, "5Y" to 1825, "10Y" to 3650, "30Y" to 10950,
)

@Serializable
private data class UpstreamYieldCurveLatest(
    val curveId: String,
    val currency: String,
    val tenors: List<UpstreamTenor>,
    val asOfDate: String,
    val source: String,
)

@Serializable
private data class UpstreamTenor(
    val label: String,
    val days: Int,
    val rate: String,
)

@Serializable
private data class UpstreamYieldCurveTenor(
    val curveId: String,
    val tenor: String,
    val value: String,
    val interpolated: Boolean,
)
