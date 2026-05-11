package com.kinetix.referencedata.routes

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.demo.UnknownScenarioException
import com.kinetix.referencedata.persistence.CounterpartyRepository
import com.kinetix.referencedata.persistence.CreditSpreadRepository
import com.kinetix.referencedata.persistence.DeskRepository
import com.kinetix.referencedata.persistence.DivisionRepository
import com.kinetix.referencedata.persistence.DividendYieldRepository
import com.kinetix.referencedata.persistence.InstrumentLiquidityRepository
import com.kinetix.referencedata.persistence.InstrumentRepository
import com.kinetix.referencedata.persistence.NettingAgreementRepository
import com.kinetix.referencedata.seed.DevDataSeeder
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
    dividendYieldRepository: DividendYieldRepository,
    creditSpreadRepository: CreditSpreadRepository,
    instrumentRepository: InstrumentRepository? = null,
    divisionRepository: DivisionRepository? = null,
    deskRepository: DeskRepository? = null,
    liquidityRepository: InstrumentLiquidityRepository? = null,
    counterpartyRepository: CounterpartyRepository? = null,
    nettingAgreementRepository: NettingAgreementRepository? = null,
    resetToken: String,
) {
    route("/api/v1/internal/reference-data") {
        post("/demo-reset") {
            val token = call.request.headers["X-Demo-Reset-Token"]
            if (token != resetToken) {
                call.respond(HttpStatusCode.Forbidden, DemoResetResponse("error", "Invalid reset token"))
                return@post
            }

            val profile = try {
                SeedProfile.parseOrDefault(call.request.queryParameters["scenario"])
            } catch (e: UnknownScenarioException) {
                call.respond(HttpStatusCode.BadRequest, DemoResetResponse("UNKNOWN_SCENARIO", "Unknown scenario '${e.scenario}'"))
                return@post
            }
            if (!profile.implemented) {
                call.respond(HttpStatusCode.BadRequest, DemoResetResponse("SCENARIO_NOT_AVAILABLE", "Scenario '${profile.id}' is not yet implemented"))
                return@post
            }

            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE netting_agreements RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE counterparty_master RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE instrument_liquidity RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE instruments RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE desks RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE divisions RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE dividend_yields RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE credit_spreads RESTART IDENTITY CASCADE")
            }

            DevDataSeeder(
                dividendYieldRepository = dividendYieldRepository,
                creditSpreadRepository = creditSpreadRepository,
                instrumentRepository = instrumentRepository,
                divisionRepository = divisionRepository,
                deskRepository = deskRepository,
                liquidityRepository = liquidityRepository,
                counterpartyRepository = counterpartyRepository,
                nettingAgreementRepository = nettingAgreementRepository,
            ).seed(profile)

            call.respond(DemoResetResponse("ok", "Reference data reset and reseeded for ${profile.id}"))
        }
    }
}
