package com.kinetix.risk.schedule

import com.kinetix.proto.risk.MLPredictionServiceGrpcKt.MLPredictionServiceCoroutineStub
import com.kinetix.proto.risk.RegimeDetectionRequest
import com.kinetix.proto.risk.RegimeDetectionResponse
import com.kinetix.risk.client.GrpcRegimeDetectorClient
import com.kinetix.risk.grpc.FakeMLPredictionService
import com.kinetix.risk.grpc.GrpcFakeServer
import com.kinetix.risk.kafka.NoOpRegimeEventPublisher
import com.kinetix.risk.model.MarketRegime
import com.kinetix.risk.model.RegimeSignals
import com.kinetix.risk.persistence.DatabaseTestSetup
import com.kinetix.risk.persistence.ExposedMarketRegimeRepository
import com.kinetix.risk.persistence.MarketRegimeHistoryTable
import com.kinetix.risk.routes.marketRegimeRoutes
import com.kinetix.risk.service.AdaptiveRegimeParameterProvider
import com.kinetix.risk.service.PersistingRegimeTransitionListener
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Acceptance test proving the ScheduledRegimeDetector is wired end to end:
 * a detection cycle classifies the regime over a real gRPC channel, the
 * confirmed transition persists a RegimeHistory row to a real Postgres
 * database, and the /api/v1/risk/regime/current endpoint serves the live
 * detector state.
 */
class ScheduledRegimeDetectorAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val repository = ExposedMarketRegimeRepository(db)

    beforeEach {
        newSuspendedTransaction(db = db) { MarketRegimeHistoryTable.deleteAll() }
    }

    fun crisisResponse() = RegimeDetectionResponse.newBuilder()
        .setRegime("CRISIS")
        .setConfidence(0.88)
        .setIsConfirmed(true)
        .setConsecutiveObservations(3)
        .setDegradedInputs(false)
        .setCorrelationAnomalyScore(0.62)
        .build()

    fun detector(fake: FakeMLPredictionService, channel: io.grpc.ManagedChannel): ScheduledRegimeDetector {
        val client = GrpcRegimeDetectorClient(MLPredictionServiceCoroutineStub(channel))
        val listener = PersistingRegimeTransitionListener(repository, NoOpRegimeEventPublisher())
        return ScheduledRegimeDetector(
            regimeDetectorClient = client,
            signalProvider = { RegimeSignals(realisedVol20d = 0.30, crossAssetCorrelation = 0.82) },
            parameterProvider = AdaptiveRegimeParameterProvider(),
            listeners = listOf(listener),
            escalationDebounce = 3,
        )
    }

    test("a confirmed regime transition persists a RegimeHistory row over a real gRPC channel") {
        val fake = FakeMLPredictionService { crisisResponse() }
        GrpcFakeServer(fake).use { server ->
            val channel = server.channel()
            val detector = detector(fake, channel)

            // Three detection cycles confirm the escalation to CRISIS.
            repeat(3) { detector.detect() }

            detector.currentState.regime shouldBe MarketRegime.CRISIS

            val persisted = repository.findCurrent()
            persisted?.regime shouldBe MarketRegime.CRISIS
            fake.detectRegimeRequests.size shouldBe 3
            channel.shutdownNow()
        }
    }

    test("the regime detection request carries the gathered market signals") {
        val fake = FakeMLPredictionService { crisisResponse() }
        GrpcFakeServer(fake).use { server ->
            val channel = server.channel()
            val detector = detector(fake, channel)

            detector.detect()

            val request: RegimeDetectionRequest = fake.detectRegimeRequests.first()
            request.signals.realisedVol20D shouldBe 0.30
            request.signals.crossAssetCorrelation shouldBe 0.82
            channel.shutdownNow()
        }
    }

    test("the regime-current endpoint serves the live detector state") {
        val fake = FakeMLPredictionService { crisisResponse() }
        GrpcFakeServer(fake).use { server ->
            val channel = server.channel()
            val detector = detector(fake, channel)
            repeat(3) { detector.detect() }

            testApplication {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                routing {
                    marketRegimeRoutes(
                        currentStateProvider = { detector.currentState },
                        repository = repository,
                    )
                }

                val response = client.get("/api/v1/risk/regime/current")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["regime"]!!.jsonPrimitive.content shouldBe "CRISIS"
                body["isConfirmed"]!!.jsonPrimitive.content shouldBe "true"
                body["varParameters"]!!.jsonObject["calculationType"]!!
                    .jsonPrimitive.content shouldBe "MONTE_CARLO"
            }
            channel.shutdownNow()
        }
    }
})
