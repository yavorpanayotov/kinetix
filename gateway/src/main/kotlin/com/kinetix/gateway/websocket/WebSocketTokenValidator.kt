package com.kinetix.gateway.websocket

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.kinetix.common.security.Role
import com.kinetix.common.security.UserPrincipal
import com.kinetix.gateway.auth.JwtConfig
import io.ktor.server.application.*
import io.ktor.websocket.*
import java.security.interfaces.RSAPublicKey

/**
 * Validates the JWT token passed as the `?token=` query parameter on a WebSocket
 * upgrade request. Returns the decoded [UserPrincipal] on success, or null if the
 * token is absent, expired, or has an invalid signature.
 */
fun ApplicationCall.validateWebSocketToken(config: JwtConfig, jwkProvider: JwkProvider): UserPrincipal? {
    val rawToken = request.queryParameters["token"] ?: return null
    return try {
        val decoded = JWT.decode(rawToken)
        val kid = decoded.keyId ?: return null
        val jwk = jwkProvider.get(kid)
        val publicKey = jwk.publicKey as? RSAPublicKey ?: return null
        val verifier = JWT.require(Algorithm.RSA256(publicKey, null))
            .withAudience(config.audience)
            .withIssuer(config.issuer)
            .acceptLeeway(3)
            .build()
        val verified = verifier.verify(rawToken)
        if (!verified.audience.contains(config.audience)) return null
        val userId = verified.subject ?: return null
        val username = verified.getClaim("preferred_username")?.asString() ?: userId
        val rolesClaim = verified.getClaim("roles")?.asList(String::class.java) ?: emptyList()
        val roles = rolesClaim.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
        UserPrincipal(userId, username, roles)
    } catch (_: JWTVerificationException) {
        null
    } catch (_: com.auth0.jwk.SigningKeyNotFoundException) {
        null
    } catch (_: com.auth0.jwk.RateLimitReachedException) {
        null
    }
}

/**
 * Extracts the book scope from the `?token=` JWT on a WebSocket upgrade request.
 *
 * Reads the optional `books` claim — the same claim
 * [com.kinetix.gateway.auth.JwtToHeaderBridge] surfaces as the `X-User-Books`
 * downstream header. Returns:
 *
 *  - the set of book IDs when the claim is present as a JSON string array;
 *  - `null` when the claim is absent, JSON null, or not an array — denoting
 *    *wildcard* scope (the user is not book-restricted).
 *
 * This does **not** re-verify the token: call [validateWebSocketToken] first to
 * confirm the token is authentic, then this to read its scope.
 */
fun ApplicationCall.webSocketTokenBooks(): Set<String>? {
    val rawToken = request.queryParameters["token"] ?: return null
    return try {
        val decoded = JWT.decode(rawToken)
        val claim = decoded.getClaim("books")
        if (claim.isNull) return null
        runCatching { claim.asList(String::class.java) }.getOrNull()?.toSet()
    } catch (_: Exception) {
        null
    }
}

/** Close code sent when a WebSocket connection is rejected due to auth failure. */
val WEBSOCKET_UNAUTHORIZED_CLOSE = CloseReason(4001, "Unauthorized")
