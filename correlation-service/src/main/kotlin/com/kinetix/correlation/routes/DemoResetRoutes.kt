package com.kinetix.correlation.routes

import com.kinetix.correlation.persistence.CorrelationMatrixRepository
import com.kinetix.correlation.seed.DevDataSeeder
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
    correlationMatrixRepository: CorrelationMatrixRepository,
    resetToken: String,
) {
    route("/api/v1/internal/correlation") {
        post("/demo-reset") {
            val token = call.request.headers["X-Demo-Reset-Token"]
            if (token != resetToken) {
                call.respond(HttpStatusCode.Forbidden, DemoResetResponse("error", "Invalid reset token"))
                return@post
            }

            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE correlation_matrices RESTART IDENTITY CASCADE")
            }

            DevDataSeeder(correlationMatrixRepository).seed()

            call.respond(DemoResetResponse("ok", "Correlation data reset and reseeded"))
        }
    }
}
