package com.kinetix.gateway.auth

import com.kinetix.common.audit.AuditEventType
import com.kinetix.common.audit.GovernanceAuditEvent
import com.kinetix.common.security.Permission
import com.kinetix.gateway.audit.GovernanceAuditPublisher
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.MDC

fun Route.requirePermission(
    permission: Permission,
    auditPublisher: GovernanceAuditPublisher? = null,
    authEnabled: Boolean = true,
    build: Route.() -> Unit,
): Route {
    val route = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
            RouteSelectorEvaluation.Transparent
    })
    if (authEnabled) {
        route.install(
            createRouteScopedPlugin("PermissionCheck_${permission.name}") {
                on(AuthenticationChecked) { call ->
                    val principal = call.principal<JwtUserPrincipal>()
                    if (principal != null && !principal.user.hasPermission(permission)) {
                        auditPublisher?.publish(
                            GovernanceAuditEvent(
                                eventType = AuditEventType.RBAC_ACCESS_DENIED,
                                userId = principal.user.userId,
                                userRole = principal.user.roles.joinToString(",") { it.name },
                                details = "Denied permission ${permission.name} for ${call.request.local.uri}",
                                correlationId = MDC.get("correlationId"),
                            )
                        )
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden", "message" to "Insufficient permissions"))
                    }
                }
            },
        )
    }
    route.build()
    return route
}

/**
 * Verifies that the authenticated user has access to the given book.
 * Returns false (and responds 403) if the check fails so callers can return early.
 */
suspend fun ApplicationCall.checkBookAccess(bookId: String, service: BookAccessService): Boolean {
    val principal = principal<JwtUserPrincipal>() ?: return true // unauthenticated calls are handled by the auth layer
    return if (service.canAccess(principal.user, bookId)) {
        true
    } else {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden", "message" to "Access to book $bookId is not permitted"))
        false
    }
}

/**
 * Verifies that the authenticated user has access to all given books.
 * Denies the entire request if any bookId is not permitted, naming the denied book(s).
 * Returns false (and responds 403/400) if the check fails so callers can return early.
 */
suspend fun ApplicationCall.checkMultiBookAccess(bookIds: List<String>, service: BookAccessService): Boolean {
    if (bookIds.isEmpty()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "bookIds must not be empty"))
        return false
    }
    val principal = principal<JwtUserPrincipal>() ?: return true
    val denied = bookIds.filter { !service.canAccess(principal.user, it) }
    return if (denied.isEmpty()) {
        true
    } else {
        respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "forbidden", "message" to "Access denied for book(s): ${denied.joinToString(", ")}"),
        )
        false
    }
}
