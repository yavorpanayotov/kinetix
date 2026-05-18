package com.kinetix.position.kafka

import com.kinetix.testsupport.kafka.KafkaTestSetup
import com.kinetix.common.kafka.RetryableConsumer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties

class RetryableConsumerKafkaIntegrationTest : FunSpec({

    val bootstrapServers by lazy { KafkaTestSetup.start() }

    fun createProducer(): KafkaProducer<String, String> {
        val props = Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        }
        return KafkaProducer(props)
    }

    fun createConsumer(groupId: String): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        return KafkaConsumer(props)
    }

    fun createAdmin(): AdminClient {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        }
        return AdminClient.create(props)
    }

    fun ensureTopic(admin: AdminClient, topicName: String) {
        val existingTopics = admin.listTopics().names().get()
        if (topicName !in existingTopics) {
            admin.createTopics(listOf(NewTopic(topicName, 1, 1))).all().get()
        }
    }

    test("sends message to DLQ topic after max retries exhausted with real Kafka") {
        val dlqProducer = createProducer()
        val topicName = "test.retry.topic"
        val dlqTopicName = "$topicName.dlq"

        val admin = createAdmin()
        ensureTopic(admin, topicName)
        ensureTopic(admin, dlqTopicName)

        val retryable = RetryableConsumer(
            topic = topicName,
            maxRetries = 2,
            baseDelayMs = 10,
            dlqProducer = dlqProducer,
        )

        shouldThrow<RuntimeException> {
            retryable.process("dead-key", "dead-value") {
                throw RuntimeException("permanent failure")
            }
        }

        dlqProducer.flush()

        val dlqConsumer = createConsumer("dlq-test-group")
        dlqConsumer.subscribe(listOf(dlqTopicName))

        val records = mutableListOf<Pair<String?, String?>>()
        val deadline = System.currentTimeMillis() + 10_000
        while (records.isEmpty() && System.currentTimeMillis() < deadline) {
            val polled = dlqConsumer.poll(Duration.ofMillis(500))
            for (record in polled) {
                records.add(record.key() to record.value())
            }
        }

        records.size shouldBe 1
        records[0].first shouldBe "dead-key"
        records[0].second shouldBe "dead-value"

        dlqConsumer.close()
        dlqProducer.close()
        admin.close()
    }

    test("successful processing does not send to DLQ") {
        val dlqProducer = createProducer()
        val topicName = "test.success.topic"
        val dlqTopicName = "$topicName.dlq"

        val admin = createAdmin()
        ensureTopic(admin, topicName)
        ensureTopic(admin, dlqTopicName)

        val retryable = RetryableConsumer(
            topic = topicName,
            maxRetries = 2,
            baseDelayMs = 10,
            dlqProducer = dlqProducer,
        )

        val result = retryable.process("good-key", "good-value") {
            "processed"
        }
        result shouldBe "processed"

        dlqProducer.flush()

        val dlqConsumer = createConsumer("dlq-success-group")
        dlqConsumer.subscribe(listOf(dlqTopicName))

        val polled = dlqConsumer.poll(Duration.ofSeconds(2))
        polled.count() shouldBe 0

        dlqConsumer.close()
        dlqProducer.close()
        admin.close()
    }

    test("retries succeed before DLQ after transient failures with real Kafka") {
        val dlqProducer = createProducer()
        val topicName = "test.transient.topic"
        val dlqTopicName = "$topicName.dlq"

        val admin = createAdmin()
        ensureTopic(admin, topicName)
        ensureTopic(admin, dlqTopicName)

        val retryable = RetryableConsumer(
            topic = topicName,
            maxRetries = 3,
            baseDelayMs = 10,
            dlqProducer = dlqProducer,
        )

        var callCount = 0
        val result = retryable.process("transient-key", "transient-value") {
            callCount++
            if (callCount < 3) throw RuntimeException("transient failure")
            "recovered"
        }

        result shouldBe "recovered"
        callCount shouldBe 3

        dlqProducer.flush()

        val dlqConsumer = createConsumer("dlq-transient-group")
        dlqConsumer.subscribe(listOf(dlqTopicName))

        val polled = dlqConsumer.poll(Duration.ofSeconds(2))
        polled.count() shouldBe 0

        dlqConsumer.close()
        dlqProducer.close()
        admin.close()
    }
})
