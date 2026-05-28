package com.kinetix.gateway.middleware

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Internal service-to-service calls behind the gateway carry an
 * HMAC-SHA256 signature over the request body so downstream services
 * can verify the call originated from the gateway and not from a
 * compromised co-resident pod. The validator pins down the contract:
 * a correct signature accepts; a tampered body or a wrong key rejects.
 */
class RequestSigningHmacValidationTest : FunSpec({

    val key = "shared-secret-please-rotate-2026Q2"
    val body = """{"trade":"42","qty":100}"""

    test("a correctly-signed body validates") {
        val signature = signHmacSha256(body, key)
        verifyHmacSha256(body, signature, key) shouldBe true
    }

    test("a tampered body rejects under the original signature") {
        val signature = signHmacSha256(body, key)
        verifyHmacSha256("""{"trade":"42","qty":1000}""", signature, key) shouldBe false
    }

    test("a wrong key rejects") {
        val signature = signHmacSha256(body, key)
        verifyHmacSha256(body, signature, "different-secret") shouldBe false
    }

    test("a malformed signature rejects without crashing") {
        verifyHmacSha256(body, "not-a-hex-string", key) shouldBe false
    }

    test("an empty signature rejects") {
        verifyHmacSha256(body, "", key) shouldBe false
    }

    test("signature is deterministic for the same input") {
        signHmacSha256(body, key) shouldBe signHmacSha256(body, key)
    }

    test("signature changes when the body changes") {
        val a = signHmacSha256(body, key)
        val b = signHmacSha256("$body ", key)
        (a == b) shouldBe false
    }
})
