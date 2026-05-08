package com.kinetix.volatility

import com.kinetix.volatility.persistence.DatabaseTestSetup
import com.kinetix.volatility.persistence.ExposedVolSurfaceRepository
import com.kinetix.volatility.routes.demoResetRoutes
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
    val volSurfaceRepository = ExposedVolSurfaceRepository(db)
    val resetToken = "test-reset-token"

    fun Application.configureDemoResetApp() {
        install(ContentNegotiation) { json() }
        routing {
            demoResetRoutes(
                db = db,
                volSurfaceRepository = volSurfaceRepository,
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

            val response = client.post("/api/v1/internal/volatility/demo-reset") {
                header("X-Demo-Reset-Token", "wrong-token")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("resets and reseeds volatility surfaces when token matches") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/volatility/demo-reset") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Volatility data reset and reseeded"

            (rowCount("volatility_surfaces") > 0L) shouldBe true
            (rowCount("volatility_surface_points") > 0L) shouldBe true
        }
    }
})
