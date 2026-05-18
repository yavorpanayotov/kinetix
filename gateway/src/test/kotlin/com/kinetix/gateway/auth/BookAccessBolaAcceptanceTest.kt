package com.kinetix.gateway.auth

import com.kinetix.common.security.Role
import com.kinetix.gateway.client.HttpRiskServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.*

/**
 * Acceptance tests verifying that BOLA prevention is wired across all book-scoped
 * route families — not just positions/trades (covered by BookAccessAcceptanceTest).
 *
 * Each test exercises a representative route from a different permission group
 * to prove the requireBookAccess() plugin is wired to all route groups.
 */
class BookAccessBolaAcceptanceTest : FunSpec({

    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()

    val bookAccessService = InMemoryBookAccessService(
        traderBooks = mapOf("trader-1" to setOf("book-A"))
    )

    // Minimal VaR result JSON satisfying ValuationResultDto
    val varResultJson = """
        {
          "bookId":"book-A","calculationType":"PARAMETRIC","confidenceLevel":"CL_95",
          "varValue":"50000.0","expectedShortfall":"65000.0",
          "componentBreakdown":[],"calculatedAt":"2025-01-15T10:00:00Z"
        }
    """.trimIndent()

    // Minimal stress test result JSON satisfying StressTestResultDto
    val stressResultJson = """
        {
          "scenarioName":"GFC 2008","baseVar":"50000.0","stressedVar":"80000.0",
          "pnlImpact":"-30000.0","assetClassImpacts":[],"calculatedAt":"2025-01-15T10:00:00Z"
        }
    """.trimIndent()

    // Minimal cross-book VaR result JSON satisfying CrossBookVaRResultClientDto
    val crossBookVarJson = """
        {
          "portfolioGroupId":"group-1","bookIds":["book-A"],"calculationType":"PARAMETRIC",
          "confidenceLevel":"CL_95","varValue":50000.0,"expectedShortfall":65000.0,
          "componentBreakdown":[],"bookContributions":[],"totalStandaloneVar":50000.0,
          "diversificationBenefit":0.0,"calculatedAt":"2025-01-15T10:00:00Z"
        }
    """.trimIndent()

    // --- CALCULATE_RISK permission group (RISK_MANAGER has this, TRADER does not) ---

    test("RISK_MANAGER accessing own-assigned book via VaR route receives response") {
        val rmBookAccess = InMemoryBookAccessService(
            traderBooks = mapOf("rm-1" to setOf("book-A"))
        )
        val backend = BackendStubServer {
            post("/api/v1/risk/var/book-A") {
                call.respondText(varResultJson, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "rm-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = rmBookAccess, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/var/book-A") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("RISK_MANAGER can access any book via VaR route (unrestricted role)") {
        val backend = BackendStubServer {
            post("/api/v1/risk/var/any-book") {
                call.respondText(varResultJson.replace("\"book-A\"", "\"any-book\""), ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "rm-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/var/any-book") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // --- READ_RISK permission group (TRADER has READ_RISK) ---

    test("TRADER accessing own book via stress test route receives response") {
        val backend = BackendStubServer {
            post("/api/v1/risk/stress/book-A") {
                call.respondText(stressResultJson, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-1", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/stress/book-A") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scenarioName":"GFC 2008"}""")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("TRADER accessing unassigned book via stress test route receives 403") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-1", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/stress/book-B") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scenarioName":"GFC 2008"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // --- Cross-book VaR (bookIds in body, under CALCULATE_RISK — use RISK_MANAGER) ---

    test("RISK_MANAGER with access to all listed books can run cross-book VaR") {
        val backend = BackendStubServer {
            post("/api/v1/risk/var/cross-book") {
                call.respondText(crossBookVarJson, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "rm-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = bookAccessService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["book-A"],"portfolioGroupId":"group-1"}""")
                }
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("TRADER-like RISK_MANAGER with partial book ownership on cross-book VaR receives 403 naming denied book") {
        // Use a custom BookAccessService that restricts rm-1 to book-A only
        // (even though RM is normally unrestricted, we test the multi-book check logic)
        val restrictedService = object : BookAccessService {
            override fun canAccess(principal: com.kinetix.common.security.UserPrincipal, bookId: String): Boolean {
                return bookId == "book-A"
            }
        }
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "rm-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = restrictedService, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/var/cross-book") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"bookIds":["book-A","book-C"],"portfolioGroupId":"group-1"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
                response.bodyAsText() shouldContain "book-C"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    // --- TRADER with no assignments ---

    test("TRADER with no book assignments receives 403 on book-scoped routes") {
        val bookAccessNoAssignments = InMemoryBookAccessService(traderBooks = emptyMap())
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val riskClient = HttpRiskServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "trader-orphan", roles = listOf(Role.TRADER))

            testApplication {
                application { module(jwtConfig, riskClient = riskClient, bookAccessService = bookAccessNoAssignments, jwkProvider = jwkProvider) }
                val response = client.post("/api/v1/risk/stress/any-book") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"scenarioName":"GFC 2008"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
