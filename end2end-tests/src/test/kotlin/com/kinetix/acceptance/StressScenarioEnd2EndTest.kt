package com.kinetix.acceptance

import com.kinetix.regulatory.client.RiskOrchestratorClient
import com.kinetix.regulatory.module
import com.kinetix.regulatory.persistence.DatabaseConfig
import com.kinetix.regulatory.persistence.DatabaseFactory
import com.kinetix.regulatory.persistence.ExposedFrtbCalculationRepository
import com.kinetix.regulatory.persistence.ExposedStressScenarioRepository
import com.kinetix.testsupport.containers.TestcontainerCaps
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class StressScenarioEnd2EndTest : BehaviorSpec({

    val regulatoryDb = TestcontainerCaps.tunePostgres(
        PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:latest-pg17")
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName("regulatory_test")
            .withUsername("test")
            .withPassword("test"),
        withTimescale = true,
    )

    lateinit var frtbRepo: ExposedFrtbCalculationRepository
    lateinit var stressRepo: ExposedStressScenarioRepository
    val riskClient = mockk<RiskOrchestratorClient>()

    beforeSpec {
        regulatoryDb.start()
        val db = DatabaseFactory.init(
            DatabaseConfig(
                jdbcUrl = regulatoryDb.jdbcUrl,
                username = regulatoryDb.username,
                password = regulatoryDb.password,
            )
        )
        frtbRepo = ExposedFrtbCalculationRepository(db)
        stressRepo = ExposedStressScenarioRepository(db)
    }

    afterSpec {
        regulatoryDb.stop()
    }

    given("a real PostgreSQL database with Flyway migrations") {
        `when`("a stress scenario goes through the full governance lifecycle") {
            then("each state transition is persisted and queryable via HTTP") {
                testApplication {
                    application {
                        module(frtbRepo, riskClient, stressScenarioRepository = stressRepo)
                    }

                    // Initially empty
                    val listEmpty = client.get("/api/v1/stress-scenarios")
                    listEmpty.status shouldBe HttpStatusCode.OK
                    val emptyList = Json.parseToJsonElement(listEmpty.bodyAsText()).jsonArray
                    emptyList.size shouldBe 0

                    // Create a scenario (DRAFT)
                    val createResponse = client.post("/api/v1/stress-scenarios") {
                        contentType(ContentType.Application.Json)
                        setBody("""
                            {
                                "name": "Equity Crash",
                                "description": "Global equity markets drop 30%",
                                "shocks": "{\"EQ\":-0.30}",
                                "createdBy": "analyst@kinetix.com"
                            }
                        """.trimIndent())
                    }
                    createResponse.status shouldBe HttpStatusCode.Created
                    val created = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                    created["status"]?.jsonPrimitive?.content shouldBe "DRAFT"
                    created["name"]?.jsonPrimitive?.content shouldBe "Equity Crash"
                    created["createdBy"]?.jsonPrimitive?.content shouldBe "analyst@kinetix.com"
                    val scenarioId = created["id"]!!.jsonPrimitive.content

                    // List contains the created scenario
                    val listOne = client.get("/api/v1/stress-scenarios")
                    listOne.status shouldBe HttpStatusCode.OK
                    val oneItemList = Json.parseToJsonElement(listOne.bodyAsText()).jsonArray
                    oneItemList.size shouldBe 1
                    oneItemList[0].jsonObject["id"]?.jsonPrimitive?.content shouldBe scenarioId

                    // Submit for approval (DRAFT → PENDING_APPROVAL)
                    val submitResponse = client.patch("/api/v1/stress-scenarios/$scenarioId/submit")
                    submitResponse.status shouldBe HttpStatusCode.OK
                    val submitted = Json.parseToJsonElement(submitResponse.bodyAsText()).jsonObject
                    submitted["status"]?.jsonPrimitive?.content shouldBe "PENDING_APPROVAL"

                    // Approve (PENDING_APPROVAL → APPROVED)
                    val approveResponse = client.patch("/api/v1/stress-scenarios/$scenarioId/approve") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"approvedBy":"manager@kinetix.com"}""")
                    }
                    approveResponse.status shouldBe HttpStatusCode.OK
                    val approved = Json.parseToJsonElement(approveResponse.bodyAsText()).jsonObject
                    approved["status"]?.jsonPrimitive?.content shouldBe "APPROVED"
                    approved["approvedBy"]?.jsonPrimitive?.content shouldBe "manager@kinetix.com"
                    approved.containsKey("approvedAt") shouldBe true

                    // Approved list contains the scenario
                    val approvedList = client.get("/api/v1/stress-scenarios/approved")
                    approvedList.status shouldBe HttpStatusCode.OK
                    val approvedScenarios = Json.parseToJsonElement(approvedList.bodyAsText()).jsonArray
                    approvedScenarios.size shouldBe 1
                    approvedScenarios[0].jsonObject["id"]?.jsonPrimitive?.content shouldBe scenarioId

                    // Retire (APPROVED → RETIRED)
                    val retireResponse = client.patch("/api/v1/stress-scenarios/$scenarioId/retire")
                    retireResponse.status shouldBe HttpStatusCode.OK
                    val retired = Json.parseToJsonElement(retireResponse.bodyAsText()).jsonObject
                    retired["status"]?.jsonPrimitive?.content shouldBe "RETIRED"

                    // Approved list is now empty (retired excluded)
                    val approvedAfterRetire = client.get("/api/v1/stress-scenarios/approved")
                    approvedAfterRetire.status shouldBe HttpStatusCode.OK
                    val emptyApproved = Json.parseToJsonElement(approvedAfterRetire.bodyAsText()).jsonArray
                    emptyApproved.size shouldBe 0
                }
            }
        }
    }
})
