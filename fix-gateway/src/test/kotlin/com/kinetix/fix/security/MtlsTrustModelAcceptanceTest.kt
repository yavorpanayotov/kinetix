package com.kinetix.fix.security

import com.kinetix.common.security.GrpcServerTlsConfig
import com.kinetix.common.security.TlsConfig
import com.kinetix.common.security.applyMtls
import com.kinetix.fix.grpc.FixGatewayServer
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Acceptance test for ADR-0037: inter-service mTLS trust model.
 *
 * Three cases verify the contract:
 *  1. mTLS enabled, client presents a valid cert signed by the shared CA → connection accepted.
 *  2. mTLS enabled, client presents no cert (plaintext / anonymous) → connection rejected.
 *  3. mTLS disabled (demo profile) → plaintext client connects without a cert.
 *
 * All gRPC calls use the Health service's `Check` RPC via raw channel state probing so
 * no service implementation is needed — the test validates the transport handshake only.
 */
class MtlsTrustModelAcceptanceTest : FunSpec({

    fun certResource(name: String): File =
        File(MtlsTrustModelAcceptanceTest::class.java.getResource("/certs/$name")!!.toURI())

    test("mTLS enabled — client with valid cert signed by shared CA connects successfully") {
        val caCert = certResource("ca-cert.pem")
        val serverCert = certResource("server-cert.pem")
        val serverKey = certResource("server-key.pem")
        val clientCert = certResource("client-cert.pem")
        val clientKey = certResource("client-key.pem")

        val serverTlsConfig = GrpcServerTlsConfig.forTesting(
            certPath = serverCert.absolutePath,
            keyPath = serverKey.absolutePath,
            clientCaPath = caCert.absolutePath,
        )

        val server = FixGatewayServer(port = 0, serverTlsConfig = serverTlsConfig).start()

        val clientSslContext = GrpcSslContexts.forClient()
            .trustManager(caCert)
            .keyManager(clientCert, clientKey)
            .build()

        val channel = NettyChannelBuilder
            .forAddress("localhost", server.boundPort())
            .sslContext(clientSslContext)
            .build()

        try {
            // Force a connection attempt by requesting state transition
            channel.getState(true)
            // Wait up to 5s for the channel to move out of IDLE — a TLS handshake
            // failure would drive it to TRANSIENT_FAILURE before READY.
            val deadline = System.currentTimeMillis() + 5_000
            var state = channel.getState(false)
            while (state == io.grpc.ConnectivityState.IDLE ||
                state == io.grpc.ConnectivityState.CONNECTING
            ) {
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(50)
                state = channel.getState(false)
            }
            // Channel must reach READY (handshake succeeded), not TRANSIENT_FAILURE
            state shouldBe io.grpc.ConnectivityState.READY
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            server.stop()
        }
    }

    test("mTLS enabled — client presenting no cert is rejected") {
        val caCert = certResource("ca-cert.pem")
        val serverCert = certResource("server-cert.pem")
        val serverKey = certResource("server-key.pem")

        val serverTlsConfig = GrpcServerTlsConfig.forTesting(
            certPath = serverCert.absolutePath,
            keyPath = serverKey.absolutePath,
            clientCaPath = caCert.absolutePath,
        )

        val server = FixGatewayServer(port = 0, serverTlsConfig = serverTlsConfig).start()

        // Anonymous client: trusts the server CA but presents no client cert
        val clientSslContext = GrpcSslContexts.forClient()
            .trustManager(caCert)
            .build()

        val channel = NettyChannelBuilder
            .forAddress("localhost", server.boundPort())
            .sslContext(clientSslContext)
            .build()

        try {
            channel.getState(true)
            val deadline = System.currentTimeMillis() + 5_000
            var state = channel.getState(false)
            while (state == io.grpc.ConnectivityState.IDLE ||
                state == io.grpc.ConnectivityState.CONNECTING
            ) {
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(50)
                state = channel.getState(false)
            }
            // Server must reject the no-cert client: TRANSIENT_FAILURE, not READY
            state shouldBe io.grpc.ConnectivityState.TRANSIENT_FAILURE
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            server.stop()
        }
    }

    test("mTLS disabled (demo profile) — plaintext client connects without a certificate") {
        val server = FixGatewayServer(port = 0).start()

        val channel = ManagedChannelBuilder
            .forAddress("localhost", server.boundPort())
            .usePlaintext()
            .build()

        try {
            channel.getState(true)
            val deadline = System.currentTimeMillis() + 5_000
            var state = channel.getState(false)
            while (state == io.grpc.ConnectivityState.IDLE ||
                state == io.grpc.ConnectivityState.CONNECTING
            ) {
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(50)
                state = channel.getState(false)
            }
            (state == io.grpc.ConnectivityState.READY ||
                state == io.grpc.ConnectivityState.IDLE) shouldBe true
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS)
            server.stop()
        }
    }
})
