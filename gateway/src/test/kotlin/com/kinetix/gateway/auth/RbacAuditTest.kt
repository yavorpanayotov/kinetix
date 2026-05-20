package com.kinetix.gateway.auth

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.common.security.Role
import com.kinetix.gateway.audit.GovernanceAuditPublisher
import com.kinetix.gateway.client.PositionServiceClient
import com.kinetix.gateway.client.RiskServiceClient
import com.kinetix.gateway.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.RecordMetadata
import java.util.concurrent.Future

class RbacAuditTest : FunSpec({

    val positionClient = mockk<PositionServiceClient>()
    val riskClient = mockk<RiskServiceClient>()
    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()
    val producer = mockk<KafkaProducer<String, String>>()
    val auditPublisher = GovernanceAuditPublisher(producer, topic = "governance.audit")

    fun givenFuture(): Future<RecordMetadata> = mockk(relaxed = true)

    beforeEach {
        clearMocks(positionClient, riskClient, producer)
    }

    test("publishes RBAC_ACCESS_DENIED audit event when user lacks required permission") {
        // VIEWER does not have WRITE_TRADES
        val token = TestJwtHelper.generateToken(userId = "user-viewer", roles = listOf(Role.VIEWER))
        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, auditPublisher = auditPublisher, jwkProvider = jwkProvider)
            }
            val response = client.post("/api/v1/books/port-1/trades") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"tradeId":"t-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z"}""")
            }

            response.status shouldBe HttpStatusCode.Forbidden
        }

        verify(atLeast = 1) { producer.send(any()) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.RBAC_ACCESS_DENIED
        decoded.userId shouldBe "user-viewer"
    }

    test("RBAC_ACCESS_DENIED audit event carries the X-Correlation-ID from the request MDC") {
        val token = TestJwtHelper.generateToken(userId = "user-viewer", roles = listOf(Role.VIEWER))
        val publishedSlot = slot<org.apache.kafka.clients.producer.ProducerRecord<String, String>>()
        every { producer.send(capture(publishedSlot)) } returns givenFuture()

        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, auditPublisher = auditPublisher, jwkProvider = jwkProvider)
            }
            val response = client.post("/api/v1/books/port-1/trades") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Correlation-ID", "corr-gateway-999")
                contentType(ContentType.Application.Json)
                setBody("""{"tradeId":"t-1","instrumentId":"AAPL","assetClass":"EQUITY","side":"BUY","quantity":"100","priceAmount":"150.00","priceCurrency":"USD","tradedAt":"2025-01-15T10:00:00Z"}""")
            }

            response.status shouldBe HttpStatusCode.Forbidden
        }

        verify(atLeast = 1) { producer.send(any()) }
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<GovernanceAuditEvent>(publishedSlot.captured.value())
        decoded.eventType shouldBe AuditEventType.RBAC_ACCESS_DENIED
        decoded.correlationId shouldBe "corr-gateway-999"
    }

    test("does not publish audit event when user has required permission") {
        coEvery { riskClient.calculateVaR(any()) } returns mockk(relaxed = true)
        val token = TestJwtHelper.generateToken(userId = "user-rm", roles = listOf(Role.RISK_MANAGER))

        testApplication {
            application {
                module(jwtConfig, riskClient = riskClient, auditPublisher = auditPublisher, jwkProvider = jwkProvider)
            }
            val response = client.post("/api/v1/risk/var/port-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            response.status shouldBe HttpStatusCode.OK
        }

        verify(exactly = 0) { producer.send(any()) }
    }

    test("does not publish audit event for unauthenticated requests (401 path is not RBAC denial)") {
        testApplication {
            application {
                module(jwtConfig, positionClient = positionClient, auditPublisher = auditPublisher, jwkProvider = jwkProvider)
            }
            val response = client.get("/api/v1/books")
            response.status shouldBe HttpStatusCode.Unauthorized
        }

        verify(exactly = 0) { producer.send(any()) }
    }
})
