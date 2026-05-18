package com.kinetix.testsupport.kafka

import com.kinetix.testsupport.containers.TestcontainerCaps
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

/**
 * Shared Testcontainers-backed Kafka setup. Each test module that needs a
 * real Kafka broker should depend on test-support and use this helper rather
 * than duplicating the container plumbing.
 *
 * One container instance is reused across tests in the same JVM — starting
 * Kafka takes several seconds, so isolation is achieved per-test by using
 * a unique topic name (e.g. include UUID.randomUUID()) rather than by
 * recreating the container.
 */
object KafkaTestSetup {

    private val kafka = TestcontainerCaps.tuneKafka(
        org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1"),
    )

    fun start(): String {
        if (!kafka.isRunning) {
            kafka.start()
        }
        return kafka.bootstrapServers
    }

    fun createProducer(bootstrapServers: String): KafkaProducer<String, String> {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        }
        return KafkaProducer(props)
    }

    fun createConsumer(bootstrapServers: String, groupId: String): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        return KafkaConsumer(props)
    }

    fun ensureTopic(bootstrapServers: String, topicName: String, partitions: Int = 1) {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        }
        AdminClient.create(props).use { admin ->
            val existing = admin.listTopics().names().get()
            if (topicName !in existing) {
                admin.createTopics(listOf(NewTopic(topicName, partitions, 1))).all().get()
            }
        }
    }
}
