package com.kinetix.fix.session

import com.kinetix.common.execution.ExecutionEventType
import com.kinetix.common.execution.ExecutionReportEvent
import com.kinetix.fix.kafka.ExecutionReportPublisher
import com.kinetix.fix.kafka.ExecutionReportProducerFactory
import com.kinetix.fix.kafka.KafkaExecutionReportPublisher
import com.kinetix.fix.testing.InMemoryFixCounterpartyFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import quickfix.field.AvgPx
import quickfix.field.ClOrdID
import quickfix.field.CumQty
import quickfix.field.ExecID
import quickfix.field.ExecType
import quickfix.field.LastPx
import quickfix.field.LastQty
import quickfix.field.MsgType
import quickfix.field.OrdStatus
import quickfix.field.OrderID
import quickfix.field.OrderQty
import quickfix.field.Side
import quickfix.field.Symbol
import quickfix.fix44.ExecutionReport
import java.time.Duration
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan 2.13 — FixGatewayDurabilityAcceptanceTest.
 *
 * Scenario: fix-gateway receives a 35=8 ExecutionReport. A fault is injected between the
 * QuickFIX/J callback and `KafkaProducer.send()`. The service is "killed" (in-process
 * restart of the initiator). The venue replays the message on logon. Exactly ONE
 * `ExecutionReportEvent` lands on the `execution.reports` Kafka topic — no duplicate from
 * the pre-crash failed publish attempt.
 *
 * Implementation:
 *   - The "fault" is a one-shot spy on [ExecutionReportPublisher]: the first call fails
 *     (simulating Kafka publish failure), subsequent calls succeed.
 *   - The "kill + restart" is [InMemoryFixCounterpartyFixture.restartInitiator]: tears
 *     down the socket initiator while Testcontainers Postgres + Kafka stay alive.
 *   - "Replay on logon" is implemented by the test: when the initiator re-connects the
 *     acceptor re-sends the same 35=8. Since [resetOnLogon=true], sequence numbers reset
 *     and the message travels as a fresh seq=1 on the new session — exactly as a venue's
 *     "cancel on disconnect / replay on reconnect" policy behaves.
 *   - The Kafka idempotent producer (enable.idempotence=true) ensures that a second
 *     successful publish of the same clOrdID+execID does not produce two consumer-visible
 *     records within one producer session; across the restart the test relies on the
 *     publisher spy to ensure only ONE publish succeeds.
 */
class FixGatewayDurabilityAcceptanceTest : FunSpec({

    val postgres = PostgreSQLContainer(
        DockerImageName.parse("timescale/timescaledb:latest-pg17")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("fix_gateway_durability_test")
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

    fun consumeAll(topic: String, expected: Int, timeoutSec: Long = 15): List<ExecutionReportEvent> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "durability-${System.nanoTime()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        val out = mutableListOf<ExecutionReportEvent>()
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos()
            while (out.size < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach { record ->
                    out += Json.decodeFromString<ExecutionReportEvent>(record.value())
                }
            }
        }
        return out
    }

    fun buildExecutionReport(clOrdId: String, venueOrderId: String = "VEN-DURABILITY-1"): ExecutionReport {
        val report = ExecutionReport()
        report.set(ClOrdID(clOrdId))
        report.set(OrderID(venueOrderId))
        report.set(ExecID("exec-durability-1"))
        report.set(ExecType(ExecType.TRADE))
        report.set(OrdStatus(OrdStatus.FILLED))
        report.set(Side(Side.BUY))
        report.set(Symbol("AAPL"))
        report.set(OrderQty(100.0))
        report.set(LastQty(100.0))
        report.set(LastPx(150.25))
        report.set(CumQty(100.0))
        report.set(AvgPx(150.25))
        return report
    }

    test("exactly one ExecutionReportEvent on Kafka despite fault between FIX-receive and Kafka publish plus service restart and venue replay") {
        val topic = "exec.durability.${System.nanoTime()}"
        val clOrdId = "clord-durability-${System.nanoTime()}"

        // One-shot fault spy: fails on the first publish call, succeeds on subsequent calls.
        val publishCallCount = AtomicInteger(0)
        val realPublisher = KafkaExecutionReportPublisher(
            producer = ExecutionReportProducerFactory.idempotent(kafka.bootstrapServers),
            topic = topic,
        )
        val faultingPublisher = ExecutionReportPublisher { event ->
            val call = publishCallCount.incrementAndGet()
            if (call == 1) {
                // Simulated fault: Kafka send fails (e.g. broker unreachable mid-commit).
                throw RuntimeException("Simulated Kafka publish fault (call #$call)")
            }
            realPublisher.publish(event)
        }

        val meterRegistry = SimpleMeterRegistry()
        val handler = InboundFixHandler(
            converter = FIXMessageConverter(),
            publisher = faultingPublisher,
            meterRegistry = meterRegistry,
        )

        val reconciliationCoordinator = SessionReconciliationCoordinator(
            messageLogRepository = NoOpFixMessageLogRepository,
            meterRegistry = meterRegistry,
        )

        val fixture = InMemoryFixCounterpartyFixture(
            jdbcUrl = postgres.jdbcUrl,
            jdbcUser = postgres.username,
            jdbcPassword = postgres.password,
            inboundFixHandler = handler,
            reconciliationCoordinator = reconciliationCoordinator,
            resetOnLogon = true,
        )

        fixture.use {
            // Phase 1: start, wait for logon, send 35=8 — publisher will fail.
            fixture.start()
            fixture.awaitLogon(timeoutMs = 10_000) shouldBe true

            val report = buildExecutionReport(clOrdId)
            fixture.sendInbound(report)

            // Give the initiator time to process (and fail) the publish.
            delay(500)

            // Confirm no event was published (the first attempt threw).
            val afterFirstAttempt = consumeAll(topic, expected = 1, timeoutSec = 2)
            afterFirstAttempt.size shouldBe 0

            // Phase 2: "kill" the service (restart initiator). Postgres seq state persists.
            fixture.resetLogonLatch()
            fixture.restartInitiator()
            fixture.awaitLogon(timeoutMs = 10_000) shouldBe true

            // Phase 3: venue "replays" the message on reconnect (test injects it again).
            // In production, the venue's cancel-on-disconnect policy sends this automatically.
            fixture.sendInbound(report)

            // Phase 4: assert exactly ONE event on Kafka.
            val events = consumeAll(topic, expected = 1, timeoutSec = 15)
            events.size shouldBe 1
            events[0].clOrdId shouldBe clOrdId
            events[0].eventType shouldBe ExecutionEventType.FILL

            // Confirm the spy was called exactly twice: once failed, once succeeded.
            publishCallCount.get() shouldBe 2
        }
    }
})

/**
 * No-op implementation of [FixMessageLogRepository] for tests that do not exercise
 * the message log. Returns empty lists and ignores writes.
 */
private object NoOpFixMessageLogRepository : FixMessageLogRepository {
    override suspend fun insert(entry: FixMessageLogEntry) = Unit
    override suspend fun markTerminal(venue: String, clOrdId: String) = Unit
    override suspend fun findOpenClOrdIds(venue: String, withinHours: Int): List<String> = emptyList()
}
