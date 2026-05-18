package com.kinetix.testsupport.grpc

import io.grpc.BindableService
import io.grpc.ConnectivityState
import io.grpc.ServerServiceDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Smoke test for [InProcessGrpcServer]. Exercises the structural lifecycle —
 * register → start → channel → close — without standing up any real Kinetix
 * service stub, so it stays fast and dependency-light. End-to-end coverage
 * (real proto stubs over the loopback channel) lives in the
 * `*AcceptanceTest`s of each consuming service.
 */
class InProcessGrpcServerTest : FunSpec({

    test("start binds a Netty server on a random localhost port") {
        InProcessGrpcServer().start().use { fake ->
            fake.port() shouldBeGreaterThan 0
        }
    }

    test("channel() returns a usable ManagedChannel pointed at the server") {
        InProcessGrpcServer().start().use { fake ->
            val channel = fake.channel()
            // A freshly built ManagedChannel starts in IDLE; we are happy as long as it
            // is not already shut down or in a terminal failure state.
            channel.getState(false) shouldBe ConnectivityState.IDLE
            channel.isShutdown shouldBe false
        }
    }

    test("channel() is cached — repeated calls return the same instance") {
        InProcessGrpcServer().start().use { fake ->
            val first = fake.channel()
            val second = fake.channel()
            (first === second) shouldBe true
        }
    }

    test("close() shuts the channel and server down cleanly") {
        val fake = InProcessGrpcServer().start()
        val channel = fake.channel()
        fake.close()
        channel.isShutdown shouldBe true
    }

    test("close() is safe before start() and idempotent") {
        val fake = InProcessGrpcServer()
        fake.close()
        fake.close()
    }

    test("port() before start() fails fast") {
        val fake = InProcessGrpcServer()
        shouldThrow<IllegalStateException> { fake.port() }
    }

    test("register() after start() fails fast") {
        InProcessGrpcServer().start().use { fake ->
            shouldThrow<IllegalStateException> {
                fake.register(NoOpService())
            }
        }
    }

    test("registered services are bound to the server") {
        InProcessGrpcServer()
            .register(NoOpService(serviceName = "kinetix.testsupport.NoOpService"))
            .start()
            .use { fake ->
                val services = fake.javaClass
                    .getDeclaredField("server")
                    .apply { isAccessible = true }
                    .get(fake) as io.grpc.Server
                services.services.map { it.serviceDescriptor.name } shouldContain
                    "kinetix.testsupport.NoOpService"
            }
    }
})

/**
 * Minimal `BindableService` used only to verify wiring — exposes a single
 * empty service definition with the supplied name. No real RPCs are
 * registered, so no proto-generated stubs are needed.
 */
private class NoOpService(
    private val serviceName: String = "kinetix.testsupport.NoOpService",
) : BindableService {
    override fun bindService(): ServerServiceDefinition =
        ServerServiceDefinition.builder(serviceName).build()
}
