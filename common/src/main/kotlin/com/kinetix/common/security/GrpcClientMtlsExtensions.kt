package com.kinetix.common.security

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import java.io.File

/**
 * Applies mutual TLS to a [NettyChannelBuilder] when [config] has [TlsConfig.enabled] set to true.
 *
 * When enabled:
 *  - If [TlsConfig.certPath] and [TlsConfig.keyPath] are present, the channel presents a client
 *    certificate to the server (full mutual TLS).
 *  - If only [TlsConfig.trustStorePath] is present, the channel validates the server's certificate
 *    but does not present one of its own (one-way TLS).
 *
 * When disabled, returns the builder unchanged (plaintext).
 *
 * Usage:
 * ```kotlin
 * NettyChannelBuilder
 *     .forAddress(host, port)
 *     .applyMtls(TlsConfig.fromEnvironment())
 *     .build()
 * ```
 */
fun NettyChannelBuilder.applyMtls(config: TlsConfig): NettyChannelBuilder {
    if (!config.enabled) return this

    val sslContextBuilder = GrpcSslContexts.forClient()

    if (config.trustStorePath != null) {
        sslContextBuilder.trustManager(File(config.trustStorePath))
    }

    if (config.certPath != null && config.keyPath != null) {
        sslContextBuilder.keyManager(File(config.certPath), File(config.keyPath))
    }

    return this.sslContext(sslContextBuilder.build())
}
