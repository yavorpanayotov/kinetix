package com.kinetix.demo.routes

import com.kinetix.demo.schedule.BootstrapResult
import com.kinetix.demo.schedule.BootstrapState
import com.kinetix.demo.schedule.BootstrapStateHolder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Unit tests for [BootstrapStatusRoutes].
 *
 * Exercises the GET /demo/bootstrap-status endpoint using Ktor's testApplication,
 * which exercises the real routing and serialisation pipeline without binding a port.
 */
class BootstrapStatusRoutesTest : FunSpec({

    test("returns NOT_STARTED with null counts when bootstrap has not started") {
        val holder = BootstrapStateHolder()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { bootstrapStatusRoutes(holder) }
            }
            val response = client.get("/demo/bootstrap-status")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"state\":\"NOT_STARTED\""
        }
    }

    test("returns IN_PROGRESS when bootstrap is running") {
        val holder = BootstrapStateHolder()
        holder.setInProgress()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { bootstrapStatusRoutes(holder) }
            }
            val response = client.get("/demo/bootstrap-status")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"state\":\"IN_PROGRESS\""
        }
    }

    test("returns READY with full counts when bootstrap completed successfully") {
        val holder = BootstrapStateHolder()
        holder.setInProgress()
        holder.setReady(
            BootstrapResult(
                successCount = 8,
                failureCount = 0,
                failedBooks = emptyList(),
                durationMillis = 1500L,
                sodSuccessCount = 8,
                sodFailureCount = 0,
                sodFailedBooks = emptyList(),
            ),
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { bootstrapStatusRoutes(holder) }
            }
            val response = client.get("/demo/bootstrap-status")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"state\":\"READY\""
            body shouldContain "\"successCount\":8"
            body shouldContain "\"failureCount\":0"
            body shouldContain "\"sodSuccessCount\":8"
            body shouldContain "\"sodFailureCount\":0"
        }
    }

    test("returns FAILED when bootstrap failed and stores partial result") {
        val holder = BootstrapStateHolder()
        holder.setInProgress()
        holder.setFailed(
            BootstrapResult(
                successCount = 3,
                failureCount = 5,
                failedBooks = listOf("book-a"),
                durationMillis = 800L,
                sodSuccessCount = 0,
                sodFailureCount = 0,
                sodFailedBooks = emptyList(),
            ),
        )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { bootstrapStatusRoutes(holder) }
            }
            val response = client.get("/demo/bootstrap-status")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"state\":\"FAILED\""
            body shouldContain "\"successCount\":3"
            body shouldContain "\"failureCount\":5"
        }
    }

    test("response Content-Type is application/json") {
        val holder = BootstrapStateHolder()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { bootstrapStatusRoutes(holder) }
            }
            val response = client.get("/demo/bootstrap-status")
            response.headers["Content-Type"] shouldContain ContentType.Application.Json.toString()
        }
    }
})
