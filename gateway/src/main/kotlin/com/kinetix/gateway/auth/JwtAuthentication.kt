package com.kinetix.gateway.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.kinetix.common.security.Role
import com.kinetix.common.security.UserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import java.net.URI
import java.util.concurrent.TimeUnit

data class JwtUserPrincipal(val user: UserPrincipal) : Principal

fun Application.configureJwtAuth(config: JwtConfig, jwkProvider: JwkProvider? = null): JwkProvider {
    val provider = jwkProvider ?: config.jwksUrl?.let { url ->
        JwkProviderBuilder(URI(url).toURL())
            .cached(10, 10, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()
    } ?: throw IllegalStateException("JwtConfig must have either a jwksUrl or a JwkProvider must be supplied")

    install(Authentication) {
        jwt("auth-jwt") {
            realm = config.realm
            verifier(provider, config.issuer) {
                acceptLeeway(3)
                withAudience(config.audience)
            }
            validate { credential ->
                if (credential.payload.audience.contains(config.audience)) {
                    val userId = credential.payload.subject ?: return@validate null
                    val username = credential.payload.getClaim("preferred_username")?.asString() ?: userId
                    val rolesClaim = credential.payload.getClaim("roles")?.asList(String::class.java) ?: emptyList()
                    val roles = rolesClaim.mapNotNull { roleName ->
                        runCatching { Role.valueOf(roleName) }.getOrNull()
                    }.toSet()
                    JwtUserPrincipal(UserPrincipal(userId, username, roles))
                } else {
                    null
                }
            }
            challenge { defaultScheme, realm ->
                call.response.header(HttpHeaders.WWWAuthenticate, "$defaultScheme realm=\"$realm\"")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized", "message" to "Token is not valid or has expired"))
            }
        }
    }
    return provider
}
