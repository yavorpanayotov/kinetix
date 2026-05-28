package com.kinetix.audit.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The `user_id` field on an [AuditEvent] is the only thread tying a trade
 * action back to the human (or service account) that initiated it. The
 * regulatory trail is worthless if a malformed identifier slips through —
 * "" is impossible to prosecute, "alice; DROP TABLE" is a SQL-injection
 * smell, and identifiers containing whitespace or punctuation are an
 * established way to silently break downstream user-attribution queries.
 *
 * This test pins the contract on the validator: non-null user IDs must be
 * non-empty, must contain only ASCII alphanumeric characters plus the
 * underscore and hyphen separators that authentication providers commonly
 * use, and must not exceed the 255-character column width of the underlying
 * Postgres `user_id` field.
 */
class AuditEventUserIdValidationTest : FunSpec({

    test("accepts a typical lowercase alphanumeric user id") {
        validateAuditUserId("alice42") shouldBe "alice42"
    }

    test("accepts an empty Optional-like null (governance events have no user)") {
        validateAuditUserId(null) shouldBe null
    }

    test("accepts mixed-case identifiers") {
        validateAuditUserId("Alice42") shouldBe "Alice42"
    }

    test("accepts hyphenated identifiers (auth providers commonly emit these)") {
        validateAuditUserId("alice-ops") shouldBe "alice-ops"
    }

    test("accepts underscore-separated identifiers (service accounts)") {
        validateAuditUserId("svc_risk_runner") shouldBe "svc_risk_runner"
    }

    test("rejects an empty string user id") {
        val ex = shouldThrow<IllegalArgumentException> { validateAuditUserId("") }
        ex.message shouldContain "user_id"
        ex.message shouldContain "empty"
    }

    test("rejects a whitespace-only user id") {
        shouldThrow<IllegalArgumentException> { validateAuditUserId("   ") }
    }

    test("rejects a user id with embedded whitespace") {
        val ex = shouldThrow<IllegalArgumentException> { validateAuditUserId("alice 42") }
        ex.message shouldContain "user_id"
    }

    test("rejects a user id with SQL-injection punctuation") {
        shouldThrow<IllegalArgumentException> { validateAuditUserId("alice;DROP") }
        shouldThrow<IllegalArgumentException> { validateAuditUserId("alice'OR'1") }
    }

    test("rejects a user id with non-ASCII characters (column is varchar 255 ASCII-canonical)") {
        shouldThrow<IllegalArgumentException> { validateAuditUserId("alïce") }
    }

    test("rejects a user id over the 255-character column width") {
        val tooLong = "a".repeat(256)
        val ex = shouldThrow<IllegalArgumentException> { validateAuditUserId(tooLong) }
        ex.message shouldContain "255"
    }

    test("accepts a user id at exactly the 255-character column width") {
        val maxLen = "a".repeat(255)
        validateAuditUserId(maxLen) shouldBe maxLen
    }
})
