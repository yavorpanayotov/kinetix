package com.kinetix.volatility.routes

import com.kinetix.volatility.persistence.VolSurfaceRepository
import com.kinetix.volatility.seed.DevDataSeeder
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
    volSurfaceRepository: VolSurfaceRepository,
    resetToken: String,
) {
    route("/api/v1/internal/volatility") {
        post("/demo-reset") {
            val token = call.request.headers["X-Demo-Reset-Token"]
            if (token != resetToken) {
                call.respond(HttpStatusCode.Forbidden, DemoResetResponse("error", "Invalid reset token"))
                return@post
            }

            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE volatility_surface_points RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE volatility_surfaces RESTART IDENTITY CASCADE")
            }

            DevDataSeeder(volSurfaceRepository).seed()

            call.respond(DemoResetResponse("ok", "Volatility data reset and reseeded"))
        }
    }
}
