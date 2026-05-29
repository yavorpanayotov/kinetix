package com.kinetix.gateway.contract

import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * Trader-review P2 §21: every row on the Trade Blotter shows `Status = LIVE`,
 * which is not a fill state. A real blotter must distinguish
 * `WORKING / FILLED / PARTIAL / CANCELLED / REJECTED` and surface
 * `qtyFilled` / `qtyOpen` so the trader can tell what's done from what's still
 * in the market.
 *
 * The gateway must project the upstream trade lifecycle status into a
 * trader-facing `fillStatus` enum and synthesise `qtyFilled` / `qtyOpen` from
 * the trade's quantity + status. Booked trades (LIVE/AMENDED) are by
 * definition fully filled — `fillStatus = FILLED`, `qtyFilled = quantity`,
 * `qtyOpen = 0`. CANCELLED trades show zero filled / zero open. When the
 * upstream wire shape explicitly carries the richer fill state on a row
 * (e.g. trades reconciled from working orders with partial fills), the
 * gateway must forward those fields verbatim rather than override them with
 * its derived default.
 */
class TradeBlotterFillStateAcceptanceTest : FunSpec({

    fun tradeJson(
        tradeId: String,
        status: String,
        quantity: String = "100",
        fillStatus: String? = null,
        qtyFilled: String? = null,
        qtyOpen: String? = null,
    ): String {
        val extraEntries = buildList<String> {
            fillStatus?.let { add("\"fillStatus\":\"$it\"") }
            qtyFilled?.let { add("\"qtyFilled\":\"$it\"") }
            qtyOpen?.let { add("\"qtyOpen\":\"$it\"") }
        }
        val extras = if (extraEntries.isEmpty()) "" else "," + extraEntries.joinToString(",")
        return """
            {
              "tradeId":"$tradeId",
              "bookId":"port-1",
              "instrumentId":"AAPL",
              "assetClass":"EQUITY",
              "side":"BUY",
              "quantity":"$quantity",
              "price":{"amount":"150.00","currency":"USD"},
              "tradedAt":"2025-01-15T10:00:00Z",
              "status":"$status",
              "instrumentType":"CASH_EQUITY"
              $extras
            }
        """.trimIndent()
    }

    test("GET /trades projects LIVE trades to fillStatus=FILLED with qtyFilled=quantity and qtyOpen=0") {
        val backend = BackendStubServer {
            get("/api/v1/books/port-1/trades") {
                val body = "[${tradeJson("t-live", "LIVE", quantity = "100")}]"
                call.respond(Json.parseToJsonElement(body).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/trades")
                response.status shouldBe HttpStatusCode.OK
                val row = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
                row["fillStatus"]?.jsonPrimitive?.content shouldBe "FILLED"
                row["qtyFilled"]?.jsonPrimitive?.content shouldBe "100"
                row["qtyOpen"]?.jsonPrimitive?.content shouldBe "0"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /trades projects CANCELLED trades to fillStatus=CANCELLED with qtyFilled=0 and qtyOpen=0") {
        val backend = BackendStubServer {
            get("/api/v1/books/port-1/trades") {
                val body = "[${tradeJson("t-cancelled", "CANCELLED", quantity = "50")}]"
                call.respond(Json.parseToJsonElement(body).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/trades")
                val row = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
                row["fillStatus"]?.jsonPrimitive?.content shouldBe "CANCELLED"
                row["qtyFilled"]?.jsonPrimitive?.content shouldBe "0"
                row["qtyOpen"]?.jsonPrimitive?.content shouldBe "0"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /trades projects AMENDED trades to fillStatus=FILLED (booking record was filled before amendment)") {
        val backend = BackendStubServer {
            get("/api/v1/books/port-1/trades") {
                val body = "[${tradeJson("t-amended", "AMENDED", quantity = "200")}]"
                call.respond(Json.parseToJsonElement(body).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/trades")
                val row = Json.parseToJsonElement(response.bodyAsText()).jsonArray[0].jsonObject
                row["fillStatus"]?.jsonPrimitive?.content shouldBe "FILLED"
                row["qtyFilled"]?.jsonPrimitive?.content shouldBe "200"
                row["qtyOpen"]?.jsonPrimitive?.content shouldBe "0"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /trades forwards explicit upstream fillStatus / qtyFilled / qtyOpen verbatim when upstream carries them") {
        // Trades that originate from working orders with partial fills carry
        // their richer fill state in the upstream payload — e.g. WORKING with
        // qtyFilled < quantity, or PARTIAL / REJECTED. The gateway must not
        // override these with its derived defaults.
        val workingJson = tradeJson(
            tradeId = "t-working",
            status = "LIVE",
            quantity = "1000",
            fillStatus = "WORKING",
            qtyFilled = "0",
            qtyOpen = "1000",
        )
        val partialJson = tradeJson(
            tradeId = "t-partial",
            status = "LIVE",
            quantity = "1000",
            fillStatus = "PARTIAL",
            qtyFilled = "600",
            qtyOpen = "400",
        )
        val rejectedJson = tradeJson(
            tradeId = "t-rejected",
            status = "LIVE",
            quantity = "500",
            fillStatus = "REJECTED",
            qtyFilled = "0",
            qtyOpen = "0",
        )
        val backend = BackendStubServer {
            get("/api/v1/books/port-1/trades") {
                val body = "[$workingJson,$partialJson,$rejectedJson]"
                call.respond(Json.parseToJsonElement(body).jsonArray)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/trades")
                val rows = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                rows.size shouldBe 3

                val byId = rows.associateBy { it.jsonObject["tradeId"]!!.jsonPrimitive.content }

                val working = byId["t-working"]!!.jsonObject
                working["fillStatus"]?.jsonPrimitive?.content shouldBe "WORKING"
                working["qtyFilled"]?.jsonPrimitive?.content shouldBe "0"
                working["qtyOpen"]?.jsonPrimitive?.content shouldBe "1000"

                val partial = byId["t-partial"]!!.jsonObject
                partial["fillStatus"]?.jsonPrimitive?.content shouldBe "PARTIAL"
                partial["qtyFilled"]?.jsonPrimitive?.content shouldBe "600"
                partial["qtyOpen"]?.jsonPrimitive?.content shouldBe "400"

                val rejected = byId["t-rejected"]!!.jsonObject
                rejected["fillStatus"]?.jsonPrimitive?.content shouldBe "REJECTED"
                rejected["qtyFilled"]?.jsonPrimitive?.content shouldBe "0"
                rejected["qtyOpen"]?.jsonPrimitive?.content shouldBe "0"

                val emittedStatuses = rows.map { it.jsonObject["fillStatus"]!!.jsonPrimitive.content }
                emittedStatuses shouldContainExactlyInAnyOrder listOf("WORKING", "PARTIAL", "REJECTED")
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /trades/page mixed-status payload — every row carries fillStatus, qtyFilled, qtyOpen") {
        val workingRow = tradeJson(
            tradeId = "t-1",
            status = "LIVE",
            quantity = "500",
            fillStatus = "WORKING",
            qtyFilled = "0",
            qtyOpen = "500",
        )
        val filledRow = tradeJson(tradeId = "t-2", status = "LIVE", quantity = "200")
        val partialRow = tradeJson(
            tradeId = "t-3",
            status = "LIVE",
            quantity = "1000",
            fillStatus = "PARTIAL",
            qtyFilled = "750",
            qtyOpen = "250",
        )
        val cancelledRow = tradeJson(tradeId = "t-4", status = "CANCELLED", quantity = "75")
        val rejectedRow = tradeJson(
            tradeId = "t-5",
            status = "LIVE",
            quantity = "300",
            fillStatus = "REJECTED",
            qtyFilled = "0",
            qtyOpen = "0",
        )

        val pageJson = """
            {
              "items":[$workingRow,$filledRow,$partialRow,$cancelledRow,$rejectedRow],
              "total":5,
              "offset":0,
              "limit":100,
              "hasMore":false
            }
        """.trimIndent()

        val backend = BackendStubServer {
            get("/api/v1/books/port-1/trades/page") {
                call.respond(Json.parseToJsonElement(pageJson).jsonObject)
            }
        }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(positionClient) }
                val response = client.get("/api/v1/books/port-1/trades/page")
                response.status shouldBe HttpStatusCode.OK
                val page = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val items = page["items"]!!.jsonArray
                items.size shouldBe 5

                val byId = items.associateBy { it.jsonObject["tradeId"]!!.jsonPrimitive.content }

                // Every row exposes the fill-state triple — none may be missing.
                for (row in items) {
                    val obj = row.jsonObject
                    obj.containsKey("fillStatus") shouldBe true
                    obj.containsKey("qtyFilled") shouldBe true
                    obj.containsKey("qtyOpen") shouldBe true
                }

                byId["t-1"]!!.jsonObject["fillStatus"]?.jsonPrimitive?.content shouldBe "WORKING"
                byId["t-2"]!!.jsonObject["fillStatus"]?.jsonPrimitive?.content shouldBe "FILLED"
                byId["t-2"]!!.jsonObject["qtyFilled"]?.jsonPrimitive?.content shouldBe "200"
                byId["t-2"]!!.jsonObject["qtyOpen"]?.jsonPrimitive?.content shouldBe "0"
                byId["t-3"]!!.jsonObject["fillStatus"]?.jsonPrimitive?.content shouldBe "PARTIAL"
                byId["t-3"]!!.jsonObject["qtyFilled"]?.jsonPrimitive?.content shouldBe "750"
                byId["t-3"]!!.jsonObject["qtyOpen"]?.jsonPrimitive?.content shouldBe "250"
                byId["t-4"]!!.jsonObject["fillStatus"]?.jsonPrimitive?.content shouldBe "CANCELLED"
                byId["t-4"]!!.jsonObject["qtyFilled"]?.jsonPrimitive?.content shouldBe "0"
                byId["t-4"]!!.jsonObject["qtyOpen"]?.jsonPrimitive?.content shouldBe "0"
                byId["t-5"]!!.jsonObject["fillStatus"]?.jsonPrimitive?.content shouldBe "REJECTED"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
