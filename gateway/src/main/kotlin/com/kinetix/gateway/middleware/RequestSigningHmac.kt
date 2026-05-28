package com.kinetix.gateway.middleware

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM = "HmacSHA256"

/**
 * Compute the lowercase-hex HMAC-SHA256 of [body] under [key].
 *
 * The gateway signs internal service-to-service requests with a
 * shared HMAC so downstream services can verify the call originated
 * from the gateway and reject impostor calls from a compromised
 * co-resident pod. Hex is preferred over base64 here so signatures
 * survive un-escaped logging.
 */
fun signHmacSha256(body: String, key: String): String {
    val mac = Mac.getInstance(ALGORITHM)
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM))
    val bytes = mac.doFinal(body.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Constant-time HMAC verification: recomputes the expected signature
 * over [body]+[key] and compares it with the supplied [signature].
 * Returns `false` for malformed or empty signatures without throwing.
 */
fun verifyHmacSha256(body: String, signature: String, key: String): Boolean {
    if (signature.isEmpty()) return false
    val expected = try {
        signHmacSha256(body, key)
    } catch (_: Exception) {
        return false
    }
    if (expected.length != signature.length) return false
    var diff = 0
    for (i in expected.indices) {
        diff = diff or (expected[i].code xor signature[i].code)
    }
    return diff == 0
}
