package com.kinetix.price.routes

import com.kinetix.price.persistence.PriceRepository
import com.kinetix.price.seed.DevDataSeeder
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
    repository: PriceRepository,
    resetToken: String,
) {
    route("/api/v1/internal/price") {
        post("/demo-reset") {
            val token = call.request.headers["X-Demo-Reset-Token"]
            if (token != resetToken) {
                call.respond(HttpStatusCode.Forbidden, DemoResetResponse("error", "Invalid reset token"))
                return@post
            }

            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE market_data RESTART IDENTITY CASCADE")
            }

            DevDataSeeder(repository).seed()

            call.respond(DemoResetResponse("ok", "Price data reset and reseeded"))
        }
    }
}
