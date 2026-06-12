package com.kinetix.audit.routes

import com.kinetix.audit.model.AuditEvent
import com.kinetix.audit.module
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.audit.persistence.AuditHasher
import com.kinetix.audit.persistence.VerificationCheckpoint
import com.kinetix.audit.persistence.VerificationCheckpointRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.time.Instant

class AuditRoutesTest : FunSpec({

    val repository = mockk<AuditEventRepository>()
    val checkpointRepository = mockk<VerificationCheckpointRepository>(relaxed = true)

    beforeEach { clearMocks(repository, checkpointRepository) }

    test("GET /api/v1/audit/events returns 200 with event list") {
        val events = listOf(
            AuditEvent(
                id = 1,
                tradeId = "t-1",
                bookId = "port-1",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "100",
                priceAmount = "150.00",
                priceCurrency = "USD",
                tradedAt = "2025-01-15T10:00:00Z",
                receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
            ),
            AuditEvent(
                id = 2,
                tradeId = "t-2",
                bookId = "port-1",
                instrumentId = "MSFT",
                assetClass = "EQUITY",
                side = "SELL",
                quantity = "50",
                priceAmount = "300.00",
                priceCurrency = "USD",
                tradedAt = "2025-01-15T11:00:00Z",
                receivedAt = Instant.parse("2025-01-15T11:00:01Z"),
            ),
        )
        coEvery {
            repository.findPage(0L, 1000, null, null, null, null, null)
        } returns events

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 2
            body[0].jsonObject["tradeId"]?.jsonPrimitive?.content shouldBe "t-1"
            body[0].jsonObject["bookId"]?.jsonPrimitive?.content shouldBe "port-1"
            body[0].jsonObject["instrumentId"]?.jsonPrimitive?.content shouldBe "AAPL"
            body[1].jsonObject["tradeId"]?.jsonPrimitive?.content shouldBe "t-2"
        }
    }

    test("GET /api/v1/audit/events surfaces correlationId when the event carries one") {
        val events = listOf(
            AuditEvent(
                id = 1,
                tradeId = "t-1",
                bookId = "port-1",
                instrumentId = "AAPL",
                assetClass = "EQUITY",
                side = "BUY",
                quantity = "100",
                priceAmount = "150.00",
                priceCurrency = "USD",
                tradedAt = "2025-01-15T10:00:00Z",
                receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
                correlationId = "corr-abc-123",
            ),
            AuditEvent(
                id = 2,
                tradeId = "t-2",
                bookId = "port-1",
                instrumentId = "MSFT",
                assetClass = "EQUITY",
                side = "SELL",
                quantity = "50",
                priceAmount = "300.00",
                priceCurrency = "USD",
                tradedAt = "2025-01-15T11:00:00Z",
                receivedAt = Instant.parse("2025-01-15T11:00:01Z"),
                correlationId = null,
            ),
        )
        coEvery {
            repository.findPage(0L, 1000, null, null, null, null, null)
        } returns events

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body[0].jsonObject["correlationId"]?.jsonPrimitive?.content shouldBe "corr-abc-123"
            // Null correlationId carries no value on the wire (absent or JSON null).
            body[1].jsonObject["correlationId"]?.jsonPrimitive?.contentOrNull shouldBe null
        }
    }

    test("GET /api/v1/audit/events returns empty array when no events") {
        coEvery {
            repository.findPage(0L, 1000, null, null, null, null, null)
        } returns emptyList()

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "[]"
        }
    }

    test("GET /api/v1/audit/events?bookId=X filters by book") {
        val event = AuditEvent(
            id = 1,
            tradeId = "t-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
            receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
        )
        coEvery {
            repository.findPage(0L, 1000, "port-1", null, null, null, null)
        } returns listOf(event)

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?bookId=port-1")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["bookId"]?.jsonPrimitive?.content shouldBe "port-1"

            coVerify(exactly = 1) { repository.findPage(0L, 1000, "port-1", null, null, null, null) }
            coVerify(exactly = 0) { repository.findAll() }
        }
    }

    test("GET /api/v1/audit/events?eventType=UNKNOWN returns 400") {
        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?eventType=NOT_A_TYPE")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/audit/events?from=invalid returns 400") {
        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/events?from=not-a-date")
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /api/v1/audit/verify returns valid true for valid chain") {
        val event1 = AuditEvent(
            id = 1,
            tradeId = "t-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
            receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
        )
        val hash1 = AuditHasher.computeHash(event1, null)
        val chained1 = event1.copy(previousHash = null, recordHash = hash1)

        val event2 = AuditEvent(
            id = 2,
            tradeId = "t-2",
            bookId = "port-1",
            instrumentId = "MSFT",
            assetClass = "EQUITY",
            side = "SELL",
            quantity = "50",
            priceAmount = "300.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T11:00:00Z",
            receivedAt = Instant.parse("2025-01-15T11:00:01Z"),
        )
        val hash2 = AuditHasher.computeHash(event2, hash1)
        val chained2 = event2.copy(previousHash = hash1, recordHash = hash2)

        coEvery { repository.findPage(0L, 10000) } returns listOf(chained1, chained2)

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/verify")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["valid"]?.jsonPrimitive?.boolean shouldBe true
            body["eventCount"]?.jsonPrimitive?.int shouldBe 2
        }
    }

    test("GET /api/v1/audit/verify returns valid true for empty chain") {
        coEvery { repository.findPage(0L, 10000) } returns emptyList()

        testApplication {
            application { module(repository) }
            val response = client.get("/api/v1/audit/verify")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["valid"]?.jsonPrimitive?.boolean shouldBe true
            body["eventCount"]?.jsonPrimitive?.int shouldBe 0
        }
    }

    test("GET /health still returns 200 when audit routes are installed") {
        testApplication {
            application { module(repository) }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    // ------------------------------------------------------------------ checkpoint persistence (AUD-04)

    test("verify persists a checkpoint after successful full verification") {
        val event1 = AuditEvent(
            id = 1,
            tradeId = "t-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
            receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
        )
        val hash1 = AuditHasher.computeHash(event1, null)
        val chained1 = event1.copy(previousHash = null, recordHash = hash1)

        coEvery { checkpointRepository.findLatest() } returns null
        coEvery { repository.findPage(0L, 10000) } returns listOf(chained1)

        testApplication {
            application { module(repository, checkpointRepository) }
            client.get("/api/v1/audit/verify")
        }

        val saved = slot<VerificationCheckpoint>()
        coVerify { checkpointRepository.save(capture(saved)) }
        saved.captured.lastEventId shouldBe 1L
        saved.captured.lastHash shouldBe hash1
        saved.captured.eventCount shouldBe 1L
    }

    test("verify resumes from last checkpoint on subsequent calls") {
        val event1 = AuditEvent(
            id = 1,
            tradeId = "t-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
            receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
        )
        val hash1 = AuditHasher.computeHash(event1, null)

        val checkpoint = VerificationCheckpoint(
            id = 1L,
            lastEventId = 1L,
            lastHash = hash1,
            eventCount = 1L,
            verifiedAt = Instant.parse("2026-03-26T09:00:00Z"),
        )
        coEvery { checkpointRepository.findLatest() } returns checkpoint
        // No new events since the checkpoint
        coEvery { repository.findPage(1L, 10000) } returns emptyList()

        testApplication {
            application { module(repository, checkpointRepository) }
            val response = client.get("/api/v1/audit/verify")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["valid"]?.jsonPrimitive?.boolean shouldBe true
            // 1 event already verified by checkpoint
            body["eventCount"]?.jsonPrimitive?.int shouldBe 1
        }

        // Verify scan started after checkpoint, not from id=0
        coVerify { repository.findPage(1L, 10000) }
        coVerify(exactly = 0) { repository.findPage(0L, 10000) }
    }

    test("verify does not persist checkpoint when chain is invalid") {
        val event1 = AuditEvent(
            id = 1,
            tradeId = "t-1",
            bookId = "port-1",
            instrumentId = "AAPL",
            assetClass = "EQUITY",
            side = "BUY",
            quantity = "100",
            priceAmount = "150.00",
            priceCurrency = "USD",
            tradedAt = "2025-01-15T10:00:00Z",
            receivedAt = Instant.parse("2025-01-15T10:00:01Z"),
            previousHash = null,
            recordHash = "WRONG-HASH",
        )
        coEvery { checkpointRepository.findLatest() } returns null
        coEvery { repository.findPage(0L, 10000) } returns listOf(event1)

        testApplication {
            application { module(repository, checkpointRepository) }
            val response = client.get("/api/v1/audit/verify")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["valid"]?.jsonPrimitive?.boolean shouldBe false
        }

        coVerify(exactly = 0) { checkpointRepository.save(any()) }
    }
})
