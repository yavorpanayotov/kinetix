package com.kinetix.fix.health

import com.kinetix.common.health.CheckResult
import com.kinetix.common.health.ReadinessChecker
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Builds a [com.kinetix.common.health.ReadinessChecker] configured for fix-gateway:
 * database (Hikari + Flyway pending count) and Kafka broker reachability.
 */
object FixGatewayReadiness {

    fun build(dataSource: DataSource, kafkaBootstrapServers: String): ReadinessChecker {
        return ReadinessChecker(
            dataSource = dataSource,
            flywayLocation = "classpath:db/migration",
            extraChecks = mapOf(
                "kafka" to { checkKafka(kafkaBootstrapServers) },
            ),
        )
    }

    private fun checkKafka(bootstrapServers: String): CheckResult {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
            put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000)
        }
        return try {
            AdminClient.create(props).use { admin ->
                val nodes = admin.describeCluster().nodes().get(5, TimeUnit.SECONDS)
                CheckResult(status = "OK", details = mapOf("brokers" to nodes.size.toString()))
            }
        } catch (e: Exception) {
            CheckResult(status = "ERROR", details = mapOf("error" to (e.message ?: "unknown")))
        }
    }
}
