package com.kinetix.gateway.routes

import com.kinetix.gateway.dtos.ActiveScenarioDto
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.atomic.AtomicReference

// Active scenario indicator. Gap 2 (Phase 2) will wire ?scenario=X through
// the demo-reset endpoint and update this reference; until then it returns
// the default "multi-asset" so the UI surface exists and the pill renders.
private val activeScenario = AtomicReference("multi-asset")

fun setActiveScenario(scenario: String) {
    activeScenario.set(scenario)
}

fun Route.demoScenarioRoutes() {
    get("/api/v1/demo/scenario") {
        call.respond(ActiveScenarioDto(scenario = activeScenario.get()))
    }
}
