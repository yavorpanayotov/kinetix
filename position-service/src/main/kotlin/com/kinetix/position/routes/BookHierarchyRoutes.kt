package com.kinetix.position.routes

import com.kinetix.position.model.BookHierarchyMapping
import com.kinetix.position.persistence.BookHierarchyRepository
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class BookHierarchyMappingRequest(
    val bookId: String,
    val deskId: String,
    val bookName: String? = null,
    val bookType: String? = null,
    val baseCurrency: String = "USD",
)

@Serializable
private data class BookHierarchyMappingResponse(
    val bookId: String,
    val deskId: String,
    val bookName: String?,
    val bookType: String?,
    val baseCurrency: String,
)

fun Route.bookHierarchyRoutes(bookHierarchyRepository: BookHierarchyRepository) {
    route("/api/v1/book-hierarchy") {
        get {
            val deskId = call.request.queryParameters["deskId"]
            val mappings = if (deskId != null) {
                bookHierarchyRepository.findByDeskId(deskId)
            } else {
                bookHierarchyRepository.findAll()
            }
            call.respond(mappings.map { it.toResponse() })
        }

        post {
            val request = call.receive<BookHierarchyMappingRequest>()
            bookHierarchyRepository.save(
                BookHierarchyMapping(
                    bookId = request.bookId,
                    deskId = request.deskId,
                    bookName = request.bookName,
                    bookType = request.bookType,
                    baseCurrency = request.baseCurrency,
                )
            )
            call.respond(HttpStatusCode.Created)
        }

        route("/{bookId}") {
            get {
                val bookId = call.requirePathParam("bookId")
                val mapping = bookHierarchyRepository.findByBookId(bookId)
                if (mapping == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(mapping.toResponse())
                }
            }

            delete {
                val bookId = call.requirePathParam("bookId")
                bookHierarchyRepository.delete(bookId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun BookHierarchyMapping.toResponse() = BookHierarchyMappingResponse(
    bookId = bookId,
    deskId = deskId,
    bookName = bookName,
    bookType = bookType,
    baseCurrency = baseCurrency,
)
