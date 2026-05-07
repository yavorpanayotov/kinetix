package com.kinetix.fix.grpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan

class FixGatewayServerTest : FunSpec({

    test("FixGatewayServer with port=0 binds to a random free port and reports it via boundPort()") {
        val server = FixGatewayServer(port = 0).start()
        try {
            server.boundPort() shouldBeGreaterThan 0
        } finally {
            server.stop()
        }
    }

    test("FixGatewayServer.stop is a no-op on an unstarted server") {
        val server = FixGatewayServer(port = 0)
        server.stop()
    }

    test("FixGatewayServer with no service implementations starts cleanly") {
        val server = FixGatewayServer(port = 0, services = emptyList()).start()
        try {
            server.boundPort() shouldBeGreaterThan 0
        } finally {
            server.stop()
        }
    }
})
