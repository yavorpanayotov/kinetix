package com.kinetix.gateway.auth

import com.kinetix.common.security.Role
import com.kinetix.gateway.client.HttpRegulatoryServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

private val scenarioJson = """
    {
      "id":"sc-1","name":"Shock","description":"desc","shocks":"{}","status":"DRAFT",
      "createdBy":"risk-mgr-1","approvedBy":null,"approvedAt":null,
      "createdAt":"2026-01-01T00:00:00Z"
    }
""".trimIndent()

private val approvedScenarioJson = """
    {
      "id":"sc-1","name":"Shock","description":"desc","shocks":"{}","status":"APPROVED",
      "createdBy":"analyst-1","approvedBy":"risk-mgr-1","approvedAt":"2026-01-02T00:00:00Z",
      "createdAt":"2026-01-01T00:00:00Z"
    }
""".trimIndent()

class SecurityEnforcementAcceptanceTest : FunSpec({

    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()

    test("unauthenticated request to stress-scenarios returns 401") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application { module(jwtConfig, regulatoryClient = regulatoryClient, jwkProvider = jwkProvider) }

                val response = client.get("/api/v1/stress-scenarios")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("VIEWER cannot create a stress scenario (403)") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(roles = listOf(Role.VIEWER))

            testApplication {
                application { module(jwtConfig, regulatoryClient = regulatoryClient, jwkProvider = jwkProvider) }

                val response = client.post("/api/v1/stress-scenarios") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Shock","description":"desc","shocks":"{}"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("COMPLIANCE user cannot create a stress scenario (403)") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(roles = listOf(Role.COMPLIANCE))

            testApplication {
                application { module(jwtConfig, regulatoryClient = regulatoryClient, jwkProvider = jwkProvider) }

                val response = client.post("/api/v1/stress-scenarios") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Shock","description":"desc","shocks":"{}"}""")
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("RISK_MANAGER can create a stress scenario with createdBy from JWT") {
        // Capture the body the gateway sends to the backend so we can assert
        // that createdBy was extracted from the JWT, not copied from the request body.
        val capturedBody = AtomicReference<String>(null)
        val backend = BackendStubServer {
            post("/api/v1/stress-scenarios") {
                capturedBody.set(call.receiveText())
                call.respondText(scenarioJson, ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "risk-mgr-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, regulatoryClient = regulatoryClient, jwkProvider = jwkProvider) }

                val response = client.post("/api/v1/stress-scenarios") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Shock","description":"desc","shocks":"{}"}""")
                }
                response.status shouldBe HttpStatusCode.Created
                // createdBy in the upstream request must come from JWT principal, NOT from request body
                val upstreamBody = Json.parseToJsonElement(capturedBody.get()).jsonObject
                upstreamBody["createdBy"]?.jsonPrimitive?.content shouldBe "risk-mgr-1"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("RISK_MANAGER can approve a scenario with approvedBy from JWT") {
        val capturedBody = AtomicReference<String>(null)
        val backend = BackendStubServer {
            patch("/api/v1/stress-scenarios/sc-1/approve") {
                capturedBody.set(call.receiveText())
                call.respondText(approvedScenarioJson, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "risk-mgr-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, regulatoryClient = regulatoryClient, jwkProvider = jwkProvider) }

                val response = client.patch("/api/v1/stress-scenarios/sc-1/approve") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                response.status shouldBe HttpStatusCode.OK
                // approvedBy in the upstream request must come from JWT principal, NOT from request body
                val upstreamBody = Json.parseToJsonElement(capturedBody.get()).jsonObject
                upstreamBody["approvedBy"]?.jsonPrimitive?.content shouldBe "risk-mgr-1"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("body createdBy is ignored when JWT principal is present (identity spoofing prevention)") {
        val capturedBody = AtomicReference<String>(null)
        val spoofedScenarioJson = """
            {
              "id":"sc-2","name":"Spoofed","description":"desc","shocks":"{}","status":"DRAFT",
              "createdBy":"risk-mgr-1","approvedBy":null,"approvedAt":null,
              "createdAt":"2026-01-01T00:00:00Z"
            }
        """.trimIndent()
        val backend = BackendStubServer {
            post("/api/v1/stress-scenarios") {
                capturedBody.set(call.receiveText())
                call.respondText(spoofedScenarioJson, ContentType.Application.Json, HttpStatusCode.Created)
            }
        }
        val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(httpClient, backend.baseUrl)
            val token = TestJwtHelper.generateToken(userId = "risk-mgr-1", roles = listOf(Role.RISK_MANAGER))

            testApplication {
                application { module(jwtConfig, regulatoryClient = regulatoryClient, jwkProvider = jwkProvider) }

                val response = client.post("/api/v1/stress-scenarios") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    // Body sends a DIFFERENT createdBy — this must be ignored
                    setBody("""{"name":"Spoofed","description":"desc","shocks":"{}","createdBy":"attacker-id"}""")
                }
                response.status shouldBe HttpStatusCode.Created
                // createdBy must come from JWT (risk-mgr-1), not from body (attacker-id)
                val upstreamBody = Json.parseToJsonElement(capturedBody.get()).jsonObject
                upstreamBody["createdBy"]?.jsonPrimitive?.content shouldBe "risk-mgr-1"
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("unauthenticated request to audit routes returns 401") {
        val mockHttpClient = HttpClient(MockEngine { respond("[]", HttpStatusCode.OK) }) {
            install(ContentNegotiation) { json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) }
        }
        val backend = BackendStubServer { }
        val regulatoryHttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
        try {
            val regulatoryClient = HttpRegulatoryServiceClient(regulatoryHttpClient, backend.baseUrl)

            testApplication {
                application {
                    module(
                        jwtConfig,
                        regulatoryClient = regulatoryClient,
                        httpClient = mockHttpClient,
                        auditBaseUrl = "http://audit-service",
                        jwkProvider = jwkProvider,
                    )
                }
                val response = client.get("/api/v1/audit/events")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        } finally {
            regulatoryHttpClient.close()
            backend.close()
        }
    }
})
