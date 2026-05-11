package com.kinetix.rates

import com.kinetix.rates.persistence.DatabaseTestSetup
import com.kinetix.rates.persistence.ExposedForwardCurveRepository
import com.kinetix.rates.persistence.ExposedRiskFreeRateRepository
import com.kinetix.rates.persistence.ExposedYieldCurveRepository
import com.kinetix.rates.routes.demoResetRoutes
import io.kotest.core.spec.style.FunSpec
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
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class DemoResetRoutesAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val yieldCurveRepository = ExposedYieldCurveRepository(db)
    val riskFreeRateRepository = ExposedRiskFreeRateRepository(db)
    val forwardCurveRepository = ExposedForwardCurveRepository(db)
    val resetToken = "test-reset-token"

    fun Application.configureDemoResetApp() {
        install(ContentNegotiation) { json() }
        routing {
            demoResetRoutes(
                db = db,
                yieldCurveRepository = yieldCurveRepository,
                riskFreeRateRepository = riskFreeRateRepository,
                forwardCurveRepository = forwardCurveRepository,
                resetToken = resetToken,
            )
        }
    }

    suspend fun rowCount(table: String): Long = newSuspendedTransaction(db = db) {
        var n = 0L
        exec("SELECT COUNT(*) FROM $table") { rs ->
            if (rs.next()) n = rs.getLong(1)
        }
        n
    }

    test("returns 403 when reset token is invalid") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/rates/demo-reset") {
                header("X-Demo-Reset-Token", "wrong-token")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("resets and reseeds yield curves and rates when token matches") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/rates/demo-reset") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Rates data reset and reseeded"

            (rowCount("yield_curves") > 0L) shouldBe true
            (rowCount("yield_curve_tenors") > 0L) shouldBe true
            (rowCount("risk_free_rates") > 0L) shouldBe true
        }
    }

    test("rejects unknown scenario with 400 + UNKNOWN_SCENARIO") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/rates/demo-reset?scenario=does-not-exist") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "UNKNOWN_SCENARIO"
        }
    }

    test("rejects regulatory scenario with 400 + SCENARIO_NOT_AVAILABLE pre-Gap-4") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/rates/demo-reset?scenario=regulatory") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "SCENARIO_NOT_AVAILABLE"
        }
    }
})
