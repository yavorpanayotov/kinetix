package com.kinetix.testsupport.grpc

import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit

/**
 * In-JVM gRPC fake-server harness for acceptance tests that need to stand in
 * for a real Kinetix gRPC service. Codifies the convention from CLAUDE.md
 * ("Acceptance tests use real infrastructure"): a fake `XxxServiceImplBase`
 * is bound to a Netty gRPC server on a random localhost port
 * (`NettyServerBuilder.forPort(0)`) and the client under test points at it
 * via `ManagedChannelBuilder...usePlaintext()` so calls still travel over
 * real HTTP/2 — interceptors, serialisation, and channel wiring all
 * exercised. The class name reflects the fact that the harness stays
 * in-process; the transport itself is real Netty over loopback (NOT the
 * gRPC `InProcessServer` API).
 *
 * Lifecycle: register services, [start] the server, take a [channel] (or
 * build your own from [port]), and close via Kotlin's `use {}` or by
 * calling [close] explicitly. [close] shuts down the server and any
 * channel built by this helper; channels obtained from [port] are owned by
 * the caller.
 *
 * Example:
 * ```
 * InProcessGrpcServer()
 *     .register(FakeTraderLookup(knownIds = setOf("tr-eg-001")))
 *     .start()
 *     .use { fake ->
 *         val stub = TraderLookupServiceGrpc.newBlockingStub(fake.channel())
 *         // ...
 *     }
 * ```
 */
class InProcessGrpcServer : AutoCloseable {

    private val services: MutableList<BindableService> = mutableListOf()
    private var server: Server? = null
    private var ownedChannel: ManagedChannel? = null

    /**
     * Registers a [BindableService] (typically a fake `XxxServiceImplBase`
     * subclass) with the server. Must be called before [start]; throws if
     * the server has already been started.
     */
    fun register(service: BindableService): InProcessGrpcServer {
        check(server == null) { "Cannot register services after the server has started." }
        services += service
        return this
    }

    /**
     * Starts a Netty gRPC server on a random localhost port. Idempotent —
     * a second call is a no-op and returns the same instance.
     */
    fun start(): InProcessGrpcServer {
        if (server != null) return this
        val builder = NettyServerBuilder.forPort(0)
        services.forEach(builder::addService)
        server = builder.build().start()
        return this
    }

    /**
     * Returns the bound port. Throws if [start] has not yet been called.
     */
    fun port(): Int = checkStarted().port

    /**
     * Returns a [ManagedChannel] pointed at the running server. The channel
     * is built on first call and reused on subsequent calls. The helper
     * owns its lifecycle and shuts it down in [close]. Callers that need
     * an independently-owned channel can build one from [port].
     */
    fun channel(): ManagedChannel {
        checkStarted()
        return ownedChannel ?: ManagedChannelBuilder
            .forAddress("localhost", port())
            .usePlaintext()
            .build()
            .also { ownedChannel = it }
    }

    /**
     * Shuts down the channel built by [channel] (if any) and the server.
     * Safe to call before [start] and safe to call more than once.
     */
    override fun close() {
        ownedChannel?.shutdownNow()?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        ownedChannel = null
        server?.shutdownNow()?.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        server = null
    }

    private fun checkStarted(): Server =
        server ?: error("InProcessGrpcServer has not been started. Call start() first.")

    private companion object {
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
