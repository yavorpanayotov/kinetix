package com.kinetix.fix.grpc

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class FixGatewayServer(
    private val port: Int,
    private val services: List<BindableService> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(FixGatewayServer::class.java)
    private var server: Server? = null

    fun start(): FixGatewayServer {
        val builder = NettyServerBuilder.forPort(port)
        services.forEach(builder::addService)
        server = builder.build().start()
        logger.info("FixGatewayServer started on port {} with {} service(s)", boundPort(), services.size)
        return this
    }

    fun boundPort(): Int = server?.port ?: port

    fun awaitTermination() {
        server?.awaitTermination()
    }

    fun stop(timeoutSeconds: Long = 5) {
        server?.shutdown()?.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
    }
}
