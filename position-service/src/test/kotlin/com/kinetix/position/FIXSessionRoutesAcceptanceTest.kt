package com.kinetix.position

import com.kinetix.position.fix.ExposedFIXSessionRepository
import com.kinetix.position.fix.FIXSession
import com.kinetix.position.fix.FIXSessionDisconnectedEvent
import com.kinetix.position.fix.FIXSessionEventPublisher
import com.kinetix.position.fix.FIXSessionStatus
import com.kinetix.position.fix.KafkaFIXSessionEventPublisher
import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.routes.fixSessionRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.patch
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Duration
import java.time.Instant

private fun Application.configureFixSessionTestApp(
    fixSessionRepository: ExposedFIXSessionRepository,
    sessionEventPublisher: FIXSessionEventPublisher? = null,
) {
    install(ContentNegotiation) { json() }
    routing {
        fixSessionRoutes(fixSessionRepository, sessionEventPublisher)
    }
}

class FIXSessionRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val fixSessionRepository = ExposedFIXSessionRepository(db)

    val bootstrapServers = KafkaTestSetup.start()
    val producer = KafkaTestSetup.createProducer(bootstrapServers)

    fun publisherFor(topic: String) = KafkaFIXSessionEventPublisher(producer, topic)

    fun consumerFor(topic: String, group: String): KafkaConsumer<String, String> {
        val consumer = KafkaTestSetup.createConsumer(bootstrapServers, group)
        consumer.subscribe(listOf(topic))
        return consumer
    }

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE fix_sessions RESTART IDENTITY CASCADE")
        }
    }

    test("GET /api/v1/fix/sessions returns empty list when no sessions exist") {
        testApplication {
            application { configureFixSessionTestApp(fixSessionRepository) }
            val response = client.get("/api/v1/fix/sessions")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 0
        }
    }

    test("GET /api/v1/fix/sessions returns connected sessions") {
        val connectedAt = Instant.parse("2026-03-24T10:00:00Z")
        fixSessionRepository.save(
            FIXSession(
                sessionId = "FIX-BROKER-01",
                counterparty = "Goldman Sachs",
                status = FIXSessionStatus.CONNECTED,
                lastMessageAt = connectedAt,
                inboundSeqNum = 1042,
                outboundSeqNum = 988,
            )
        )

        testApplication {
            application { configureFixSessionTestApp(fixSessionRepository) }
            val response = client.get("/api/v1/fix/sessions")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 1
            body[0].jsonObject["sessionId"]!!.jsonPrimitive.content shouldBe "FIX-BROKER-01"
            body[0].jsonObject["status"]!!.jsonPrimitive.content shouldBe "CONNECTED"
            body[0].jsonObject["counterparty"]!!.jsonPrimitive.content shouldBe "Goldman Sachs"
            body[0].jsonObject["inboundSeqNum"]!!.jsonPrimitive.content shouldBe "1042"
        }
    }

    test("GET /api/v1/fix/sessions returns disconnected sessions with null lastMessageAt") {
        fixSessionRepository.save(
            FIXSession(
                sessionId = "FIX-BROKER-02",
                counterparty = "JPMorgan",
                status = FIXSessionStatus.DISCONNECTED,
                lastMessageAt = null,
                inboundSeqNum = 0,
                outboundSeqNum = 0,
            )
        )

        testApplication {
            application { configureFixSessionTestApp(fixSessionRepository) }
            val response = client.get("/api/v1/fix/sessions")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body[0].jsonObject["status"]!!.jsonPrimitive.content shouldBe "DISCONNECTED"
            body[0].jsonObject.containsKey("lastMessageAt") shouldBe true
        }
    }

    test("GET /api/v1/fix/sessions returns multiple sessions with mixed status") {
        fixSessionRepository.save(FIXSession("S1", "Broker A", FIXSessionStatus.CONNECTED, Instant.now(), 100, 50))
        fixSessionRepository.save(FIXSession("S2", "Broker B", FIXSessionStatus.DISCONNECTED, null, 0, 0))
        fixSessionRepository.save(FIXSession("S3", "Broker C", FIXSessionStatus.RECONNECTING, Instant.now(), 25, 12))

        testApplication {
            application { configureFixSessionTestApp(fixSessionRepository) }
            val response = client.get("/api/v1/fix/sessions")
            response.status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.size shouldBe 3
            response.bodyAsText() shouldContain "RECONNECTING"
        }
    }

    // EXEC-06: FIX session disconnect events
    test("PATCH /api/v1/fix/sessions/{id}/status to DISCONNECTED emits FIX_SESSION_DISCONNECTED event") {
        val topic = "fix.session.events.fsr-disconnect-1"
        val consumer = consumerFor(topic, "fsr-disconnect-1-group")
        try {
            val publisher = publisherFor(topic)
            fixSessionRepository.save(
                FIXSession("FIX-01", "Goldman Sachs", FIXSessionStatus.CONNECTED, Instant.now(), 100, 50)
            )

            testApplication {
                application { configureFixSessionTestApp(fixSessionRepository, publisher) }
                val response = client.patch("/api/v1/fix/sessions/FIX-01/status") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "DISCONNECTED"}""")
                }
                response.status shouldBe HttpStatusCode.NoContent

                val records = consumer.poll(Duration.ofSeconds(10))
                records.count() shouldBe 1
                val record = records.first()
                record.key() shouldBe "FIX-01"
                val event = Json.decodeFromString<FIXSessionDisconnectedEvent>(record.value())
                event.sessionId shouldBe "FIX-01"
                event.counterparty shouldBe "Goldman Sachs"
            }

            val updatedSession = fixSessionRepository.findById("FIX-01")!!
            updatedSession.status shouldBe FIXSessionStatus.DISCONNECTED
        } finally {
            consumer.close()
        }
    }

    test("PATCH session status to CONNECTED does not emit disconnect event") {
        val topic = "fix.session.events.fsr-connect-1"
        val consumer = consumerFor(topic, "fsr-connect-1-group")
        try {
            val publisher = publisherFor(topic)
            fixSessionRepository.save(
                FIXSession("FIX-02", "JPMorgan", FIXSessionStatus.DISCONNECTED, null, 0, 0)
            )

            testApplication {
                application { configureFixSessionTestApp(fixSessionRepository, publisher) }
                val response = client.patch("/api/v1/fix/sessions/FIX-02/status") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "CONNECTED"}""")
                }
                response.status shouldBe HttpStatusCode.NoContent

                val records = consumer.poll(Duration.ofMillis(750))
                records.count() shouldBe 0
            }

            val updatedSession = fixSessionRepository.findById("FIX-02")!!
            updatedSession.status shouldBe FIXSessionStatus.CONNECTED
        } finally {
            consumer.close()
        }
    }

    test("PATCH session status returns 404 when session not found") {
        testApplication {
            application { configureFixSessionTestApp(fixSessionRepository) }
            val response = client.patch("/api/v1/fix/sessions/UNKNOWN/status") {
                contentType(ContentType.Application.Json)
                setBody("""{"status": "DISCONNECTED"}""")
            }
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("PATCH session status returns 400 for invalid status") {
        testApplication {
            application { configureFixSessionTestApp(fixSessionRepository) }
            val response = client.patch("/api/v1/fix/sessions/FIX-01/status") {
                contentType(ContentType.Application.Json)
                setBody("""{"status": "INVALID"}""")
            }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    afterSpec {
        producer.close()
    }
})
