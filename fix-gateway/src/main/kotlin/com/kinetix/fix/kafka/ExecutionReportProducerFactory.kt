package com.kinetix.fix.kafka

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

/**
 * Builds the idempotent Kafka producer that fix-gateway uses to publish
 * `execution.reports` (ADR-0035 phase 3 §3.5).
 *
 * Settings:
 *   - `enable.idempotence = true` — exactly-once semantics within a single producer session.
 *   - `acks = all` — wait for ISR replication so unacked-leader-failover does not lose events.
 *   - `max.in.flight.requests.per.connection = 5` — Kafka's idempotence ceiling without
 *     ordering loss.
 *   - `delivery.timeout.ms = 120000` — bounded retry window; matches plan §3.5.
 *
 * Crashes between FIX-receive and Kafka-publish are handled by FIX session resync: the venue
 * resends unacked 35=8s on logon. The Postgres-backed QuickFIX/J `MessageStore` (configured
 * in `FixSessionManager`) is the durability anchor; this producer's job is to never lose an
 * event the broker accepted.
 */
object ExecutionReportProducerFactory {

    fun idempotent(bootstrapServers: String): KafkaProducer<String, String> =
        KafkaProducer(idempotentProps(bootstrapServers))

    fun idempotentProps(bootstrapServers: String): Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000")
    }
}
