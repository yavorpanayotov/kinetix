package com.kinetix.position.routes

import com.kinetix.common.demo.SeedProfile
import com.kinetix.common.demo.UnknownScenarioException
import com.kinetix.position.fix.ExecutionCostRepository
import com.kinetix.position.persistence.LimitDefinitionRepository
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.persistence.TradeEventRepository
import com.kinetix.position.seed.DevDataSeeder
import com.kinetix.position.service.TradeBookingService
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
    tradeBookingService: TradeBookingService,
    positionRepository: PositionRepository,
    limitDefinitionRepo: LimitDefinitionRepository,
    executionCostRepo: ExecutionCostRepository? = null,
    tradeEventRepository: TradeEventRepository? = null,
    resetToken: String,
) {
    route("/api/v1/internal/position") {
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
                exec("TRUNCATE TABLE positions RESTART IDENTITY CASCADE")
                // TRUNCATE bypasses the prevent_trade_event_deletion row-level trigger
                // (V10 migration). DELETE would be rejected. Demo mode is the only caller;
                // production trade events remain immutable per the trigger.
                exec("TRUNCATE TABLE trade_events RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE limit_definitions RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE limit_temporary_increases RESTART IDENTITY CASCADE")
                exec("TRUNCATE TABLE execution_cost_analysis RESTART IDENTITY CASCADE")
            }

            DevDataSeeder(
                tradeBookingService = tradeBookingService,
                positionRepository = positionRepository,
                limitDefinitionRepo = limitDefinitionRepo,
                executionCostRepo = executionCostRepo,
                tradeEventRepository = tradeEventRepository,
            ).seed(profile)

            call.respond(DemoResetResponse("ok", "Position data reset and reseeded for ${profile.id}"))
        }
    }
}
