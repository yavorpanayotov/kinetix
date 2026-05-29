package com.kinetix.volatility.error

import com.kinetix.common.dtos.ApiError
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

private val log = LoggerFactory.getLogger("com.kinetix.volatility.error.StatusPages")

/**
 * Installs Ktor's [StatusPages] plugin with the canonical [ApiError] wire shape.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
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
