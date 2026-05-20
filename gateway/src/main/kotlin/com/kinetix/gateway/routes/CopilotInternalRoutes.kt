package com.kinetix.gateway.routes

import com.kinetix.gateway.dtos.CopilotPushRequest
import com.kinetix.gateway.dtos.ErrorResponse
import com.kinetix.gateway.websocket.CopilotBroadcaster
import com.kinetix.gateway.websocket.CopilotWebSocketMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * HTTP header carrying the cluster-internal shared secret. Set by
 * `ai-insights-service` on every `POST /internal/copilot/push` request and
 * matched server-side against [CopilotInternalAuth.expectedToken].
 */
const val INTERNAL_REQUEST_TOKEN_HEADER: String = "X-Internal-Token"

/**
 * Cluster-internal authentication for the `/internal/copilot/push` route.
 *
 * The intraday-push endpoint accepts a payload from `ai-insights-service`
 * and carries no end-user JWT — `ai-insights-service` is a service principal,
 * not a logged-in user (ADR-0036). It must, however, reject *external*
 * traffic: a misconfigured ingress that exposed `/internal/...` to the public
 * internet would otherwise let anyone inject Copilot push frames.
 *
 * The gateway already establishes the shared-secret-header pattern for
 * privileged non-JWT routes (`X-Demo-Admin-Key` on `/api/v1/admin/demo-reset`,
 * see [demoAdminRoutes]). This mirrors it: a token known only to in-cluster
 * callers is required on the [INTERNAL_REQUEST_TOKEN_HEADER] header. External
 * callers cannot supply it, so they are rejected with `403 Forbidden`; the
 * in-cluster `ai-insights-service` sets it and flows through. The token is
 * sourced from the `COPILOT_INTERNAL_TOKEN` environment variable and is the
 * same value configured on `ai-insights-service` in Docker Compose / Helm.
 */
class CopilotInternalAuth(val expectedToken: String) {
    /** True when [provided] matches the configured cluster-internal token. */
    fun isAuthorized(provided: String?): Boolean =
        provided != null && provided == expectedToken
}

/**
 * Gateway internal route for intraday Copilot push (PR 7 / ADR-0036).
 *
 * `POST /internal/copilot/push` accepts the [CopilotPushRequest] payload that
 * `ai-insights-service` composes when an intraday risk threshold breaches,
 * and enqueues it on the [broadcaster] for fan-out to subscribed
 * `/ws/copilot` WebSocket clients (the WebSocket route itself is checkbox
 * 7.6's job).
 *
 * This route is **cluster-internal only**: it is not exposed through the
 * gateway's public ingress and carries no JWT challenge. The
 * [internalAuth] shared-secret check rejects any caller that cannot present
 * the in-cluster token — see [CopilotInternalAuth].
 */
fun Route.copilotInternalRoutes(
    broadcaster: CopilotBroadcaster,
    internalAuth: CopilotInternalAuth,
) {
    post("/internal/copilot/push") {
        val token = call.request.headers[INTERNAL_REQUEST_TOKEN_HEADER]
        if (!internalAuth.isAuthorized(token)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("forbidden", "This route is restricted to cluster-internal callers."),
            )
            return@post
        }

        val push = call.receive<CopilotPushRequest>()
        broadcaster.broadcast(CopilotWebSocketMessage(push = push))
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
    }
}
