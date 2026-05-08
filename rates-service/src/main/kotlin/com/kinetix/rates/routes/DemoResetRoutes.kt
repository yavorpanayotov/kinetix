package com.kinetix.rates.routes

import com.kinetix.rates.persistence.ForwardCurveRepository
import com.kinetix.rates.persistence.RiskFreeRateRepository
import com.kinetix.rates.persistence.YieldCurveRepository
import com.kinetix.rates.seed.DevDataSeeder
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

@Serializable
data class DemoResetResponse(val status: String, val message: String)

fun Route.demoResetRoutes(
    db: Database,
    yieldCurveRepository: YieldCurveRepository,
    riskFreeRateRepository: RiskFreeRateRepository,
    forwardCurveRepository: ForwardCurveRepository,
    resetToken: String,
) {
    route("/api/v1/internal/rates") {
        post("/demo-reset") {
            val token = call.request.headers["X-Demo-Reset-Token"]
            if (token != resetToken) {
                call.respond(HttpStatusCode.Forbidden, DemoResetResponse("error", "Invalid reset token"))
                return@post
            }

            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE forward_curve_points RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE forward_curves RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE yield_curve_tenors RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE yield_curves RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE risk_free_rates RESTART IDENTITY CASCADE")
            }

            DevDataSeeder(yieldCurveRepository, riskFreeRateRepository, forwardCurveRepository).seed()

            call.respond(DemoResetResponse("ok", "Rates data reset and reseeded"))
        }
    }
}
