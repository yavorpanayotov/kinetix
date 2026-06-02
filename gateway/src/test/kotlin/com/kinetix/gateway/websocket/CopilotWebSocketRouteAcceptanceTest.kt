package com.kinetix.gateway.websocket

import com.kinetix.common.security.Role
import com.kinetix.gateway.auth.TestJwtHelper
import com.kinetix.gateway.dtos.CopilotPushRequest
import com.kinetix.gateway.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Acceptance test for the gateway's `/ws/copilot` WebSocket route — the
 * subscriber side of the intraday Copilot push channel (PR 7 / ADR-0036,
 * checkbox 7.6 of `docs/plans/ai-v2.md`).
 *
 * The route mirrors `/ws/alerts`: JWT auth on connect, then book-scope
 * filtering by the connecting user's `X-User-Books` access (the JWT `books`
 * claim). The [CopilotBroadcaster] fans an intraday push out only to
 * subscribers whose book scope covers the push's `book_id`.
 *
 * Per CLAUDE.md, gateway WebSocket route acceptance tests run on the Ktor test
 * host (`testApplication`); a real WebSocket client connects and the broadcaster
 * is driven directly to assert which subscribers a push reaches.
 */
class CopilotWebSocketRouteAcceptanceTest : FunSpec({

    val jwtConfig = TestJwtHelper.testJwtConfig()
    val jwkProvider = TestJwtHelper.testJwkProvider()

    fun push(bookId: String): CopilotWebSocketMessage = CopilotWebSocketMessage(
        push = CopilotPushRequest(
            alertType = "VAR_BREACH",
            severity = "critical",
            bookId = bookId,
            headline = "Critical VAR_BREACH on $bookId",
            contextBullets = listOf("Current VaR up sharply"),
            sessionId = "9f2b1c4d-0000-0000-0000-000000000001",
            generatedAt = "2026-05-20T09:00:05Z",
        ),
    )

    test("connection is rejected without a token") {
        val broadcaster = CopilotBroadcaster()

        testApplication {
            application { module(jwtConfig, broadcaster, jwkProvider) }
            val client = createClient { install(ClientWebSockets) }

            var connectionClosed = false
            try {
                client.webSocket("/ws/copilot") {
                    for (frame in incoming) {
                        if (frame is Frame.Close) {
                            connectionClosed = true
                            break
                        }
                    }
                    connectionClosed = true
                }
            } catch (_: ClosedReceiveChannelException) {
                connectionClosed = true
            }
            connectionClosed shouldBe true
        }
    }

    test("connection is rejected with an invalid token") {
        val broadcaster = CopilotBroadcaster()
        val badToken = TestJwtHelper.generateTokenWithWrongSignature()

        testApplication {
            application { module(jwtConfig, broadcaster, jwkProvider) }
            val client = createClient { install(ClientWebSockets) }

            var connectionClosed = false
            try {
                client.webSocket("/ws/copilot?token=$badToken") {
                    for (frame in incoming) {
                        if (frame is Frame.Close) {
                            connectionClosed = true
                            break
                        }
                    }
                    connectionClosed = true
                }
            } catch (_: ClosedReceiveChannelException) {
                connectionClosed = true
            }
            connectionClosed shouldBe true
        }
    }

    test("connection succeeds with a valid token") {
        val broadcaster = CopilotBroadcaster()
        val token = TestJwtHelper.generateToken(roles = listOf(Role.TRADER))

        testApplication {
            application { module(jwtConfig, broadcaster, jwkProvider) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/ws/copilot?token=$token") {
                delay(50)
                broadcaster.subscriberCount() shouldBe 1
                outgoing.close()
            }
        }
    }

    test("a push for a book reaches a subscriber scoped to that book") {
        val broadcaster = CopilotBroadcaster()
        val token = TestJwtHelper.generateToken(
            userId = "trader-fx",
            roles = listOf(Role.TRADER),
            books = listOf("fx-main"),
        )

        testApplication {
            application { module(jwtConfig, broadcaster, jwkProvider) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/ws/copilot?token=$token") {
                delay(50)
                broadcaster.broadcast(push(bookId = "fx-main"))

                val frame = withTimeoutOrNull(2000) { incoming.receive() } as? Frame.Text
                frame.shouldNotBeNull()
                val json = Json.parseToJsonElement(frame.readText()).jsonObject
                json["type"]?.jsonPrimitive?.content shouldBe "intraday_push"
                json["push"]?.jsonObject?.get("book_id")?.jsonPrimitive?.content shouldBe "fx-main"
            }
        }
    }

    test("a push is delivered only to subscribers scoped to its book, not to others") {
        val broadcaster = CopilotBroadcaster()
        val fxToken = TestJwtHelper.generateToken(
            userId = "trader-fx",
            roles = listOf(Role.TRADER),
            books = listOf("fx-main"),
        )
        val ratesToken = TestJwtHelper.generateToken(
            userId = "trader-rates",
            roles = listOf(Role.TRADER),
            books = listOf("rates-emea"),
        )

        testApplication {
            application { module(jwtConfig, broadcaster, jwkProvider) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/ws/copilot?token=$fxToken") {
                val fxSession = this
                client.webSocket("/ws/copilot?token=$ratesToken") {
                    val ratesSession = this
                    // Both subscribers registered before the push fans out.
                    delay(100)
                    broadcaster.subscriberCount() shouldBe 2

                    broadcaster.broadcast(push(bookId = "fx-main"))

                    // The fx-main-scoped subscriber receives the push.
                    val fxFrame = withTimeoutOrNull(2000) {
                        fxSession.incoming.receive()
                    } as? Frame.Text
                    fxFrame.shouldNotBeNull()
                    val fxJson = Json.parseToJsonElement(fxFrame.readText()).jsonObject
                    fxJson["push"]?.jsonObject?.get("book_id")?.jsonPrimitive?.content shouldBe "fx-main"

                    // The rates-emea-scoped subscriber receives nothing.
                    val ratesFrame = withTimeoutOrNull(500) {
                        ratesSession.incoming.receive()
                    }
                    ratesFrame.shouldBeNull()

                    ratesSession.outgoing.close()
                }
                fxSession.outgoing.close()
            }
        }
    }

    test("a wildcard-scoped subscriber receives a push for any book") {
        val broadcaster = CopilotBroadcaster()
        // No `books` claim — risk managers are not book-restricted.
        val rmToken = TestJwtHelper.generateToken(
            userId = "rm-1",
            roles = listOf(Role.RISK_MANAGER),
        )

        testApplication {
            application { module(jwtConfig, broadcaster, jwkProvider) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/ws/copilot?token=$rmToken") {
                delay(50)
                broadcaster.broadcast(push(bookId = "some-book-the-rm-never-listed"))

                val frame = withTimeoutOrNull(2000) { incoming.receive() } as? Frame.Text
                frame.shouldNotBeNull()
                val json = Json.parseToJsonElement(frame.readText()).jsonObject
                json["push"]?.jsonObject?.get("book_id")?.jsonPrimitive?.content shouldBe
                    "some-book-the-rm-never-listed"
            }
        }
    }
})
