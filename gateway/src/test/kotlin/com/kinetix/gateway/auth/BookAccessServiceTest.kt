package com.kinetix.gateway.auth

import com.kinetix.common.security.Role
import com.kinetix.common.security.UserPrincipal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookAccessServiceTest : FunSpec({

    val service = InMemoryBookAccessService(
        traderBooks = mapOf(
            "trader-1" to setOf("book-A", "book-B"),
            "trader-2" to setOf("book-C"),
        ),
    )

    fun principal(userId: String, vararg roles: Role): UserPrincipal =
        UserPrincipal(userId = userId, username = userId, roles = roles.toSet())

    test("TRADER can access their own assigned book") {
        service.canAccess(principal("trader-1", Role.TRADER), "book-A") shouldBe true
        service.canAccess(principal("trader-1", Role.TRADER), "book-B") shouldBe true
        service.canAccess(principal("trader-2", Role.TRADER), "book-C") shouldBe true
    }

    test("TRADER cannot access a book not assigned to them") {
        service.canAccess(principal("trader-1", Role.TRADER), "book-C") shouldBe false
        service.canAccess(principal("trader-2", Role.TRADER), "book-A") shouldBe false
    }

    test("TRADER with no assigned books cannot access any book") {
        service.canAccess(principal("trader-99", Role.TRADER), "book-A") shouldBe false
    }

    test("RISK_MANAGER can access any book") {
        service.canAccess(principal("rm-1", Role.RISK_MANAGER), "book-A") shouldBe true
        service.canAccess(principal("rm-1", Role.RISK_MANAGER), "book-C") shouldBe true
        service.canAccess(principal("rm-1", Role.RISK_MANAGER), "any-book-id") shouldBe true
    }

    test("COMPLIANCE can access any book") {
        service.canAccess(principal("comp-1", Role.COMPLIANCE), "book-A") shouldBe true
        service.canAccess(principal("comp-1", Role.COMPLIANCE), "book-C") shouldBe true
    }

    test("ADMIN can access any book") {
        service.canAccess(principal("admin-1", Role.ADMIN), "book-A") shouldBe true
        service.canAccess(principal("admin-1", Role.ADMIN), "unknown-book") shouldBe true
    }

    test("VIEWER can access any book (read-only access)") {
        service.canAccess(principal("viewer-1", Role.VIEWER), "book-A") shouldBe true
    }

    test("booksFor returns the explicit set for a TRADER with assigned books") {
        service.booksFor(principal("trader-1", Role.TRADER)) shouldBe setOf("book-A", "book-B")
        service.booksFor(principal("trader-2", Role.TRADER)) shouldBe setOf("book-C")
    }

    test("booksFor returns empty set for a TRADER with no assigned books") {
        service.booksFor(principal("trader-99", Role.TRADER)) shouldBe emptySet()
    }

    test("booksFor returns null (wildcard) for RISK_MANAGER") {
        service.booksFor(principal("rm-1", Role.RISK_MANAGER)) shouldBe null
    }

    test("booksFor returns null (wildcard) for COMPLIANCE") {
        service.booksFor(principal("comp-1", Role.COMPLIANCE)) shouldBe null
    }

    test("booksFor returns null (wildcard) for ADMIN") {
        service.booksFor(principal("admin-1", Role.ADMIN)) shouldBe null
    }

    test("booksFor returns null (wildcard) for VIEWER") {
        service.booksFor(principal("viewer-1", Role.VIEWER)) shouldBe null
    }
})
