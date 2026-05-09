package com.kinetix.fix.session

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import quickfix.Application
import quickfix.DefaultMessageFactory
import quickfix.FieldNotFound
import quickfix.IncorrectDataFormat
import quickfix.IncorrectTagValue
import quickfix.JdbcStoreFactory
import quickfix.Message
import quickfix.MessageCracker
import quickfix.SLF4JLogFactory
import quickfix.Session
import quickfix.SessionID
import quickfix.SessionSettings
import quickfix.ThreadedSocketInitiator
import quickfix.UnsupportedMessageType
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the QuickFIX/J `ThreadedSocketInitiator` lifecycle for production venue connectivity
 * (ADR-0035 §2.5). Wired in [Application.kt] when `FIX_GATEWAY_LIVE_SESSIONS=true`.
 *
 * Responsibilities:
 *   1. Build [SessionSettings] from [VenueSessionConfig] entries.
 *   2. Boot the initiator with Postgres-backed [JdbcStoreFactory] so sequence numbers
 *      survive restarts — this is the durability anchor for [FixGatewayDurabilityAcceptanceTest].
 *   3. Register an [Application] whose `fromApp` delegates to [InboundFixHandler.handle].
 *   4. On [Application.onLogon] trigger [SessionReconciliationCoordinator.onLogon] to block
 *      new outbound RPCs during the post-reconnect reconciliation window.
 *   5. Expose [sendToVenue] as the production [FixSessionSender] implementation.
 *
 * The [JdbcStoreFactory] uses the same Postgres database as the rest of fix-gateway
 * (passed as raw JDBC properties — QuickFIX/J manages its own JDBC connections via
 * DriverManager). The tables it creates (`sessions`, `messages`) are separate from the
 * Flyway-managed `fix_session_state` table and coexist without conflict.
 */
class QuickfixjFixSessionRunner(
    private val settings: SessionSettings,
    private val inboundFixHandler: InboundFixHandler,
    private val reconciliationCoordinator: SessionReconciliationCoordinator,
) : FixSessionSender, AutoCloseable {

    private val logger = LoggerFactory.getLogger(QuickfixjFixSessionRunner::class.java)

    /** Maps venue (upper-case TargetCompID) → QuickFIX/J SessionID for outbound routing. */
    private val sessionIds: MutableMap<String, SessionID> = ConcurrentHashMap()

    private var initiator: ThreadedSocketInitiator? = null

    fun start(): QuickfixjFixSessionRunner {
        val app = FixGatewayApplication()
        val storeFactory = JdbcStoreFactory(settings)
        val logFactory = SLF4JLogFactory(settings)
        val messageFactory = DefaultMessageFactory()
        initiator = ThreadedSocketInitiator(app, storeFactory, settings, logFactory, messageFactory)
        initiator!!.start()
        logger.info("QuickfixjFixSessionRunner started")
        return this
    }

    override fun close() {
        initiator?.stop(true)
        logger.info("QuickfixjFixSessionRunner stopped")
    }

    override fun send(venue: String, message: Message): SendOutcome {
        val sessionId = sessionIds[venue.uppercase()]
            ?: return SendOutcome.UnknownVenue
        val session = Session.lookupSession(sessionId)
            ?: return SendOutcome.SessionDown
        val sent = session.send(message)
        return if (sent) SendOutcome.Sent else SendOutcome.SessionDown
    }

    private inner class FixGatewayApplication : MessageCracker(), Application {

        override fun onCreate(sessionId: SessionID) {
            logger.info("FIX session created: {}", sessionId)
        }

        override fun onLogon(sessionId: SessionID) {
            val venue = venueFor(sessionId)
            logger.info("FIX session logon: {} venue={}", sessionId, venue)
            sessionIds[venue] = sessionId
            reconciliationCoordinator.onLogon(venue)
        }

        override fun onLogout(sessionId: SessionID) {
            val venue = venueFor(sessionId)
            logger.info("FIX session logout: {} venue={}", sessionId, venue)
            reconciliationCoordinator.onLogout(venue)
            sessionIds.remove(venue)
        }

        override fun toAdmin(message: Message, sessionId: SessionID) {
            // Admin messages (Logon, Logout, Heartbeat, TestRequest) sent by QuickFIX/J.
        }

        override fun fromAdmin(message: Message, sessionId: SessionID) {
            // Heartbeats, TestRequests — no-op; QuickFIX/J handles responses automatically.
        }

        override fun toApp(message: Message, sessionId: SessionID) {
            // Opportunity to log outbound app messages; no-op for now.
        }

        @Throws(FieldNotFound::class, IncorrectDataFormat::class, IncorrectTagValue::class, UnsupportedMessageType::class)
        override fun fromApp(message: Message, sessionId: SessionID) {
            val rawMessage = message.toString().replace('', '|')
            val fixVersion = sessionId.beginString
            val venue = venueFor(sessionId)
            try {
                runBlocking {
                    inboundFixHandler.handle(rawMessage, sessionId.toString(), fixVersion)
                }
            } catch (e: Exception) {
                // Log the failure. Note: QuickFIX/J advances the inbound seq regardless of
                // exceptions thrown from fromApp. The durability contract in production relies
                // on the venue's cancel-on-disconnect / replay-on-logon policy: if fix-gateway
                // restarts before successfully publishing, the venue re-sends on the next logon.
                logger.error(
                    "Inbound FIX message processing failed for venue={}: {}",
                    venue, e.message, e,
                )
            }
        }

        private fun venueFor(sessionId: SessionID): String = sessionId.targetCompID.uppercase()
    }

    companion object {
        /**
         * Build [SessionSettings] for a single venue session pointing at [host]:[port].
         * Used by both production wiring and the in-memory test fixture.
         *
         * @param jdbcUrl      JDBC URL for JdbcStoreFactory.
         * @param jdbcUser     JDBC user.
         * @param jdbcPassword JDBC password.
         * @param senderCompId Our SenderCompID (e.g. "KINETIX").
         * @param targetCompId The venue's TargetCompID (e.g. "NYSE").
         * @param host         Host of the acceptor.
         * @param port         Port of the acceptor.
         * @param resetOnLogon Whether to reset sequence numbers on logon (false in production).
         */
        fun buildSettings(
            jdbcUrl: String,
            jdbcUser: String,
            jdbcPassword: String,
            senderCompId: String,
            targetCompId: String,
            host: String,
            port: Int,
            resetOnLogon: Boolean = false,
        ): SessionSettings {
            val cfg = buildString {
                appendLine("[DEFAULT]")
                appendLine("ConnectionType=initiator")
                appendLine("ReconnectInterval=2")
                appendLine("StartTime=00:00:00")
                appendLine("EndTime=00:00:00")
                appendLine("UseDataDictionary=N")
                appendLine("ValidateUserDefinedFields=N")
                appendLine("ValidateIncomingMessage=N")
                appendLine("FileStorePath=target/quickfixj")
                // JdbcStore settings
                appendLine("JdbcDriver=org.postgresql.Driver")
                appendLine("JdbcURL=$jdbcUrl")
                appendLine("JdbcUser=$jdbcUser")
                appendLine("JdbcPassword=$jdbcPassword")
                appendLine("[SESSION]")
                appendLine("BeginString=FIX.4.4")
                appendLine("SenderCompID=$senderCompId")
                appendLine("TargetCompID=$targetCompId")
                appendLine("SocketConnectHost=$host")
                appendLine("SocketConnectPort=$port")
                appendLine("HeartBtInt=30")
                if (resetOnLogon) appendLine("ResetOnLogon=Y")
            }
            return SessionSettings(ByteArrayInputStream(cfg.toByteArray()))
        }

        /**
         * Build settings for the in-memory acceptor side.
         * The acceptor uses MemoryStoreFactory so no JDBC params are needed.
         */
        fun buildAcceptorSettings(
            senderCompId: String,
            targetCompId: String,
            port: Int,
        ): SessionSettings {
            val cfg = buildString {
                appendLine("[DEFAULT]")
                appendLine("ConnectionType=acceptor")
                appendLine("StartTime=00:00:00")
                appendLine("EndTime=00:00:00")
                appendLine("UseDataDictionary=N")
                appendLine("ValidateUserDefinedFields=N")
                appendLine("ValidateIncomingMessage=N")
                appendLine("[SESSION]")
                appendLine("BeginString=FIX.4.4")
                appendLine("SenderCompID=$senderCompId")
                appendLine("TargetCompID=$targetCompId")
                appendLine("SocketAcceptPort=$port")
            }
            return SessionSettings(ByteArrayInputStream(cfg.toByteArray()))
        }
    }
}
