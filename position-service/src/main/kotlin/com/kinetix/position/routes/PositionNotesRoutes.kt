package com.kinetix.position.routes

import com.kinetix.common.dtos.CreatePositionNoteRequest
import com.kinetix.common.dtos.PositionNoteDto
import com.kinetix.position.model.PositionNote
import com.kinetix.position.service.PositionNotesService
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * HTTP routes for the position-note feature shipped in plan §7.3.
 *
 * The author of a new note is derived in this order:
 *   1. The `X-User` header on the inbound request (set by the gateway from the
 *      authenticated principal — see ADR-0013).
 *   2. The fallback string `"system"` when no header is present, mirroring the
 *      behaviour of other position-service write routes that accept demo/test
 *      traffic without authentication.
 *
 * The body of the create request never carries `author` — that would let a
 * client impersonate another user.
 */
fun Route.positionNotesRoutes(service: PositionNotesService) {
    route("/api/v1/positions") {

        route("/{bookId}/notes") {

            get({
                summary = "List notes for a book, optionally filtered by instrument"
                tags = listOf("Position Notes")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                    queryParameter<String>("instrumentId") {
                        description = "Restrict to a single (book, instrument) pair"
                        required = false
                    }
                }
                response {
                    code(HttpStatusCode.OK) { body<List<PositionNoteDto>>() }
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val instrumentId = call.request.queryParameters["instrumentId"]?.takeIf { it.isNotBlank() }
                val notes = service.list(bookId, instrumentId).map { it.toDto() }
                call.respond(HttpStatusCode.OK, notes)
            }

            post({
                summary = "Create a free-text note for a (book, instrument) pair"
                tags = listOf("Position Notes")
                request {
                    pathParameter<String>("bookId") { description = "Book identifier" }
                    body<CreatePositionNoteRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<PositionNoteDto>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                }
            }) {
                val bookId = call.requirePathParam("bookId")
                val request = call.receive<CreatePositionNoteRequest>()

                if (request.instrumentId.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_request", "instrumentId must not be blank"),
                    )
                    return@post
                }
                if (request.note.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_request", "note must not be blank"),
                    )
                    return@post
                }

                val author = call.request.headers["X-User"]?.takeIf { it.isNotBlank() } ?: "system"

                val created = service.create(
                    bookId = bookId,
                    instrumentId = request.instrumentId,
                    note = request.note,
                    author = author,
                )
                call.respond(HttpStatusCode.Created, created.toDto())
            }
        }

        delete("/notes/{id}", {
            summary = "Delete a position note by id"
            tags = listOf("Position Notes")
            request {
                pathParameter<String>("id") { description = "Note identifier (UUID)" }
            }
            response {
                code(HttpStatusCode.NoContent) { }
                code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
            }
        }) {
            val rawId = call.requirePathParam("id")
            val noteId = runCatching { UUID.fromString(rawId) }.getOrNull()
                ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_request", "id must be a valid UUID"),
                )

            val deleted = service.delete(noteId)
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("note_not_found", "No position note exists with id $rawId"),
                )
            }
        }
    }
}

private fun PositionNote.toDto() = PositionNoteDto(
    id = id.toString(),
    bookId = bookId,
    instrumentId = instrumentId,
    note = note,
    author = author,
    createdAt = createdAt.toString(),
)
