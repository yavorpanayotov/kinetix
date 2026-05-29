package com.kinetix.common.dtos

import kotlinx.serialization.Serializable

/**
 * Standard error envelope returned by all Kinetix HTTP APIs.
 *
 * - [code] — machine-readable error category (e.g. `UPSTREAM_ERROR`, `BAD_REQUEST`).
 * - [message] — human-readable description safe to show to API consumers.
 *   For generic server errors this must NOT expose internal implementation details
 *   (stack traces, SQL, class names); callers see a generic phrase such as
 *   "Internal server error".
 * - [correlationId] — the request-scoped correlation ID from MDC or the
 *   `X-Correlation-ID` header, allowing log aggregation across services.
 * - [details] — optional structured key/value pairs for richer client diagnostics
 *   (e.g. field-level validation failures).
 */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val correlationId: String? = null,
    val details: Map<String, String>? = null,
)
