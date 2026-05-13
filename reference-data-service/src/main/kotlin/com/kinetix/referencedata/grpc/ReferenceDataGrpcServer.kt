package com.kinetix.referencedata.grpc

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Embedded gRPC server for reference-data-service. Hosts the TraderLookup
 * service today; new gRPC contracts owned by reference-data-service can be
 * bound here without touching the Ktor HTTP server.
 */
class ReferenceDataGrpcServer(
    private val port: Int,
    private val services: List<BindableService> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(ReferenceDataGrpcServer::class.java)
    private var server: Server? = null

    fun start(): ReferenceDataGrpcServer {
        val builder = NettyServerBuilder.forPort(port)
        services.forEach(builder::addService)
        server = builder.build().start()
        logger.info(
            "ReferenceDataGrpcServer started on port {} with {} service(s)",
            boundPort(), services.size,
        )
        return this
    }

    fun boundPort(): Int = server?.port ?: port

    fun stop(timeoutSeconds: Long = 5) {
        server?.shutdown()?.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
    }
}
