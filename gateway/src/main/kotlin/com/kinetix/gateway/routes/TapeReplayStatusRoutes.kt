package com.kinetix.gateway.routes

import com.kinetix.gateway.dtos.TapeReplayStatusDto
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Phase 3 Gap 13 — tape-replay status header badge backend.
 *
 * Reports the current demo replay state so the UI can render an
 * "Active / Frozen / Live" indicator next to the scenario pill. The
 * status is derived from environment flags shared with the per-service
 * tape replay sweepers (`DEMO_TAPE_REPLAY_ENABLED`) and the demo-mode
 * gate (`DEMO_RESET_TOKEN` presence proxies for demo deployments).
 *
 * - LIVE   — production deployment, no demo flags present.
 * - ACTIVE — demo deployment with tape replay sweepers enabled.
 * - FROZEN — demo deployment but tape replay is disabled, screen is static.
 */
enum class TapeReplayStatus { LIVE, ACTIVE, FROZEN }

fun resolveTapeReplayStatus(env: (String) -> String?): TapeReplayStatus {
    val demoToken = env("DEMO_RESET_TOKEN")
    if (demoToken.isNullOrBlank()) return TapeReplayStatus.LIVE
    val replayEnabled = env("DEMO_TAPE_REPLAY_ENABLED")?.toBooleanStrictOrNull() ?: false
    return if (replayEnabled) TapeReplayStatus.ACTIVE else TapeReplayStatus.FROZEN
}

fun Route.tapeReplayStatusRoutes(env: (String) -> String? = System::getenv) {
    get("/api/v1/demo/replay-status") {
        val status = resolveTapeReplayStatus(env)
        call.respond(TapeReplayStatusDto(status = status.name))
    }
}
