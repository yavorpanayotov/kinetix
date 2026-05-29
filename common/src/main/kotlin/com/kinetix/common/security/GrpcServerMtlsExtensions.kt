package com.kinetix.common.security

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import java.io.File

/**
 * Applies mutual TLS to a [NettyServerBuilder] when [config] has [GrpcServerTlsConfig.enabled]
 * set to true.
 *
 * When enabled, the server presents [GrpcServerTlsConfig.certPath] / [GrpcServerTlsConfig.keyPath]
 * to connecting clients and requires clients to present a certificate signed by the CA at
 * [GrpcServerTlsConfig.clientCaPath] (ClientAuth.REQUIRE — this is mutual TLS, not one-way TLS).
 *
 * When disabled, returns the builder unchanged so callers can start a plaintext server without
 * any branching logic.
 *
 * Usage:
 * ```kotlin
 * NettyServerBuilder.forPort(port)
 *     .applyMtls(GrpcServerTlsConfig.fromEnvironment())
 *     .addService(myService)
 *     .build()
 *     .start()
 * ```
 */
fun NettyServerBuilder.applyMtls(config: GrpcServerTlsConfig): NettyServerBuilder {
    if (!config.enabled) return this

    requireNotNull(config.certPath) { "GrpcServerTlsConfig.certPath must be set when enabled=true" }
    requireNotNull(config.keyPath) { "GrpcServerTlsConfig.keyPath must be set when enabled=true" }
    requireNotNull(config.clientCaPath) { "GrpcServerTlsConfig.clientCaPath must be set when enabled=true (mutual TLS)" }

    val sslContext = GrpcSslContexts.forServer(File(config.certPath), File(config.keyPath))
        .trustManager(File(config.clientCaPath))
        .clientAuth(ClientAuth.REQUIRE)
        .build()

    return this.sslContext(sslContext)
}
