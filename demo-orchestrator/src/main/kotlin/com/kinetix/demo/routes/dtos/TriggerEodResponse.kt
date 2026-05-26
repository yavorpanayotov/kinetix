package com.kinetix.demo.routes.dtos

import kotlinx.serialization.Serializable

/**
 * Response payload for `POST /demo/trigger-eod`.
 *
 * The endpoint is fire-and-forget: the HTTP response is sent immediately
 * with [dispatched]=`true` once the [com.kinetix.demo.schedule.EodPromotionJob]
 * coroutine has been launched. Actual promotion success/failure flows
 * downstream via the `risk.official-eod` Kafka topic — callers wanting
 * end-state confirmation must observe that stream, not this response.
 *
 * @property correlationId UUIDv4 identifying this dispatch, included in
 *     log lines emitted by the dispatched job so operators can join the
 *     HTTP request with the resulting promotion sweep.
 * @property dispatched always `true` for a 202 response. Reserved as a
 *     field so future surface variants (e.g. quiesce, rate-limit) can
 *     return `false` without breaking the schema.
 * @property bookCount number of books the [com.kinetix.demo.schedule.EodPromotionJob]
 *     will iterate over on this dispatch (matches `DemoBookProfiles.all().size`
 *     by default — eight in the canonical demo seed).
 */
@Serializable
data class TriggerEodResponse(
    val correlationId: String,
    val dispatched: Boolean,
    val bookCount: Int,
)
