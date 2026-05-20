package com.kinetix.gateway.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Intraday-push payload posted by `ai-insights-service` to the gateway's
 * cluster-internal route `POST /internal/copilot/push`.
 *
 * Mirrors the Python `IntradayPush` model
 * (`ai-insights-service/src/kinetix_insights/push/models.py`): a firing
 * intraday threshold composed into a sourced, dismissible alert. The gateway
 * does not own the intraday-push schema — it forwards the payload to the
 * `CopilotBroadcaster` for WebSocket fan-out (PR 7 / ADR-0036). `sources`
 * carries the provenance trail (a list of `Citation`-shaped objects) and is
 * kept as a raw `JsonElement` so downstream citation-schema evolution does
 * not require redeploying the gateway.
 *
 * The Python side serialises field names in snake_case; `@SerialName`
 * bridges to the gateway's idiomatic camelCase Kotlin properties.
 */
@Serializable
data class CopilotPushRequest(
    @SerialName("alert_type") val alertType: String,
    val severity: String,
    @SerialName("book_id") val bookId: String,
    val headline: String,
    @SerialName("context_bullets") val contextBullets: List<String> = emptyList(),
    val sources: List<JsonElement> = emptyList(),
    @SerialName("session_id") val sessionId: String,
    @SerialName("generated_at") val generatedAt: String,
)
