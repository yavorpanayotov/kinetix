package com.kinetix.gateway.error

import com.kinetix.common.dtos.ApiError
import com.kinetix.gateway.client.GatewayTimeoutException
import com.kinetix.gateway.client.ServiceUnavailableException
import com.kinetix.gateway.client.UpstreamErrorException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

private val log = LoggerFactory.getLogger("com.kinetix.gateway.error.StatusPages")

/**
 * Installs Ktor's [StatusPages] plugin with explicit exception-to-HTTP-status
 * mappings that emit the canonical [ApiError] wire shape.
 *
 * ## Correlation ID resolution
 * Reads from the `X-Correlation-ID` request header first (the value the
 * [CallLogging] plugin already placed in MDC), then falls back to
 * `MDC.get("correlationId")`, then generates a fresh UUID.  This ensures the
 * error response and the corresponding log line always share the same ID.
 *
 * ## Message safety
 * For unhandled [Throwable] the cause message is intentionally suppressed —
 * it may contain SQL fragments, class names, or other internal details.
 * Callers see a static "Internal server error" string; the real cause is
 * logged at ERROR so on-call engineers can diagnose it from the observability
 * stack.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<UpstreamErrorException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ApiError(
                    code = "UPSTREAM_ERROR",
                    message = cause.message ?: "Upstream error",
                    correlationId = correlationId,
                ),
            )
        }
        exception<ServiceUnavailableException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            cause.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError(
                    code = "SERVICE_UNAVAILABLE",
                    message = cause.message ?: "Service unavailable",
                    correlationId = correlationId,
                ),
            )
        }
        exception<GatewayTimeoutException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ApiError(
                    code = "GATEWAY_TIMEOUT",
                    message = cause.message ?: "Gateway timeout",
                    correlationId = correlationId,
                ),
            )
        }
        // Client supplied a JSON body whose shape doesn't match the route's DTO
        // (wrong field type, missing required field, malformed JSON). Ktor's
        // content-negotiation surfaces these as JsonConvertException. Pass the
        // field-naming message through so API consumers can self-diagnose without
        // needing server logs. These are caller faults: do NOT log at ERROR.
        exception<io.ktor.serialization.JsonConvertException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    code = "INVALID_REQUEST_BODY",
                    message = cause.message ?: "Invalid JSON body",
                    correlationId = correlationId,
                ),
            )
        }
        exception<kotlinx.serialization.SerializationException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    code = "INVALID_REQUEST_BODY",
                    message = cause.message ?: "Invalid JSON body",
                    correlationId = correlationId,
                ),
            )
        }
        // call.receive<T>() wraps deserialisation failures in BadRequestException.
        // Walk the cause chain to surface the underlying serialisation message
        // and a richer error code instead of an opaque `BAD_REQUEST`.
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            val serializationCause = generateSequence<Throwable>(cause) { it.cause }
                .firstOrNull {
                    it is kotlinx.serialization.SerializationException ||
                        it is io.ktor.serialization.JsonConvertException
                }
            if (serializationCause != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        code = "INVALID_REQUEST_BODY",
                        message = serializationCause.message ?: cause.message ?: "Invalid JSON body",
                        correlationId = correlationId,
                    ),
                )
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        code = "BAD_REQUEST",
                        message = cause.message ?: "Bad request",
                        correlationId = correlationId,
                    ),
                )
            }
        }
        exception<IllegalArgumentException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    code = "BAD_REQUEST",
                    message = cause.message ?: "Invalid request",
                    correlationId = correlationId,
                ),
            )
        }
        // Transport-level failures: upstream unreachable (connection refused or
        // connect timeout). Return 503 with a Retry-After hint.
        exception<java.net.ConnectException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError(
                    code = "UPSTREAM_UNAVAILABLE",
                    message = cause.message ?: "Upstream connection refused",
                    correlationId = correlationId,
                ),
            )
        }
        // Ktor's HttpTimeout plugin fires this when the total request time exceeds
        // requestTimeoutMillis. Return 504 so callers can distinguish from a
        // gateway internal error.
        exception<io.ktor.client.plugins.HttpRequestTimeoutException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ApiError(
                    code = "UPSTREAM_TIMEOUT",
                    message = cause.message ?: "Upstream request timed out",
                    correlationId = correlationId,
                ),
            )
        }
        // Socket-level read/write timeout. Map to 504 for the same reason as above.
        exception<java.net.SocketTimeoutException> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            call.respond(
                HttpStatusCode.GatewayTimeout,
                ApiError(
                    code = "UPSTREAM_TIMEOUT",
                    message = cause.message ?: "Upstream socket timed out",
                    correlationId = correlationId,
                ),
            )
        }
        exception<Throwable> { call, cause ->
            val correlationId = resolveCorrelationId(call)
            log.error("Unhandled exception [correlationId=$correlationId]", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    code = "INTERNAL_ERROR",
                    // Do NOT include cause.message — it may expose SQL, class names,
                    // or other internal details. The log line above carries the full
                    // cause for the on-call engineer.
                    message = "Internal server error",
                    correlationId = correlationId,
                ),
            )
        }
    }
}

private fun resolveCorrelationId(call: ApplicationCall): String =
    call.request.header("X-Correlation-ID")
        ?: MDC.get("correlationId")
        ?: UUID.randomUUID().toString()
