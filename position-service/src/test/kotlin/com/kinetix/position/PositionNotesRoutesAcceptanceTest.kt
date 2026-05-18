package com.kinetix.position

import com.kinetix.common.dtos.PositionNoteDto
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedPositionNotesRepository
import com.kinetix.position.persistence.PositionNotesTable
import com.kinetix.position.routes.ErrorResponse
import com.kinetix.position.routes.positionNotesRoutes
import com.kinetix.position.service.PositionNotesService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

private fun Application.configureTestApp(service: PositionNotesService) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", cause.message ?: "Invalid request"),
            )
        }
    }
    routing {
        positionNotesRoutes(service)
    }
}

class PositionNotesRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedPositionNotesRepository(db)
    val service = PositionNotesService(repository)

    beforeEach {
        newSuspendedTransaction(db = db) { PositionNotesTable.deleteAll() }
    }

    test("POST /api/v1/positions/{bookId}/notes — valid body — returns 201 with persisted note") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                header("X-User", "alice")
                setBody("""{"instrumentId":"AAPL","note":"Watching earnings"}""")
            }

            response.status shouldBe HttpStatusCode.Created
            val body = Json.decodeFromString<PositionNoteDto>(response.bodyAsText())
            body.bookId shouldBe "BOOK-EQ-US"
            body.instrumentId shouldBe "AAPL"
            body.note shouldBe "Watching earnings"
            body.author shouldBe "alice"
            UUID.fromString(body.id) // does not throw
        }
    }

    test("POST /api/v1/positions/{bookId}/notes — no X-User header — defaults author to 'system'") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":"No author header"}""")
            }

            response.status shouldBe HttpStatusCode.Created
            val body = Json.decodeFromString<PositionNoteDto>(response.bodyAsText())
            body.author shouldBe "system"
        }
    }

    test("POST /api/v1/positions/{bookId}/notes — empty note — returns 400") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":""}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            body.message shouldBe "note must not be blank"
        }
    }

    test("POST /api/v1/positions/{bookId}/notes — blank instrumentId — returns 400") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"   ","note":"some note"}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            body.message shouldBe "instrumentId must not be blank"
        }
    }

    test("GET /api/v1/positions/{bookId}/notes — returns all notes for the book, newest first") {
        testApplication {
            application { configureTestApp(service) }

            client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":"first"}""")
            }
            delay(10)
            client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"MSFT","note":"second"}""")
            }
            delay(10)
            client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":"third"}""")
            }

            val response = client.get("/api/v1/positions/BOOK-EQ-US/notes")

            response.status shouldBe HttpStatusCode.OK
            val notes = Json.decodeFromString<List<PositionNoteDto>>(response.bodyAsText())
            notes shouldHaveSize 3
            notes.map { it.note } shouldBe listOf("third", "second", "first")
        }
    }

    test("GET /api/v1/positions/{bookId}/notes?instrumentId=... — filters to that instrument") {
        testApplication {
            application { configureTestApp(service) }

            client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":"apple a"}""")
            }
            client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"MSFT","note":"microsoft"}""")
            }
            client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":"apple b"}""")
            }

            val response = client.get("/api/v1/positions/BOOK-EQ-US/notes?instrumentId=AAPL")

            response.status shouldBe HttpStatusCode.OK
            val notes = Json.decodeFromString<List<PositionNoteDto>>(response.bodyAsText())
            notes shouldHaveSize 2
            notes.forEach { it.instrumentId shouldBe "AAPL" }
        }
    }

    test("DELETE /api/v1/positions/notes/{id} — existing id — returns 204 and GET no longer returns it") {
        testApplication {
            application { configureTestApp(service) }

            val createResponse = client.post("/api/v1/positions/BOOK-EQ-US/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"instrumentId":"AAPL","note":"to be deleted"}""")
            }
            val created = Json.decodeFromString<PositionNoteDto>(createResponse.bodyAsText())

            val deleteResponse = client.delete("/api/v1/positions/notes/${created.id}")
            deleteResponse.status shouldBe HttpStatusCode.NoContent

            val listResponse = client.get("/api/v1/positions/BOOK-EQ-US/notes")
            val notes = Json.decodeFromString<List<PositionNoteDto>>(listResponse.bodyAsText())
            notes shouldHaveSize 0
        }
    }

    test("DELETE /api/v1/positions/notes/{id} — unknown id — returns 404") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.delete("/api/v1/positions/notes/${UUID.randomUUID()}")

            response.status shouldBe HttpStatusCode.NotFound
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            body.error shouldBe "note_not_found"
        }
    }

    test("DELETE /api/v1/positions/notes/{id} — non-UUID id — returns 400") {
        testApplication {
            application { configureTestApp(service) }

            val response = client.delete("/api/v1/positions/notes/not-a-uuid")

            response.status shouldBe HttpStatusCode.BadRequest
        }
    }
})
