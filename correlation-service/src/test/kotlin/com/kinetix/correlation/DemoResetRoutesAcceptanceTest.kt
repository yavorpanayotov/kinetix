package com.kinetix.correlation

import com.kinetix.correlation.persistence.DatabaseTestSetup
import com.kinetix.correlation.persistence.ExposedCorrelationMatrixRepository
import com.kinetix.correlation.routes.demoResetRoutes
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
    val correlationMatrixRepository = ExposedCorrelationMatrixRepository(db)
    val resetToken = "test-reset-token"

    fun Application.configureDemoResetApp() {
        install(ContentNegotiation) { json() }
        routing {
            demoResetRoutes(
                db = db,
                correlationMatrixRepository = correlationMatrixRepository,
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

            val response = client.post("/api/v1/internal/correlation/demo-reset") {
                header("X-Demo-Reset-Token", "wrong-token")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("resets and reseeds correlation matrices when token matches") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/correlation/demo-reset") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Correlation data reset and reseeded"

            (rowCount("correlation_matrices") > 0L) shouldBe true
        }
    }
})
