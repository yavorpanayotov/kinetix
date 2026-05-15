package com.kinetix.price

import com.kinetix.price.persistence.DatabaseTestSetup
import com.kinetix.price.persistence.ExposedPriceRepository
import com.kinetix.price.persistence.PriceTable
import com.kinetix.price.routes.demoResetRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class DemoResetRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedPriceRepository(db)
    val resetToken = "test-reset-token"

    fun Application.configureDemoResetApp() {
        install(ContentNegotiation) { json() }
        routing {
            demoResetRoutes(db = db, repository = repository, resetToken = resetToken)
        }
    }

    test("returns 403 when reset token is invalid") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/price/demo-reset") {
                header("X-Demo-Reset-Token", "wrong-token")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("returns 403 when reset token header is absent") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/price/demo-reset")

            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("resets and reseeds prices when token matches") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/price/demo-reset") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "ok"
            response.bodyAsText() shouldContain "Price data reset and reseeded"

            val rowCount = newSuspendedTransaction(db = db) {
                PriceTable.selectAll().count().toInt()
            }
            rowCount shouldBeGreaterThan 0
        }
    }
})
