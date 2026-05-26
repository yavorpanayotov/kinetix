package com.kinetix.demo.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalTime

class DemoConfigTest : FunSpec({

    test("applies defaults when env map is empty") {
        val config = DemoConfig.fromEnv { null }

        config.demoMode shouldBe false
        config.positionServiceUrl shouldBe "http://position-service:8080"
        config.riskOrchestratorUrl shouldBe "http://risk-orchestrator:8080"
        config.regulatoryServiceUrl shouldBe "http://regulatory-service:8080"
        config.kafkaBootstrapServers shouldBe "kafka:9092"
        config.tradingHoursStart shouldBe LocalTime.of(9, 0)
        config.tradingHoursEnd shouldBe LocalTime.of(16, 30)
        config.tradeCadenceSeconds shouldBe 90L
        config.breachBook shouldBe "derivatives-book"
        config.breachVarFactor shouldBe BigDecimal("0.50")
    }

    test("reads KAFKA_BOOTSTRAP_SERVERS from env") {
        val env = mapOf("KAFKA_BOOTSTRAP_SERVERS" to "kafka.example:9093")
        DemoConfig.fromEnv { env[it] }.kafkaBootstrapServers shouldBe "kafka.example:9093"
    }

    test("reads DEMO_MODE from env") {
        val env = mapOf("DEMO_MODE" to "true")
        DemoConfig.fromEnv { env[it] }.demoMode shouldBe true
    }

    test("reads POSITION_SERVICE_URL from env") {
        val env = mapOf("POSITION_SERVICE_URL" to "http://positions.example:9000")
        DemoConfig.fromEnv { env[it] }.positionServiceUrl shouldBe "http://positions.example:9000"
    }

    test("reads RISK_ORCHESTRATOR_URL from env") {
        val env = mapOf("RISK_ORCHESTRATOR_URL" to "http://risk.example:9001")
        DemoConfig.fromEnv { env[it] }.riskOrchestratorUrl shouldBe "http://risk.example:9001"
    }

    test("reads REGULATORY_SERVICE_URL from env") {
        val env = mapOf("REGULATORY_SERVICE_URL" to "http://reg.example:9002")
        DemoConfig.fromEnv { env[it] }.regulatoryServiceUrl shouldBe "http://reg.example:9002"
    }

    test("reads DEMO_TRADING_HOURS_START from env") {
        val env = mapOf("DEMO_TRADING_HOURS_START" to "08:15")
        DemoConfig.fromEnv { env[it] }.tradingHoursStart shouldBe LocalTime.of(8, 15)
    }

    test("reads DEMO_TRADING_HOURS_END from env") {
        val env = mapOf("DEMO_TRADING_HOURS_END" to "17:45")
        DemoConfig.fromEnv { env[it] }.tradingHoursEnd shouldBe LocalTime.of(17, 45)
    }

    test("reads DEMO_TRADE_CADENCE_SECONDS from env") {
        val env = mapOf("DEMO_TRADE_CADENCE_SECONDS" to "30")
        DemoConfig.fromEnv { env[it] }.tradeCadenceSeconds shouldBe 30L
    }

    test("reads DEMO_BREACH_BOOK from env") {
        val env = mapOf("DEMO_BREACH_BOOK" to "macro-hedge")
        DemoConfig.fromEnv { env[it] }.breachBook shouldBe "macro-hedge"
    }

    test("reads DEMO_BREACH_VAR_FACTOR from env") {
        val env = mapOf("DEMO_BREACH_VAR_FACTOR" to "0.25")
        DemoConfig.fromEnv { env[it] }.breachVarFactor shouldBe BigDecimal("0.25")
    }

    test("supports overriding every field together") {
        val env = mapOf(
            "DEMO_MODE" to "true",
            "POSITION_SERVICE_URL" to "http://positions.example:9000",
            "RISK_ORCHESTRATOR_URL" to "http://risk.example:9001",
            "REGULATORY_SERVICE_URL" to "http://reg.example:9002",
            "KAFKA_BOOTSTRAP_SERVERS" to "kafka.example:9093",
            "DEMO_TRADING_HOURS_START" to "07:30",
            "DEMO_TRADING_HOURS_END" to "18:15",
            "DEMO_TRADE_CADENCE_SECONDS" to "45",
            "DEMO_BREACH_BOOK" to "tech-momentum",
            "DEMO_BREACH_VAR_FACTOR" to "0.4",
        )

        val config = DemoConfig.fromEnv { env[it] }

        config.demoMode shouldBe true
        config.positionServiceUrl shouldBe "http://positions.example:9000"
        config.riskOrchestratorUrl shouldBe "http://risk.example:9001"
        config.regulatoryServiceUrl shouldBe "http://reg.example:9002"
        config.kafkaBootstrapServers shouldBe "kafka.example:9093"
        config.tradingHoursStart shouldBe LocalTime.of(7, 30)
        config.tradingHoursEnd shouldBe LocalTime.of(18, 15)
        config.tradeCadenceSeconds shouldBe 45L
        config.breachBook shouldBe "tech-momentum"
        config.breachVarFactor shouldBe BigDecimal("0.4")
    }

    test("invalid DEMO_BREACH_VAR_FACTOR throws with a clear message") {
        val env = mapOf("DEMO_BREACH_VAR_FACTOR" to "not-a-number")
        val ex = shouldThrow<IllegalArgumentException> {
            DemoConfig.fromEnv { env[it] }
        }
        ex.message!!.contains("DEMO_BREACH_VAR_FACTOR") shouldBe true
    }

    test("DEMO_MODE values other than 'true' are treated as false") {
        val env = mapOf("DEMO_MODE" to "yes")
        DemoConfig.fromEnv { env[it] }.demoMode shouldBe false
    }

    test("invalid DEMO_TRADING_HOURS_START throws with a clear message") {
        val env = mapOf("DEMO_TRADING_HOURS_START" to "not-a-time")
        val ex = shouldThrow<IllegalArgumentException> {
            DemoConfig.fromEnv { env[it] }
        }
        ex.message!!.contains("DEMO_TRADING_HOURS_START") shouldBe true
    }

    test("invalid DEMO_TRADING_HOURS_END throws with a clear message") {
        val env = mapOf("DEMO_TRADING_HOURS_END" to "25:99")
        val ex = shouldThrow<IllegalArgumentException> {
            DemoConfig.fromEnv { env[it] }
        }
        ex.message!!.contains("DEMO_TRADING_HOURS_END") shouldBe true
    }

    test("non-numeric DEMO_TRADE_CADENCE_SECONDS throws") {
        val env = mapOf("DEMO_TRADE_CADENCE_SECONDS" to "fast")
        val ex = shouldThrow<IllegalArgumentException> {
            DemoConfig.fromEnv { env[it] }
        }
        ex.message!!.contains("DEMO_TRADE_CADENCE_SECONDS") shouldBe true
    }

    test("zero DEMO_TRADE_CADENCE_SECONDS throws") {
        val env = mapOf("DEMO_TRADE_CADENCE_SECONDS" to "0")
        val ex = shouldThrow<IllegalArgumentException> {
            DemoConfig.fromEnv { env[it] }
        }
        ex.message!!.contains("DEMO_TRADE_CADENCE_SECONDS") shouldBe true
    }

    test("negative DEMO_TRADE_CADENCE_SECONDS throws") {
        val env = mapOf("DEMO_TRADE_CADENCE_SECONDS" to "-5")
        val ex = shouldThrow<IllegalArgumentException> {
            DemoConfig.fromEnv { env[it] }
        }
        ex.message!!.contains("DEMO_TRADE_CADENCE_SECONDS") shouldBe true
    }
})
