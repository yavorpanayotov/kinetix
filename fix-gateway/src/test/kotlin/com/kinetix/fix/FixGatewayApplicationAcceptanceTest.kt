package com.kinetix.fix

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Phase 1 acceptance test (plan 1.10): boots the full fix-gateway service against
 * Testcontainers Postgres + Kafka and asserts that /health/ready returns 200
 * and /metrics exposes the JVM up gauge.
 */
class FixGatewayApplicationAcceptanceTest : FunSpec({

    val postgres = PostgreSQLContainer(
        DockerImageName.parse("timescale/timescaledb:latest-pg17")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("fix_gateway_test")
        .withUsername("test")
        .withPassword("test")

    val kafka = KafkaContainer("apache/kafka:3.8.1")

    beforeSpec {
        postgres.start()
        kafka.start()
    }

    afterSpec {
        postgres.stop()
        kafka.stop()
    }

    test("GET /health returns 200 with status UP") {
        testApplication {
            environment {
                config = serviceConfig(postgres, kafka)
            }
            application { moduleWithDependencies() }

            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "UP"
        }
    }

    test("GET /health/ready returns READY with database and kafka checks OK") {
        testApplication {
            environment {
                config = serviceConfig(postgres, kafka)
            }
            application { moduleWithDependencies() }

            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "READY"
            body shouldContain "\"database\""
            body shouldContain "\"kafka\""
        }
    }

    test("GET /metrics exposes Prometheus exposition format with JVM metrics") {
        testApplication {
            environment {
                config = serviceConfig(postgres, kafka)
            }
            application { moduleWithDependencies() }

            val response = client.get("/metrics")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "# HELP"
            body shouldContain "jvm_memory"
        }
    }
})

private fun serviceConfig(postgres: PostgreSQLContainer<*>, kafka: KafkaContainer) = MapApplicationConfig(
    "database.jdbcUrl" to postgres.jdbcUrl,
    "database.username" to postgres.username,
    "database.password" to postgres.password,
    "kafka.bootstrapServers" to kafka.bootstrapServers,
    "grpc.port" to "0",
)
