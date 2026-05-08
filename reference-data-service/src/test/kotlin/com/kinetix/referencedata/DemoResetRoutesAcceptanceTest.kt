package com.kinetix.referencedata

import com.kinetix.referencedata.persistence.DatabaseTestSetup
import com.kinetix.referencedata.persistence.ExposedCounterpartyRepository
import com.kinetix.referencedata.persistence.ExposedCreditSpreadRepository
import com.kinetix.referencedata.persistence.ExposedDeskRepository
import com.kinetix.referencedata.persistence.ExposedDivisionRepository
import com.kinetix.referencedata.persistence.ExposedDividendYieldRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentLiquidityRepository
import com.kinetix.referencedata.persistence.ExposedInstrumentRepository
import com.kinetix.referencedata.persistence.ExposedNettingAgreementRepository
import com.kinetix.referencedata.routes.demoResetRoutes
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
    val dividendYieldRepository = ExposedDividendYieldRepository(db)
    val creditSpreadRepository = ExposedCreditSpreadRepository(db)
    val instrumentRepository = ExposedInstrumentRepository(db)
    val divisionRepository = ExposedDivisionRepository(db)
    val deskRepository = ExposedDeskRepository(db)
    val liquidityRepository = ExposedInstrumentLiquidityRepository(db)
    val counterpartyRepository = ExposedCounterpartyRepository(db)
    val nettingAgreementRepository = ExposedNettingAgreementRepository(db)
    val resetToken = "test-reset-token"

    fun Application.configureDemoResetApp() {
        install(ContentNegotiation) { json() }
        routing {
            demoResetRoutes(
                db = db,
                dividendYieldRepository = dividendYieldRepository,
                creditSpreadRepository = creditSpreadRepository,
                instrumentRepository = instrumentRepository,
                divisionRepository = divisionRepository,
                deskRepository = deskRepository,
                liquidityRepository = liquidityRepository,
                counterpartyRepository = counterpartyRepository,
                nettingAgreementRepository = nettingAgreementRepository,
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

            val response = client.post("/api/v1/internal/reference-data/demo-reset") {
                header("X-Demo-Reset-Token", "wrong-token")
            }

            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "Invalid reset token"
        }
    }

    test("resets and reseeds reference data when token matches") {
        testApplication {
            application { configureDemoResetApp() }

            val response = client.post("/api/v1/internal/reference-data/demo-reset") {
                header("X-Demo-Reset-Token", resetToken)
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "Reference data reset and reseeded"

            (rowCount("counterparty_master") > 0L) shouldBe true
            (rowCount("netting_agreements") > 0L) shouldBe true
            (rowCount("instruments") > 0L) shouldBe true
            (rowCount("divisions") > 0L) shouldBe true
            (rowCount("desks") > 0L) shouldBe true
        }
    }
})
