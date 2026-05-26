package com.kinetix.demo.config

import java.math.BigDecimal
import java.time.LocalTime
import java.time.format.DateTimeParseException

data class DemoConfig(
    val demoMode: Boolean,
    val positionServiceUrl: String,
    val riskOrchestratorUrl: String,
    val regulatoryServiceUrl: String,
    val kafkaBootstrapServers: String,
    val tradingHoursStart: LocalTime,
    val tradingHoursEnd: LocalTime,
    val tradeCadenceSeconds: Long,
    val breachBook: String,
    val breachVarFactor: BigDecimal,
) {
    companion object {
        private const val DEFAULT_POSITION_SERVICE_URL = "http://position-service:8080"
        private const val DEFAULT_RISK_ORCHESTRATOR_URL = "http://risk-orchestrator:8080"
        private const val DEFAULT_REGULATORY_SERVICE_URL = "http://regulatory-service:8080"
        private const val DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "kafka:9092"
        private const val DEFAULT_TRADING_HOURS_START = "09:00"
        private const val DEFAULT_TRADING_HOURS_END = "16:30"
        private const val DEFAULT_TRADE_CADENCE_SECONDS = 90L
        private const val DEFAULT_BREACH_BOOK = "derivatives-book"
        private const val DEFAULT_BREACH_VAR_FACTOR = "0.50"

        fun fromEnv(env: (String) -> String? = System::getenv): DemoConfig {
            val demoMode = env("DEMO_MODE")?.toBooleanStrictOrNull() ?: false
            val positionServiceUrl = env("POSITION_SERVICE_URL") ?: DEFAULT_POSITION_SERVICE_URL
            val riskOrchestratorUrl = env("RISK_ORCHESTRATOR_URL") ?: DEFAULT_RISK_ORCHESTRATOR_URL
            val regulatoryServiceUrl = env("REGULATORY_SERVICE_URL") ?: DEFAULT_REGULATORY_SERVICE_URL
            val kafkaBootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS") ?: DEFAULT_KAFKA_BOOTSTRAP_SERVERS
            val tradingHoursStart = parseTime("DEMO_TRADING_HOURS_START", env("DEMO_TRADING_HOURS_START") ?: DEFAULT_TRADING_HOURS_START)
            val tradingHoursEnd = parseTime("DEMO_TRADING_HOURS_END", env("DEMO_TRADING_HOURS_END") ?: DEFAULT_TRADING_HOURS_END)
            val tradeCadenceSeconds = parseCadence(env("DEMO_TRADE_CADENCE_SECONDS"))
            val breachBook = env("DEMO_BREACH_BOOK") ?: DEFAULT_BREACH_BOOK
            val breachVarFactor = parseBreachVarFactor(env("DEMO_BREACH_VAR_FACTOR") ?: DEFAULT_BREACH_VAR_FACTOR)

            return DemoConfig(
                demoMode = demoMode,
                positionServiceUrl = positionServiceUrl,
                riskOrchestratorUrl = riskOrchestratorUrl,
                regulatoryServiceUrl = regulatoryServiceUrl,
                kafkaBootstrapServers = kafkaBootstrapServers,
                tradingHoursStart = tradingHoursStart,
                tradingHoursEnd = tradingHoursEnd,
                tradeCadenceSeconds = tradeCadenceSeconds,
                breachBook = breachBook,
                breachVarFactor = breachVarFactor,
            )
        }

        private fun parseTime(envName: String, raw: String): LocalTime {
            return try {
                LocalTime.parse(raw)
            } catch (ex: DateTimeParseException) {
                throw IllegalArgumentException(
                    "$envName='$raw' is not a valid HH:mm time (expected e.g. 09:00)",
                    ex,
                )
            }
        }

        private fun parseCadence(raw: String?): Long {
            if (raw == null) return DEFAULT_TRADE_CADENCE_SECONDS
            val parsed = raw.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "DEMO_TRADE_CADENCE_SECONDS='$raw' is not a valid integer",
                )
            require(parsed > 0) {
                "DEMO_TRADE_CADENCE_SECONDS='$raw' must be > 0"
            }
            return parsed
        }

        private fun parseBreachVarFactor(raw: String): BigDecimal {
            return try {
                BigDecimal(raw)
            } catch (ex: NumberFormatException) {
                throw IllegalArgumentException(
                    "DEMO_BREACH_VAR_FACTOR='$raw' is not a valid decimal",
                    ex,
                )
            }
        }
    }
}
