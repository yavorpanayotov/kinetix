package com.kinetix.common.security

data class TlsConfig(
    val enabled: Boolean = false,
    val certPath: String? = null,
    val keyPath: String? = null,
    val trustStorePath: String? = null,
    val selfSigned: Boolean = false,
) {
    companion object {
        fun forTesting(): TlsConfig = TlsConfig(enabled = true, selfSigned = true)
        fun forProduction(certPath: String, keyPath: String, trustStorePath: String? = null): TlsConfig =
            TlsConfig(enabled = true, certPath = certPath, keyPath = keyPath, trustStorePath = trustStorePath)
        fun disabled(): TlsConfig = TlsConfig(enabled = false)

        /**
         * Reads [KINETIX_PROFILE] from the environment. Returns a full mTLS client config
         * when the profile is "prod", otherwise returns [disabled].
         *
         * Production cert paths are overridable via:
         *  - KINETIX_TLS_CERT   → certPath       (default /etc/kinetix/certs/service-cert.pem)
         *  - KINETIX_TLS_KEY    → keyPath        (default /etc/kinetix/certs/service-key.pem)
         *  - KINETIX_TLS_CA     → trustStorePath (default /etc/kinetix/certs/ca-cert.pem)
         */
        fun fromEnvironment(): TlsConfig {
            return when (System.getenv("KINETIX_PROFILE")) {
                "prod" -> forProduction(
                    certPath = System.getenv("KINETIX_TLS_CERT") ?: "/etc/kinetix/certs/service-cert.pem",
                    keyPath = System.getenv("KINETIX_TLS_KEY") ?: "/etc/kinetix/certs/service-key.pem",
                    trustStorePath = System.getenv("KINETIX_TLS_CA") ?: "/etc/kinetix/certs/ca-cert.pem",
                )
                else -> disabled()
            }
        }
    }
}

data class GrpcTlsConfig(
    val tls: TlsConfig,
    val host: String = "localhost",
    val port: Int = 50051,
) {
    val isSecure: Boolean get() = tls.enabled
}
