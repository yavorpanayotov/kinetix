package com.kinetix.gateway.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.kinetix.common.security.Role
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

object TestJwtHelper {

    private const val TEST_ISSUER = "kinetix-test"
    private const val TEST_AUDIENCE = "kinetix-api"
    private const val TEST_KEY_ID = "test-key-1"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey
    private val algorithm = Algorithm.RSA256(publicKey, privateKey)

    private val wrongKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val wrongPrivateKey = wrongKeyPair.private as RSAPrivateKey
    private val wrongPublicKey = wrongKeyPair.public as RSAPublicKey
    private val wrongAlgorithm = Algorithm.RSA256(wrongPublicKey, wrongPrivateKey)

    fun testJwtConfig(): JwtConfig = JwtConfig(
        issuer = TEST_ISSUER,
        audience = TEST_AUDIENCE,
        realm = "kinetix-test",
    )

    fun testJwkProvider(): JwkProvider = object : JwkProvider {
        override fun get(keyId: String): Jwk {
            if (keyId != TEST_KEY_ID) {
                throw com.auth0.jwk.SigningKeyNotFoundException("Key not found: $keyId", null)
            }
            val values = mapOf(
                "kty" to "RSA",
                "kid" to TEST_KEY_ID,
                "use" to "sig",
                "alg" to "RS256",
                "n" to java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(publicKey.modulus.toByteArray()),
                "e" to java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(publicKey.publicExponent.toByteArray()),
            )
            return Jwk.fromValues(values)
        }
    }

    fun generateToken(
        userId: String = "user-1",
        username: String = "testuser",
        roles: List<Role> = listOf(Role.ADMIN),
        expiresInSeconds: Long = 3600,
        books: List<String>? = null,
    ): String = JWT.create()
        .withKeyId(TEST_KEY_ID)
        .withSubject(userId)
        .withAudience(TEST_AUDIENCE)
        .withIssuer(TEST_ISSUER)
        .withClaim("preferred_username", username)
        .withClaim("roles", roles.map { it.name })
        .apply { if (books != null) withClaim("books", books) }
        .withExpiresAt(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
        .sign(algorithm)

    fun generateExpiredToken(
        userId: String = "user-1",
        username: String = "testuser",
        roles: List<Role> = listOf(Role.ADMIN),
    ): String = JWT.create()
        .withKeyId(TEST_KEY_ID)
        .withSubject(userId)
        .withAudience(TEST_AUDIENCE)
        .withIssuer(TEST_ISSUER)
        .withClaim("preferred_username", username)
        .withClaim("roles", roles.map { it.name })
        .withExpiresAt(Date(System.currentTimeMillis() - 10_000))
        .sign(algorithm)

    fun generateTokenWithWrongSignature(
        userId: String = "user-1",
        username: String = "testuser",
        roles: List<Role> = listOf(Role.ADMIN),
    ): String = JWT.create()
        .withKeyId(TEST_KEY_ID)
        .withSubject(userId)
        .withAudience(TEST_AUDIENCE)
        .withIssuer(TEST_ISSUER)
        .withClaim("preferred_username", username)
        .withClaim("roles", roles.map { it.name })
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
        .sign(wrongAlgorithm)

    fun generateTokenWithUnknownKid(
        userId: String = "user-1",
        roles: List<Role> = listOf(Role.ADMIN),
    ): String = JWT.create()
        .withKeyId("unknown-key-99")
        .withSubject(userId)
        .withAudience(TEST_AUDIENCE)
        .withIssuer(TEST_ISSUER)
        .withClaim("preferred_username", "testuser")
        .withClaim("roles", roles.map { it.name })
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
        .sign(algorithm)

    fun generateTokenWithWrongIssuer(
        roles: List<Role> = listOf(Role.ADMIN),
    ): String = JWT.create()
        .withKeyId(TEST_KEY_ID)
        .withSubject("user-1")
        .withAudience(TEST_AUDIENCE)
        .withIssuer("https://attacker.com/realms/kinetix")
        .withClaim("preferred_username", "testuser")
        .withClaim("roles", roles.map { it.name })
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
        .sign(algorithm)

    fun generateTokenWithWrongAudience(
        roles: List<Role> = listOf(Role.ADMIN),
    ): String = JWT.create()
        .withKeyId(TEST_KEY_ID)
        .withSubject("user-1")
        .withAudience("other-client")
        .withIssuer(TEST_ISSUER)
        .withClaim("preferred_username", "testuser")
        .withClaim("roles", roles.map { it.name })
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
        .sign(algorithm)

    fun generateTokenWithoutRolesClaim(
        userId: String = "user-1",
    ): String = JWT.create()
        .withKeyId(TEST_KEY_ID)
        .withSubject(userId)
        .withAudience(TEST_AUDIENCE)
        .withIssuer(TEST_ISSUER)
        .withClaim("preferred_username", "testuser")
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
        .sign(algorithm)
}
