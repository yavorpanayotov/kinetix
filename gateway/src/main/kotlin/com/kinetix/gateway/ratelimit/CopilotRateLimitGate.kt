package com.kinetix.gateway.ratelimit

import com.kinetix.gateway.auth.JwtUserPrincipal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Route-level gate that applies a [CopilotRateLimiter] to a Copilot route
 * handler (AI-v2 PR 10.2).
 *
 * Identity resolution mirrors the JWT→header bridge ([com.kinetix.gateway.auth.JwtToHeaderBridge]):
 * the rate-limit bucket is keyed by the JWT `sub` claim. `sub` is read from the
 * authenticated [JwtUserPrincipal] when one is present; when the request has
 * not been through the JWT auth layer (auth-disabled smoke mode, or the
 * acceptance-test harness), the already-bridged `X-User-Id` request header is
 * used as the fallback key. A request that carries neither is keyed by remote
 * address so it still consumes a bounded allowance rather than bypassing the
 * limiter entirely.
 *
 * When the user is within their allowance the [handler] runs normally; on
 * breach the gate responds `429 Too Many Requests` with a `Retry-After` header
 * and a `rate_limited` error body, and [handler] is never invoked — so a
 * rejected request never reaches the upstream `ai-insights-service`.
 */
suspend fun ApplicationCall.copilotRateLimited(
    rateLimiter: CopilotRateLimiter,
    handler: suspend ApplicationCall.() -> Unit,
) {
    val userKey = copilotUserKey()
    if (rateLimiter.tryAcquire(userKey)) {
        handler()
    } else {
        response.header(HttpHeaders.RetryAfter, CopilotRateLimiter.RETRY_AFTER_SECONDS.toString())
        respond(
            HttpStatusCode.TooManyRequests,
            mapOf(
                "error" to "rate_limited",
                "message" to "Copilot request rate limit exceeded — try again shortly.",
            ),
        )
    }
}

/**
 * Resolves the rate-limit key for the calling user: JWT `sub` (preferred),
 * then the bridged `X-User-Id` header, then the remote address.
 */
private fun ApplicationCall.copilotUserKey(): String =
    principal<JwtUserPrincipal>()?.user?.userId
        ?: request.headers["X-User-Id"]?.takeIf { it.isNotBlank() }
        ?: request.local.remoteAddress
