package com.kinetix.gateway.routes

import com.kinetix.common.demo.RegimeCalendar
import com.kinetix.gateway.dtos.StressWindowDto
import com.kinetix.gateway.dtos.StressWindowsResponse
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.ZoneOffset

// Exposes the demo regime calendar's stress windows so the UI can annotate
// charts with vertical bands marking the 2020 / 2022 analog regimes baked
// into the Phase 0 demo tape.
fun Route.demoStressWindowsRoutes(calendar: RegimeCalendar = RegimeCalendar()) {
    get("/api/v1/demo/stress-windows") {
        val windows = calendar.stressWindows().map { window ->
            StressWindowDto(
                label = window.label,
                start = window.start.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                end = window.end.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
            )
        }
        call.respond(StressWindowsResponse(windows = windows))
    }
}
