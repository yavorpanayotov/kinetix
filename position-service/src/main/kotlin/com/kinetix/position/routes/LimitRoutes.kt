package com.kinetix.position.routes

import com.kinetix.position.model.LimitDefinition
import com.kinetix.position.model.LimitLevel
import com.kinetix.position.model.LimitType
import com.kinetix.position.model.TemporaryLimitIncrease
import com.kinetix.position.persistence.LimitDefinitionRepository
import com.kinetix.position.persistence.TemporaryLimitIncreaseRepository
import com.kinetix.position.service.LimitUsageProvider
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@Serializable
data class LimitDefinitionResponse(
    val id: String,
    val level: String,
    val entityId: String,
    val limitType: String,
    val limitValue: String,
    val intradayLimit: String?,
    val overnightLimit: String?,
    val active: Boolean,
    // Trader-review P0: surface "how close to the wall" alongside the
    // ceiling. `current` is the consumed value (in the same unit as
    // `limitValue` — dollars for NOTIONAL/VAR, share count for POSITION,
    // ratio for CONCENTRATION). `utilisationPct` is `current /
    // effectiveLimit * 100`, rounded to two decimals. Both are nullable:
    // position-service can compute NOTIONAL and POSITION utilisation from
    // the position book but not VAR/CONCENTRATION (those need risk-engine
    // output), and TRADER/DIVISION/COUNTERPARTY scopes have no position-
    // level attribution. Nullable lets the UI render em-dash rather than
    // a misleading $0 figure for those rows.
    val current: String? = null,
    val utilisationPct: Double? = null,
)

@Serializable
data class CreateLimitRequest(
    val level: String,
    val entityId: String,
    val limitType: String,
    val limitValue: String,
    val intradayLimit: String? = null,
    val overnightLimit: String? = null,
)

@Serializable
data class UpdateLimitRequest(
    val limitValue: String,
    val intradayLimit: String? = null,
    val overnightLimit: String? = null,
    val active: Boolean? = null,
)

@Serializable
data class TemporaryIncreaseRequest(
    val newValue: String,
    val approvedBy: String,
    val expiresAt: String,
    val reason: String,
)

@Serializable
data class TemporaryIncreaseResponse(
    val id: String,
    val limitId: String,
    val newValue: String,
    val approvedBy: String,
    val expiresAt: String,
    val reason: String,
    val createdAt: String,
)

private fun LimitDefinition.toResponse(
    current: BigDecimal? = null,
    utilisationPct: Double? = null,
) = LimitDefinitionResponse(
    id = id,
    level = level.name,
    entityId = entityId,
    limitType = limitType.name,
    limitValue = limitValue.toPlainString(),
    intradayLimit = intradayLimit?.toPlainString(),
    overnightLimit = overnightLimit?.toPlainString(),
    active = active,
    current = current?.normalisePlain(),
    utilisationPct = utilisationPct,
)

/**
 * Render a BigDecimal without trailing zeros so `640000000.000000000000`
 * (BigDecimal arithmetic over decimal(28,12) position rows) doesn't leak
 * into the wire shape as a noisy "$640,000,000.000000000000" cell. We
 * keep the integer form when there's no fractional component, which is
 * the common case for NOTIONAL (`marketPrice * quantity`) and POSITION
 * (`|quantity|`) over share-count instruments.
 */
private fun BigDecimal.normalisePlain(): String {
    val stripped = stripTrailingZeros()
    // stripTrailingZeros() on a zero value returns 0E-N — coerce back to "0".
    if (stripped.signum() == 0) return "0"
    // For integers, drop the exponent (e.g. 6.4E+8 → 640000000).
    return if (stripped.scale() <= 0) stripped.toBigInteger().toString() else stripped.toPlainString()
}

/**
 * Choose the denominator for utilisation. Caller passes the limit row; we
 * prefer the intraday ceiling (the screen the trader is looking at is
 * intraday by default), falling back to overnight, then to the headline
 * [LimitDefinition.limitValue]. A zero denominator returns `null` so the
 * UI shows em-dash rather than NaN/∞ for a misconfigured row.
 */
private fun LimitDefinition.utilisationDenominator(): BigDecimal? {
    val denominator = intradayLimit ?: overnightLimit ?: limitValue
    return if (denominator.signum() == 0) null else denominator
}

private fun utilisationPct(current: BigDecimal?, denominator: BigDecimal?): Double? {
    if (current == null || denominator == null) return null
    val ratio = current.divide(denominator, MathContext.DECIMAL64)
        .multiply(BigDecimal(100))
        .setScale(2, RoundingMode.HALF_UP)
    return ratio.toDouble()
}

private fun TemporaryLimitIncrease.toResponse() = TemporaryIncreaseResponse(
    id = id,
    limitId = limitId,
    newValue = newValue.toPlainString(),
    approvedBy = approvedBy,
    expiresAt = expiresAt.toString(),
    reason = reason,
    createdAt = createdAt.toString(),
)

fun Route.limitRoutes(
    limitDefinitionRepo: LimitDefinitionRepository,
    temporaryLimitIncreaseRepo: TemporaryLimitIncreaseRepository,
    // Optional provider — when null, `current` and `utilisationPct` fall
    // through as nulls in the response. Lets existing tests wire the route
    // without a position store, while production keeps utilisation populated
    // through PositionBasedLimitUsageProvider.
    limitUsageProvider: LimitUsageProvider? = null,
) {
    route("/api/v1/limits") {

        get({
            summary = "List all limit definitions"
            tags = listOf("Limits")
            response {
                code(HttpStatusCode.OK) { body<List<LimitDefinitionResponse>>() }
            }
        }) {
            val limits = limitDefinitionRepo.findAll()
            val responses = limits.map { limit ->
                val current = limitUsageProvider?.currentUsage(limit)
                val pct = utilisationPct(current, limit.utilisationDenominator())
                limit.toResponse(current = current, utilisationPct = pct)
            }
            call.respond(responses)
        }

        post({
            summary = "Create a limit definition"
            tags = listOf("Limits")
            request {
                body<CreateLimitRequest>()
            }
            response {
                code(HttpStatusCode.Created) { body<LimitDefinitionResponse>() }
                code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
            }
        }) {
            val request = call.receive<CreateLimitRequest>()
            val limitDefinition = LimitDefinition(
                id = UUID.randomUUID().toString(),
                level = LimitLevel.valueOf(request.level),
                entityId = request.entityId,
                limitType = LimitType.valueOf(request.limitType),
                limitValue = BigDecimal(request.limitValue),
                intradayLimit = request.intradayLimit?.let { BigDecimal(it) },
                overnightLimit = request.overnightLimit?.let { BigDecimal(it) },
                active = true,
            )
            limitDefinitionRepo.save(limitDefinition)
            call.respond(HttpStatusCode.Created, limitDefinition.toResponse())
        }

        route("/{id}") {

            put({
                summary = "Update a limit definition"
                tags = listOf("Limits")
                request {
                    pathParameter<String>("id") { description = "Limit definition ID" }
                    body<UpdateLimitRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<LimitDefinitionResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                }
            }) {
                val id = call.requirePathParam("id")
                val existing = limitDefinitionRepo.findById(id)
                    ?: return@put call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("not_found", "Limit definition not found"),
                    )

                val request = call.receive<UpdateLimitRequest>()
                val updated = existing.copy(
                    limitValue = BigDecimal(request.limitValue),
                    intradayLimit = request.intradayLimit?.let { BigDecimal(it) } ?: existing.intradayLimit,
                    overnightLimit = request.overnightLimit?.let { BigDecimal(it) } ?: existing.overnightLimit,
                    active = request.active ?: existing.active,
                )
                limitDefinitionRepo.update(updated)
                call.respond(updated.toResponse())
            }

            post("/temporary-increase", {
                summary = "Request a temporary limit increase"
                tags = listOf("Limits")
                request {
                    pathParameter<String>("id") { description = "Limit definition ID" }
                    body<TemporaryIncreaseRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<TemporaryIncreaseResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                }
            }) {
                val limitId = call.requirePathParam("id")
                val existing = limitDefinitionRepo.findById(limitId)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("not_found", "Limit definition not found"),
                    )

                val request = call.receive<TemporaryIncreaseRequest>()
                val tempIncrease = TemporaryLimitIncrease(
                    id = UUID.randomUUID().toString(),
                    limitId = limitId,
                    newValue = BigDecimal(request.newValue),
                    approvedBy = request.approvedBy,
                    expiresAt = Instant.parse(request.expiresAt),
                    reason = request.reason,
                    createdAt = Instant.now(),
                )
                temporaryLimitIncreaseRepo.save(tempIncrease)
                call.respond(HttpStatusCode.Created, tempIncrease.toResponse())
            }
        }
    }
}
