package com.kinetix.audit.model

/**
 * Validate an [AuditEvent.userId]: non-empty, ASCII-alphanumeric (plus the
 * underscore and hyphen separators commonly emitted by SAML/OIDC providers
 * and Kafka service accounts), and within the 255-character column width
 * of the underlying Postgres `user_id` field.
 *
 * Governance events (model approvals, scenario edits) have no human
 * principal and pass through `null`. Trade events should always carry a
 * principal — the validator rejects empty strings so the audit trail can
 * never be filed against an unknown actor.
 *
 * @throws IllegalArgumentException if [userId] is non-null but malformed.
 */
fun validateAuditUserId(userId: String?): String? {
    if (userId == null) return null
    require(userId.isNotEmpty()) { "user_id must not be empty" }
    require(userId.isNotBlank()) { "user_id must not be whitespace-only" }
    require(userId.length <= 255) {
        "user_id (${userId.length} chars) exceeds the 255-character column width"
    }
    require(USER_ID_PATTERN.matches(userId)) {
        "user_id '$userId' contains characters outside [A-Za-z0-9_-]"
    }
    return userId
}

private val USER_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
