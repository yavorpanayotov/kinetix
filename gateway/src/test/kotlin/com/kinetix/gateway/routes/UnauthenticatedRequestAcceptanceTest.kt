package com.kinetix.gateway.routes

import com.kinetix.gateway.auth.TestJwtHelper
import com.kinetix.gateway.auth.configureJwtAuth
import com.kinetix.gateway.client.HttpPositionServiceClient
import com.kinetix.gateway.client.HttpPriceServiceClient
import com.kinetix.gateway.module
import com.kinetix.gateway.testing.BackendStubServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Acceptance tests proving that unauthenticated requests to /api/v1/... routes
 * receive 401 Unauthorized with a WWW-Authenticate: Bearer header, not 404
 * Not Found, which masked the session-expired condition from support engineers.
 *
 * See issue kx-42wk.4.
 */
class UnauthenticatedRequestAcceptanceTest : FunSpec({

    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()

    test("GET /api/v1/prices/{instrumentId}/latest without Authorization header returns 401 not 404") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val priceClient = HttpPriceServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application {
                    module()
                    configureJwtAuth(jwtConfig, jwkProvider)
                    routing {
                        authenticate("auth-jwt") {
                            priceRoutes(priceClient)
                        }
                    }
                }
                val response = client.get("/api/v1/prices/AAPL/latest")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/prices/{instrumentId}/latest without Authorization header includes WWW-Authenticate: Bearer header") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val priceClient = HttpPriceServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application {
                    module()
                    configureJwtAuth(jwtConfig, jwkProvider)
                    routing {
                        authenticate("auth-jwt") {
                            priceRoutes(priceClient)
                        }
                    }
                }
                val response = client.get("/api/v1/prices/AAPL/latest")
                response.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer realm=\"kinetix-test\""
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }

    test("GET /api/v1/positions/{bookId}/notes without Authorization header returns 401 not 404") {
        val backend = BackendStubServer { }
        val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
        try {
            val positionClient = HttpPositionServiceClient(httpClient, backend.baseUrl)

            testApplication {
                application {
                    module()
                    configureJwtAuth(jwtConfig, jwkProvider)
                    routing {
                        authenticate("auth-jwt") {
                            positionRoutes(positionClient)
                        }
                    }
                }
                val response = client.get("/api/v1/positions/port-1/notes")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        } finally {
            httpClient.close()
            backend.close()
        }
    }
})
