package com.kinetix.position

import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedBookHierarchyRepository
import com.kinetix.position.routes.bookHierarchyRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private fun Application.configureTestApp(repo: ExposedBookHierarchyRepository) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "invalid_request", "message" to (cause.message ?: "Invalid request")),
            )
        }
    }
    routing {
        bookHierarchyRoutes(repo)
    }
}

class BookHierarchyRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repo = ExposedBookHierarchyRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE book_hierarchy RESTART IDENTITY CASCADE")
        }
    }

    test("POST /api/v1/book-hierarchy creates a mapping and returns 201") {
        testApplication {
            application { configureTestApp(repo) }

            val response = client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "bookId": "EQ-DERIV-001",
                        "deskId": "EQ-DESK",
                        "bookName": "Equity Derivatives Book 001",
                        "bookType": "TRADING"
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.Created

            val persisted = repo.findByBookId("EQ-DERIV-001")
            persisted?.deskId shouldBe "EQ-DESK"
            persisted?.bookName shouldBe "Equity Derivatives Book 001"
            persisted?.bookType shouldBe "TRADING"
        }
    }

    test("POST /api/v1/book-hierarchy persists the book's base currency when provided") {
        testApplication {
            application { configureTestApp(repo) }

            val response = client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                        "bookId": "FX-LDN-001",
                        "deskId": "FX-DESK",
                        "baseCurrency": "GBP"
                    }
                    """.trimIndent()
                )
            }

            response.status shouldBe HttpStatusCode.Created
            repo.findByBookId("FX-LDN-001")?.baseCurrency shouldBe "GBP"
        }
    }

    test("POST /api/v1/book-hierarchy defaults base currency to USD when omitted") {
        testApplication {
            application { configureTestApp(repo) }

            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"US-001","deskId":"EQ-DESK"}""")
            }

            repo.findByBookId("US-001")?.baseCurrency shouldBe "USD"
        }
    }

    test("GET /api/v1/book-hierarchy/{bookId} returns the book's base currency") {
        testApplication {
            application { configureTestApp(repo) }

            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"JP-001","deskId":"EQ-DESK","baseCurrency":"JPY"}""")
            }

            val response = client.get("/api/v1/book-hierarchy/JP-001")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["baseCurrency"]!!.jsonPrimitive.content shouldBe "JPY"
        }
    }

    test("POST /api/v1/book-hierarchy returns 400 when a required field is missing") {
        testApplication {
            application { configureTestApp(repo) }

            val response = client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"EQ-DERIV-001"}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/book-hierarchy returns all persisted mappings") {
        testApplication {
            application { configureTestApp(repo) }

            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"EQ-001","deskId":"EQ-DESK"}""")
            }
            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"FX-001","deskId":"FX-DESK"}""")
            }

            val response = client.get("/api/v1/book-hierarchy")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body.map { it.jsonObject["bookId"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("EQ-001", "FX-001")
        }
    }

    test("GET /api/v1/book-hierarchy filters by deskId when provided") {
        testApplication {
            application { configureTestApp(repo) }

            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"EQ-001","deskId":"EQ-DESK"}""")
            }
            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"EQ-002","deskId":"EQ-DESK"}""")
            }
            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"FX-001","deskId":"FX-DESK"}""")
            }

            val response = client.get("/api/v1/book-hierarchy?deskId=EQ-DESK")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body.all { it.jsonObject["deskId"]!!.jsonPrimitive.content == "EQ-DESK" } shouldBe true
        }
    }

    test("GET /api/v1/book-hierarchy/{bookId} returns the mapping when it exists") {
        testApplication {
            application { configureTestApp(repo) }

            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"EQ-001","deskId":"EQ-DESK","bookName":"Cash Equity Flow","bookType":"TRADING"}""")
            }

            val response = client.get("/api/v1/book-hierarchy/EQ-001")

            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["bookId"]!!.jsonPrimitive.content shouldBe "EQ-001"
            body["deskId"]!!.jsonPrimitive.content shouldBe "EQ-DESK"
            body["bookName"]!!.jsonPrimitive.content shouldBe "Cash Equity Flow"
        }
    }

    test("GET /api/v1/book-hierarchy/{bookId} returns 404 when the mapping does not exist") {
        testApplication {
            application { configureTestApp(repo) }

            val response = client.get("/api/v1/book-hierarchy/UNKNOWN-BOOK")

            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("DELETE /api/v1/book-hierarchy/{bookId} removes the mapping and returns 204") {
        testApplication {
            application { configureTestApp(repo) }

            client.post("/api/v1/book-hierarchy") {
                contentType(ContentType.Application.Json)
                setBody("""{"bookId":"EQ-001","deskId":"EQ-DESK"}""")
            }

            val response = client.delete("/api/v1/book-hierarchy/EQ-001")

            response.status shouldBe HttpStatusCode.NoContent
            repo.findByBookId("EQ-001") shouldBe null
        }
    }
})
