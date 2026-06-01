package com.kinetix.gateway

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.ktor.client.request.*
import io.ktor.server.testing.*
import org.slf4j.LoggerFactory
import org.slf4j.Logger.ROOT_LOGGER_NAME

/**
 * The Log Search experience lets operators filter logs by the HTTP endpoint that
 * produced them. That only works if the request path is placed into the logging
 * MDC, from where the OpenTelemetry appender exports it to Loki as structured
 * metadata. This test pins the wiring: a handled request must emit an access log
 * whose MDC carries the request path under the `endpoint` key.
 */
class CallLoggingEndpointMdcTest : FunSpec({

    test("access log carries the request path in the 'endpoint' MDC key") {
        val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)

        try {
            testApplication {
                application { module() }
                client.get("/health")
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }

        val endpoints = appender.list.mapNotNull { it.mdcPropertyMap["endpoint"] }
        endpoints shouldContain "/health"
    }
})
