package com.kinetix.position

import com.kinetix.position.fix.ExposedExecutionCostRepository
import com.kinetix.position.kafka.KafkaTestSetup
import com.kinetix.position.kafka.KafkaTradeEventPublisher
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedLimitDefinitionRepository
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTradeEventRepository
import com.kinetix.position.routes.demoResetRoutes
import com.kinetix.position.service.ExposedTransactionalRunner
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

class DemoResetRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val positionRepository = ExposedPositionRepository(db)
    val limitDefinitionRepo = ExposedLimitDefinitionRepository(db)
    val executionCostRepo = ExposedExecutionCostRepository(db)
    val tradeEventRepo = ExposedTradeEventRepository(db)
    val transactional = ExposedTransactionalRunner(db)

    val bootstrapServers = KafkaTestSetup.start()
    val producer = KafkaTestSetup.createProducer(bootstrapServers)
    val publisher = KafkaTradeEventPublisher(producer, "trades.lifecycle.demo-reset")

    val tradeBookingService = TradeBookingService(tradeEventRepo, positionRepository, transactional, publisher)
    val resetToken = "test-reset-token"

    fun Application.configureDemoResetApp() {
        install(ContentNegotiation) { json() }
        routing {
            demoResetRoutes(
                db = db,
                tradeBookingService = tradeBookingService,
                positionRepository = positionRepository,
                limitDefinitionRepo = limitDefinitionRepo,
                executionCostRepo = executionCostRepo,
                resetToken = resetToken,
            )
        }
    }

    test("returns 403 when reset token is invalid") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/position/demo-reset") {
                header("X-Demo-Reset-Token", "wrong-token")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("returns 403 when reset token header is absent") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/position/demo-reset")

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("resets and reseeds all data including execution costs") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/position/demo-reset") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "ok"
            response.bodyAsText() shouldContain "Position data reset and reseeded"

            // Verify execution costs were seeded
            val costs = executionCostRepo.findByBookId("equity-growth")
            costs.shouldNotBeEmpty()
            costs.any { it.orderId.startsWith("seed-exec-") } shouldBe true
        }
    }

    afterSpec {
        producer.close()
    }
})
