package com.kinetix.audit.routes

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.module
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

private val BASE_TIME: Instant = Instant.parse("2026-05-20T10:00:00Z")

/**
 * PR 4.2 — proves the support-facing `/api/v1/audit/events` query API filters by
 * `tradeId`, `eventType` and a `from`/`to` time window (alone and combined with the
 * existing `bookId` filter), and that cursor pagination keeps working alongside the
 * new filters. Exercises the real Ktor route, mapper, repository and a real Postgres
 * instance (Testcontainers) — never an in-memory fake.
 */
class AuditRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedAuditEventRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
        }
    }

    fun event(
        tradeId: String? = null,
        bookId: String? = null,
        eventType: String = "TRADE_BOOKED",
        receivedAt: Instant = BASE_TIME,
    ) = AuditEvent(
        tradeId = tradeId,
        bookId = bookId,
        eventType = eventType,
        receivedAt = receivedAt,
    )

    fun JsonElement.tradeIds(): List<String?> =
        jsonArray.map { it.jsonObject["tradeId"]?.jsonPrimitive?.contentOrNull }

    fun JsonElement.eventTypes(): List<String> =
        jsonArray.map { it.jsonObject["eventType"]!!.jsonPrimitive.content }

    test("GET /api/v1/audit/events?tradeId=X returns only events for that trade") {
        repository.save(event(tradeId = "t-1", bookId = "book-1"))
        repository.save(event(tradeId = "t-2", bookId = "book-1"))
        repository.save(event(tradeId = "t-1", bookId = "book-2", eventType = "TRADE_AMENDED"))

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?tradeId=t-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText())
            body.jsonArray.size shouldBe 2
            body.tradeIds().shouldContainExactlyInAnyOrder("t-1", "t-1")
        }
    }

    test("GET /api/v1/audit/events?eventType=X returns only events of that type") {
        repository.save(event(tradeId = "t-1", eventType = "TRADE_BOOKED"))
        repository.save(event(tradeId = "t-2", eventType = "TRADE_AMENDED"))
        repository.save(event(tradeId = "t-3", eventType = "TRADE_AMENDED"))

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?eventType=TRADE_AMENDED")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText())
            body.jsonArray.size shouldBe 2
            body.eventTypes().shouldContainExactlyInAnyOrder("TRADE_AMENDED", "TRADE_AMENDED")
        }
    }

    test("GET /api/v1/audit/events?eventType=UNKNOWN returns 400") {
        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?eventType=NOT_A_REAL_TYPE")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/audit/events with from/to window returns only events whose receivedAt falls in the window") {
        repository.save(event(tradeId = "early", receivedAt = Instant.parse("2026-05-20T08:00:00Z")))
        repository.save(event(tradeId = "in-window", receivedAt = Instant.parse("2026-05-20T10:30:00Z")))
        repository.save(event(tradeId = "late", receivedAt = Instant.parse("2026-05-20T12:00:00Z")))

        testApplication {
            application { module(repository) }
            val response = client.get(
                "/api/v1/audit/events?from=2026-05-20T09:00:00Z&to=2026-05-20T11:00:00Z"
            )
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText())
            body.jsonArray.size shouldBe 1
            body.tradeIds() shouldBe listOf("in-window")
        }
    }

    test("GET /api/v1/audit/events?from=not-a-date returns 400") {
        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?from=yesterday")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/audit/events?to=not-a-date returns 400") {
        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?to=soon")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/audit/events combines tradeId with the existing bookId filter") {
        repository.save(event(tradeId = "t-1", bookId = "book-1"))
        repository.save(event(tradeId = "t-1", bookId = "book-2"))
        repository.save(event(tradeId = "t-2", bookId = "book-1"))

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?bookId=book-1&tradeId=t-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText())
            body.jsonArray.size shouldBe 1
            val only = body.jsonArray[0].jsonObject
            only["tradeId"]?.jsonPrimitive?.content shouldBe "t-1"
            only["bookId"]?.jsonPrimitive?.content shouldBe "book-1"
        }
    }

    test("GET /api/v1/audit/events combines eventType with from/to window") {
        repository.save(event(tradeId = "a", eventType = "TRADE_BOOKED", receivedAt = Instant.parse("2026-05-20T10:30:00Z")))
        repository.save(event(tradeId = "b", eventType = "TRADE_AMENDED", receivedAt = Instant.parse("2026-05-20T10:30:00Z")))
        repository.save(event(tradeId = "c", eventType = "TRADE_AMENDED", receivedAt = Instant.parse("2026-05-20T14:00:00Z")))

        testApplication {
            application { module(repository) }
            val response = client.get(
                "/api/v1/audit/events?eventType=TRADE_AMENDED&from=2026-05-20T09:00:00Z&to=2026-05-20T11:00:00Z"
            )
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText())
            body.jsonArray.size shouldBe 1
            body.tradeIds() shouldBe listOf("b")
        }
    }

    test("cursor pagination still works alongside the new filters") {
        // 5 TRADE_AMENDED events plus noise of another type.
        repeat(5) { i ->
            repository.save(
                event(
                    tradeId = "amend-${i + 1}",
                    eventType = "TRADE_AMENDED",
                    receivedAt = BASE_TIME.plusSeconds(i.toLong()),
                )
            )
        }
        repository.save(event(tradeId = "noise", eventType = "TRADE_BOOKED"))

        testApplication {
            application { module(repository) }

            // First page: limit 2, filtered by eventType.
            val firstResponse = client.get("/api/v1/audit/events?eventType=TRADE_AMENDED&limit=2")
            firstResponse.status shouldBe HttpStatusCode.OK
            val firstPage = Json.parseToJsonElement(firstResponse.bodyAsText()).jsonArray
            firstPage.size shouldBe 2
            firstPage.eventTypes() shouldBe listOf("TRADE_AMENDED", "TRADE_AMENDED")
            val cursor = firstPage.last().jsonObject["id"]!!.jsonPrimitive.long

            // Second page resumes after the cursor, still filtered.
            val secondResponse =
                client.get("/api/v1/audit/events?eventType=TRADE_AMENDED&limit=2&afterId=$cursor")
            secondResponse.status shouldBe HttpStatusCode.OK
            val secondPage = Json.parseToJsonElement(secondResponse.bodyAsText()).jsonArray
            secondPage.size shouldBe 2
            secondPage.eventTypes() shouldBe listOf("TRADE_AMENDED", "TRADE_AMENDED")

            // No overlap between pages.
            val firstIds = firstPage.map { it.jsonObject["id"]!!.jsonPrimitive.long }
            val secondIds = secondPage.map { it.jsonObject["id"]!!.jsonPrimitive.long }
            firstIds.intersect(secondIds.toSet()).isEmpty() shouldBe true
        }
    }
})
