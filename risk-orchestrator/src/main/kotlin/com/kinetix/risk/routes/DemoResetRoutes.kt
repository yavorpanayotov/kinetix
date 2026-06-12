package com.kinetix.risk.routes

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.demo.UnknownScenarioException
import com.kinetix.risk.persistence.CounterpartyExposureRepository
import com.kinetix.risk.persistence.PnlAttributionRepository
import com.kinetix.risk.seed.DevDataSeeder
import com.kinetix.risk.service.ValuationJobRecorder
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

@Serializable
data class DemoResetResponse(val status: String, val message: String)

fun Route.demoResetRoutes(
    riskDb: Database,
    jobRecorder: ValuationJobRecorder,
    exposureRepository: CounterpartyExposureRepository,
    pnlAttributionRepository: PnlAttributionRepository? = null,
    resetToken: String,
) {
    route("/api/v1/internal/risk") {
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

            jobRecorder.deleteByTriggeredBy("SEED")

            newSuspendedTransaction(db = riskDb) {
                exec("DELETE FROM daily_risk_snapshots WHERE snapshot_date > '2026-02-27'")
                exec("DELETE FROM intraday_pnl_snapshots WHERE snapshot_at > '2026-02-27'")
                exec("DELETE FROM counterparty_exposure_history")
                exec("DELETE FROM pnl_attributions")
            }

            DevDataSeeder(jobRecorder, exposureRepository, pnlAttributionRepository).seed()

            // The reset above rewrote daily_risk_snapshots / pnl_attributions, but
            // risk_positions_flat is otherwise refreshed only after EOD promotion —
            // without this, reports serve the pre-reset rows (or nothing) until the
            // next promotion (kx-cg2t). CONCURRENTLY requires an already-populated
            // view; fall back to a plain refresh the first time.
            newSuspendedTransaction(db = riskDb) {
                try {
                    exec("REFRESH MATERIALIZED VIEW CONCURRENTLY risk_positions_flat")
                } catch (_: Exception) {
                    exec("REFRESH MATERIALIZED VIEW risk_positions_flat")
                }
            }

            call.respond(DemoResetResponse("ok", "Risk data reset and reseeded for ${profile.id}"))
        }
    }
}
