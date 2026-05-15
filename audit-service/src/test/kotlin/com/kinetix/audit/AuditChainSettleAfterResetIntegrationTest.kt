package com.kinetix.audit

import com.kinetix.audit.kafka.AuditEventConsumer
import com.kinetix.audit.kafka.KafkaTestSetup
import com.kinetix.audit.persistence.AuditEventRepository
import com.kinetix.audit.persistence.DatabaseTestSetup
import com.kinetix.audit.persistence.ExposedAuditEventRepository
import com.kinetix.audit.routes.demoResetRoutes
import com.kinetix.common.kafka.events.TradeEventMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.Properties
import java.util.UUID

/**
 * Pins the engineering SLA from `docs/plans/demo-follow-up.md` PR 2 item 5:
 *
 *   "Audit hash chain settles <90s after `/api/v1/admin/demo-reset` returns 200."
 *
 * Concretely: after a demo-reset truncates and reseeds the audit chain, any trade events
 * other services fan out on `trades.lifecycle` during the reset must be drained by the
 * audit-service consumer within 90 seconds — measured as Kafka consumer lag
 * (committed-offset vs end-offset) reaching zero for the `audit-service-group` on the
 * `trades.lifecycle` topic.
 *
 * Real Postgres (TimescaleDB) + real Kafka via Testcontainers — see CLAUDE.md "Known
 * Gotchas" on why this lives in `audit-service` rather than `common`.
 */
class AuditChainSettleAfterResetIntegrationTest : FunSpec({

    val resetToken = "settle-test-token"
    // Unique topic + group per spec so the shared Testcontainers Kafka isn't polluted by
    // the burst this test produces (other audit-service integration tests assume an empty
    // `trades.lifecycle` and a fresh `audit-service-group`).
    val topic = "trades.lifecycle.settle-${UUID.randomUUID()}"
    val groupId = "audit-service-group-settle-${UUID.randomUUID()}"
    val settleBudgetMs = 90_000L
    val publishedEventCount = 50

    val db = DatabaseTestSetup.startAndMigrate()
    val repository: AuditEventRepository = ExposedAuditEventRepository(db)
    val bootstrapServers = KafkaTestSetup.start()
    val json = Json { ignoreUnknownKeys = true }

    val consumerProps = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    }

    beforeSpec {
        // Clean slate — the seed anchor lookup in DevDataSeeder skips reseeding when the
        // anchor row exists, so we must truncate before each spec run. The audit_events
        // table is a TimescaleDB hypertable with compression enabled (V4 migration);
        // existing acceptance tests use plain TRUNCATE … RESTART IDENTITY which works
        // against the hypertable wrapper.
        runBlocking {
            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
            }
        }
    }

    afterSpec {
        // Be a good neighbour: leave the shared Testcontainers Postgres empty so other
        // specs in the integration-test suite are not polluted by the ~1153 seeded rows
        // this test writes via DemoResetRoutes → DevDataSeeder.
        runBlocking {
            newSuspendedTransaction(db = db) {
                exec("TRUNCATE TABLE audit_events RESTART IDENTITY")
            }
        }
    }

    test(
        "after POST /api/v1/internal/audit/demo-reset returns 200 and trade events are " +
            "published, the audit consumer drains Kafka lag to zero within 90s",
    ) {
        val kafkaConsumer = KafkaConsumer<String, String>(consumerProps)
        val auditConsumer = AuditEventConsumer(kafkaConsumer, repository, topic = topic)

        // Standalone scope keeps the consumer alive across the testApplication block and
        // lets us cancel it deterministically before the test returns — otherwise Kotest's
        // outer runBlocking waits forever on the consumer's polling loop.
        val consumerScope = CoroutineScope(Dispatchers.IO + Job())
        val consumerJob: Job = consumerScope.launch { auditConsumer.start() }

        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        demoResetRoutes(db, repository, resetToken)
                    }
                }

                // Trigger the demo-reset endpoint and assert it returns 200 quickly.
                val resetStartedAt = System.currentTimeMillis()
                val resetResponse = client.post("/api/v1/internal/audit/demo-reset") {
                    header("X-Demo-Reset-Token", resetToken)
                }
                resetResponse.status shouldBe HttpStatusCode.OK
                val resetReturnedAt = System.currentTimeMillis()
                val resetDurationMs = resetReturnedAt - resetStartedAt
                println(
                    "[audit-settle-test] demo-reset returned 200 in ${resetDurationMs}ms " +
                        "(scenario=multi-asset)",
                )

                // Fan-out simulation: in production other services emit a wave of trade events
                // to trades.lifecycle during the reset window. Publish a representative batch
                // to exercise the consumer-drain path the SLA pins.
                val producer = KafkaTestSetup.createProducer(bootstrapServers)
                try {
                    repeat(publishedEventCount) { i ->
                        val event = TradeEventMessage(
                            tradeId = "settle-t-$i",
                            bookId = "settle-book-${i % 3}",
                            instrumentId = "AAPL",
                            assetClass = "EQUITY",
                            side = if (i % 2 == 0) "BUY" else "SELL",
                            quantity = "100",
                            priceAmount = "150.00",
                            priceCurrency = "USD",
                            tradedAt = "2026-05-14T09:00:00Z",
                        )
                        producer.send(
                            ProducerRecord(topic, "settle-book-${i % 3}", json.encodeToString(event)),
                        ).get()
                    }
                    producer.flush()
                } finally {
                    producer.close()
                }

                // Poll Kafka consumer lag until it reaches zero or we exhaust the SLA budget.
                val settleStartNs = System.nanoTime()
                var observedLag = Long.MAX_VALUE
                val pollIntervalMs = 500L
                while ((System.nanoTime() - settleStartNs) / 1_000_000 < settleBudgetMs) {
                    observedLag = computeLag(bootstrapServers, topic, groupId)
                    if (observedLag == 0L) break
                    withContext(Dispatchers.IO) { Thread.sleep(pollIntervalMs) }
                }
                val settleDurationMs = (System.nanoTime() - settleStartNs) / 1_000_000

                println(
                    "[audit-settle-test] settle observed: lag=$observedLag " +
                        "settleDurationMs=$settleDurationMs " +
                        "settleBudgetMs=$settleBudgetMs " +
                        "publishedEvents=$publishedEventCount",
                )

                // Hard SLA: consumer must drain to zero lag within the budget.
                observedLag shouldBe 0L
                settleDurationMs shouldBeLessThanOrEqual settleBudgetMs

                // Sanity: the published batch must have been persisted alongside the seeded
                // audit chain — the reset reseeds, and the consumer appends the published
                // trade events. We compare the count of `settle-t-*` audit events directly so
                // that this assertion does not depend on the seeder catalogue size.
                val persistedSettleTrades = repository.findByBookId("settle-book-0").size +
                    repository.findByBookId("settle-book-1").size +
                    repository.findByBookId("settle-book-2").size
                persistedSettleTrades shouldBe publishedEventCount
            }
        } finally {
            consumerJob.cancelAndJoin()
        }
    }
})

/**
 * Returns the audit-service consumer group's lag on [topic]: sum across partitions of
 * (end offset - committed offset). When the consumer has not yet committed for a
 * partition that has data, the un-committed end-offset counts in full toward the lag —
 * a fresh consumer with messages waiting must not be reported as "drained".
 */
private fun computeLag(bootstrap: String, topic: String, groupId: String): Long {
    AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
        val partitionInfos = admin.describeTopics(listOf(topic))
            .allTopicNames().get()[topic]?.partitions().orEmpty()
        if (partitionInfos.isEmpty()) return Long.MAX_VALUE

        val topicPartitions = partitionInfos
            .map { TopicPartition(topic, it.partition()) }
        val endOffsets = admin.listOffsets(
            topicPartitions.associateWith { OffsetSpec.latest() },
        ).all().get()
        val committed = admin.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata().get()

        return topicPartitions.sumOf { tp ->
            val end = endOffsets[tp]?.offset() ?: 0L
            val committedOffset = committed[tp]?.offset() ?: 0L
            (end - committedOffset).coerceAtLeast(0)
        }
    }
}
