package com.kinetix.gateway.auth

import com.kinetix.common.security.Role
import com.kinetix.common.security.UserPrincipal

/**
 * Determines whether an authenticated user may access a specific book.
 *
 * RISK_MANAGER, COMPLIANCE, ADMIN, and VIEWER roles have unrestricted read access to all books.
 * TRADER role is restricted to books explicitly assigned to their userId.
 */
interface BookAccessService {
    fun canAccess(principal: UserPrincipal, bookId: String): Boolean

    /**
     * Returns the set of books the principal is explicitly scoped to.
     *
     * Returns `null` when the principal has wildcard access (e.g.
     * RISK_MANAGER / COMPLIANCE / ADMIN / VIEWER roles). Callers should
     * interpret `null` as "all books" and may stamp the wildcard sentinel
     * `*` on downstream user-headers (see [JwtToHeaderBridge]).
     */
    fun booksFor(principal: UserPrincipal): Set<String>?
}

/**
 * In-memory implementation suitable for development and testing.
 * Production deployments should back this with a persistent store.
 *
 * @param traderBooks maps each trader's userId to the set of bookIds they own.
 */
class InMemoryBookAccessService(
    private val traderBooks: Map<String, Set<String>> = emptyMap(),
) : BookAccessService {

    private val unrestricted = setOf(Role.ADMIN, Role.RISK_MANAGER, Role.COMPLIANCE, Role.VIEWER)

    override fun canAccess(principal: UserPrincipal, bookId: String): Boolean {
        if (principal.roles.any { it in unrestricted }) return true
        return traderBooks[principal.userId]?.contains(bookId) == true
    }

    override fun booksFor(principal: UserPrincipal): Set<String>? {
        if (principal.roles.any { it in unrestricted }) return null
        return traderBooks[principal.userId] ?: emptySet()
    }
}
