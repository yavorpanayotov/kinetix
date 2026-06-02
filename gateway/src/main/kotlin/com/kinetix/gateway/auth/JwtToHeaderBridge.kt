package com.kinetix.gateway.auth

import com.auth0.jwt.interfaces.Payload
import com.kinetix.common.security.UserPrincipal
import io.ktor.client.request.HttpRequestBuilder

/**
 * Bridges the gateway's JWT principal model into the user-context
 * headers that downstream Kinetix services (notably
 * `ai-insights-service`) consume:
 *
 * * `X-User-Id` — the JWT `sub` claim, surfaced via [UserPrincipal.userId].
 * * `X-User-Books` — comma-separated book IDs the user is scoped to.
 *   Sourced from an explicit JWT `books` claim when present
 *   (forward-compat path) or from [BookAccessService] (the v2 source of
 *   truth). Wildcard scope is rendered as `"*"`.
 *
 * This is the AI-v2 PR 4.3 implementation; see `docs/plans/ai-v2.md`.
 */
object JwtToHeaderBridge {

    const val USER_ID_HEADER: String = "X-User-Id"
    const val USER_BOOKS_HEADER: String = "X-User-Books"
    const val WILDCARD_BOOKS: String = "*"

    /**
     * Compute the user-headers map for a fully-validated principal.
     *
     * [jwtBooksClaim] is read first (extension point for JWTs that
     * embed scopes directly). If null or empty, the bridge falls
     * back to [BookAccessService.booksFor], rendering `null`
     * (wildcard access) as [WILDCARD_BOOKS].
     */
    fun headersFor(
        principal: UserPrincipal,
        bookAccessService: BookAccessService,
        jwtBooksClaim: List<String>? = null,
    ): Map<String, String> {
        val books: String = if (!jwtBooksClaim.isNullOrEmpty()) {
            jwtBooksClaim.sorted().joinToString(",")
        } else {
            bookAccessService.booksFor(principal)
                ?.sorted()
                ?.joinToString(",")
                ?: WILDCARD_BOOKS
        }
        return mapOf(
            USER_ID_HEADER to principal.userId,
            USER_BOOKS_HEADER to books,
        )
    }

    /**
     * Convenience helper for extracting the optional `books` claim
     * out of a raw [com.auth0.jwt.interfaces.Payload]. Returns the
     * list of strings when the claim is present and is a JSON array;
     * `null` otherwise (absent, JSON null, or wrong shape).
     */
    fun extractBooksClaim(payload: Payload): List<String>? {
        val claim = payload.getClaim("books") ?: return null
        if (claim.isNull) return null
        return runCatching { claim.asList(String::class.java) }.getOrNull()
    }

    /**
     * Apply the headers to an outgoing [HttpRequestBuilder].
     *
     * Idempotent: calling twice with the same inputs leaves each
     * header set to exactly the latest value (no duplicate values).
     * The underlying Ktor `header(name, value)` extension calls
     * `headers.append`, which would otherwise accumulate duplicates
     * on repeated invocation — so this helper explicitly removes the
     * existing entry before appending.
     */
    fun applyTo(builder: HttpRequestBuilder, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            builder.headers.remove(name)
            builder.headers.append(name, value)
        }
    }
}
