package com.kinetix.gateway.auth

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import com.kinetix.common.security.Role
import com.kinetix.common.security.UserPrincipal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.HttpRequestBuilder
import io.mockk.every
import io.mockk.mockk

/**
 * Unit tests for [JwtToHeaderBridge] — the AI-v2 PR 4.3 utility that
 * translates a Kinetix gateway JWT principal into the downstream
 * user-context headers (`X-User-Id`, `X-User-Books`) consumed by the
 * ai-insights-service. See `docs/plans/ai-v2.md` for the wider context.
 */
class JwtToHeaderBridgeTest : FunSpec({

    fun principal(userId: String, vararg roles: Role): UserPrincipal =
        UserPrincipal(userId = userId, username = userId, roles = roles.toSet())

    fun bookAccessService(traderBooks: Map<String, Set<String>>): BookAccessService =
        InMemoryBookAccessService(traderBooks = traderBooks)

    test("headersFor stamps X-User-Id from principal userId") {
        val bas = bookAccessService(mapOf("trader-1" to setOf("fx-main")))
        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-1", Role.TRADER),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_ID_HEADER] shouldBe "trader-1"
    }

    test("headersFor stamps X-User-Books from BookAccessService for TRADER") {
        val bas = bookAccessService(
            mapOf("trader-1" to setOf("fx-main", "rates-emea")),
        )

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-1", Role.TRADER),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe "fx-main,rates-emea"
    }

    test("headersFor stamps wildcard for RISK_MANAGER") {
        val bas = bookAccessService(emptyMap())

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("rm-1", Role.RISK_MANAGER),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe JwtToHeaderBridge.WILDCARD_BOOKS
    }

    test("headersFor stamps wildcard for COMPLIANCE") {
        val bas = bookAccessService(emptyMap())

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("comp-1", Role.COMPLIANCE),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe JwtToHeaderBridge.WILDCARD_BOOKS
    }

    test("headersFor stamps wildcard for ADMIN") {
        val bas = bookAccessService(emptyMap())

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("admin-1", Role.ADMIN),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe JwtToHeaderBridge.WILDCARD_BOOKS
    }

    test("headersFor stamps wildcard for VIEWER") {
        val bas = bookAccessService(emptyMap())

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("viewer-1", Role.VIEWER),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe JwtToHeaderBridge.WILDCARD_BOOKS
    }

    test("headersFor stamps empty string when TRADER has no assigned books") {
        val bas = bookAccessService(emptyMap())

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-orphan", Role.TRADER),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe ""
    }

    test("headersFor prefers explicit jwtBooksClaim over BookAccessService") {
        val bas = bookAccessService(
            mapOf("trader-1" to setOf("fx-main", "rates-emea")),
        )

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-1", Role.TRADER),
            bookAccessService = bas,
            jwtBooksClaim = listOf("beta", "alpha"),
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe "alpha,beta"
    }

    test("headersFor falls back to BookAccessService when jwtBooksClaim is empty list") {
        val bas = bookAccessService(
            mapOf("trader-1" to setOf("fx-main", "rates-emea")),
        )

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-1", Role.TRADER),
            bookAccessService = bas,
            jwtBooksClaim = emptyList(),
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe "fx-main,rates-emea"
    }

    test("headersFor falls back to BookAccessService when jwtBooksClaim is null") {
        val bas = bookAccessService(
            mapOf("trader-1" to setOf("fx-main", "rates-emea")),
        )

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-1", Role.TRADER),
            bookAccessService = bas,
            jwtBooksClaim = null,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe "fx-main,rates-emea"
    }

    test("headersFor sorts multiple books alphabetically for stable downstream comparison") {
        val bas = bookAccessService(
            mapOf("trader-1" to setOf("zulu", "alpha", "mike")),
        )

        val headers = JwtToHeaderBridge.headersFor(
            principal = principal("trader-1", Role.TRADER),
            bookAccessService = bas,
        )

        headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe "alpha,mike,zulu"
    }

    test("extractBooksClaim returns list when claim present as string array") {
        val payload = mockk<Payload>()
        val claim = mockk<Claim>()
        every { payload.getClaim("books") } returns claim
        every { claim.isNull } returns false
        every { claim.asList(String::class.java) } returns listOf("fx-main", "rates-emea")

        val result = JwtToHeaderBridge.extractBooksClaim(payload)

        result.shouldContainExactly("fx-main", "rates-emea")
    }

    test("extractBooksClaim returns null when claim absent") {
        val payload = mockk<Payload>()
        every { payload.getClaim("books") } returns null

        val result = JwtToHeaderBridge.extractBooksClaim(payload)

        result.shouldBeNull()
    }

    test("extractBooksClaim returns null when claim is JSON null") {
        val payload = mockk<Payload>()
        val claim = mockk<Claim>()
        every { payload.getClaim("books") } returns claim
        every { claim.isNull } returns true

        val result = JwtToHeaderBridge.extractBooksClaim(payload)

        result.shouldBeNull()
    }

    test("extractBooksClaim returns null on non-array claim shape") {
        val payload = mockk<Payload>()
        val claim = mockk<Claim>()
        every { payload.getClaim("books") } returns claim
        every { claim.isNull } returns false
        every { claim.asList(String::class.java) } throws RuntimeException("not an array")

        val result = JwtToHeaderBridge.extractBooksClaim(payload)

        result.shouldBeNull()
    }

    test("applyTo sets every header on the request builder") {
        val builder = HttpRequestBuilder()

        JwtToHeaderBridge.applyTo(
            builder,
            mapOf(
                JwtToHeaderBridge.USER_ID_HEADER to "t1",
                JwtToHeaderBridge.USER_BOOKS_HEADER to "fx",
            ),
        )

        builder.headers[JwtToHeaderBridge.USER_ID_HEADER] shouldBe "t1"
        builder.headers[JwtToHeaderBridge.USER_BOOKS_HEADER] shouldBe "fx"
    }

    test("applyTo is idempotent") {
        val builder = HttpRequestBuilder()
        val headers = mapOf(
            JwtToHeaderBridge.USER_ID_HEADER to "t1",
            JwtToHeaderBridge.USER_BOOKS_HEADER to "fx",
        )

        JwtToHeaderBridge.applyTo(builder, headers)
        JwtToHeaderBridge.applyTo(builder, headers)

        builder.headers.getAll(JwtToHeaderBridge.USER_ID_HEADER) shouldBe listOf("t1")
        builder.headers.getAll(JwtToHeaderBridge.USER_BOOKS_HEADER) shouldBe listOf("fx")
    }
})
