package com.kinetix.fix.testing

import com.kinetix.fix.session.InboundFixHandler
import com.kinetix.fix.session.QuickfixjFixSessionRunner
import com.kinetix.fix.session.SessionReconciliationCoordinator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import quickfix.Application
import quickfix.DefaultMessageFactory
import quickfix.FieldNotFound
import quickfix.IncorrectDataFormat
import quickfix.IncorrectTagValue
import quickfix.JdbcStoreFactory
import quickfix.MemoryStoreFactory
import quickfix.Message
import quickfix.Session
import quickfix.SessionID
import quickfix.SLF4JLogFactory
import quickfix.ThreadedSocketAcceptor
import quickfix.ThreadedSocketInitiator
import quickfix.UnsupportedMessageType
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test fixture that boots a QuickFIX/J `ThreadedSocketAcceptor` (acting as the venue) on a
 * random localhost port, and a `ThreadedSocketInitiator` (acting as fix-gateway) pointed at
 * that acceptor.
 *
 * Acceptor:  MemoryStoreFactory (no persistence required on the venue side)
 * Initiator: JdbcStoreFactory   (Postgres-backed, same contract as production)
 *
 * Exposes:
 *   - [awaitLogon]                    — wait until the session is established
 *   - [sendInbound]                   — inject a raw FIX message from the "venue" side
 *   - [disconnect]                    — drop the TCP connection (simulates venue disconnect)
 *   - [messagesReceivedByAcceptor]    — messages sent FROM the initiator TO the acceptor
 *   - [restartInitiator]              — tear down and re-boot the initiator (simulates restart)
 *   - [resetLogonLatch]               — re-arm so [awaitLogon] works after a disconnect cycle
 *
 * The [inboundFixHandler] is wired into the initiator's `fromApp` callback so published
 * events flow exactly as in production. The [reconciliationCoordinator] is called on
 * logon/logout so state transitions are exercised under test conditions.
 */
class InMemoryFixCounterpartyFixture(
    private val jdbcUrl: String,
    private val jdbcUser: String,
    private val jdbcPassword: String,
    private val inboundFixHandler: InboundFixHandler,
    private val reconciliationCoordinator: SessionReconciliationCoordinator,
    private val senderCompId: String = "KINETIX",
    private val targetCompId: String = "NYSE",
    /** Reset sequence numbers on logon — true for tests so reconnect starts fresh. */
    private val resetOnLogon: Boolean = true,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(InMemoryFixCounterpartyFixture::class.java)

    val port: Int = findFreePort()

    private var acceptor: ThreadedSocketAcceptor? = null
    private var initiator: ThreadedSocketInitiator? = null
    private var initiatorDataSource: HikariDataSource? = null

    /** Messages received by the acceptor FROM the initiator (e.g. 35=D, 35=F, 35=H). */
    val messagesReceivedByAcceptor: MutableList<Message> = CopyOnWriteArrayList()

    @Volatile
    private var logonLatch = CountDownLatch(1)

    @Volatile
    private var acceptorSessionId: SessionID? = null

    /** Start the acceptor; call [startInitiator] separately if needed, or use [start]. */
    fun start(): InMemoryFixCounterpartyFixture {
        runMigrations()
        startAcceptor()
        startInitiator()
        return this
    }

    private fun runMigrations() {
        Flyway.configure()
            .dataSource(jdbcUrl, jdbcUser, jdbcPassword)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    /** Wait until the initiator establishes a session with the acceptor. */
    fun awaitLogon(timeoutMs: Long = 10_000): Boolean =
        logonLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Inject a FIX message from the acceptor (venue) side to the initiator.
     * QuickFIX/J handles session-layer framing (BeginString, BodyLength, CheckSum, MsgSeqNum).
     */
    fun sendInbound(message: Message): Boolean {
        val sessionId = acceptorSessionId ?: error("No active session — call awaitLogon() first")
        val session = Session.lookupSession(sessionId) ?: error("Acceptor session not found: $sessionId")
        return session.send(message)
    }

    /**
     * Re-arm the logon latch so [awaitLogon] can be called again after a
     * disconnect/reconnect cycle.
     */
    fun resetLogonLatch() {
        logonLatch = CountDownLatch(1)
    }

    /**
     * Drop the TCP connection from the acceptor side. The initiator's reconnect logic fires
     * automatically. Use [resetLogonLatch] + [awaitLogon] to synchronise with reconnect.
     */
    fun disconnect() {
        val sessionId = acceptorSessionId ?: return
        Session.lookupSession(sessionId)?.disconnect("Test-induced disconnect", false)
    }

    /**
     * Tear down the initiator without stopping the acceptor. Re-creates the initiator with
     * Postgres seq-num state intact. Use this to simulate a "service restart" while the
     * Testcontainers Postgres and Kafka remain live.
     */
    fun restartInitiator() {
        initiator?.stop(true)
        initiator = null
        initiatorDataSource?.close()
        initiatorDataSource = null
        startInitiator()
    }

    override fun close() {
        initiator?.stop(true)
        initiatorDataSource?.close()
        initiatorDataSource = null
        acceptor?.stop(true)
        logger.info("InMemoryFixCounterpartyFixture closed")
    }

    // -------------------------------------------------------------------------

    private fun startAcceptor() {
        val settings = QuickfixjFixSessionRunner.buildAcceptorSettings(
            senderCompId = targetCompId,   // acceptor's sender = initiator's target
            targetCompId = senderCompId,   // acceptor's target = initiator's sender
            port = port,
        )
        val storeFactory = MemoryStoreFactory()
        val logFactory = SLF4JLogFactory(settings)
        val messageFactory = DefaultMessageFactory()
        acceptor = ThreadedSocketAcceptor(AcceptorApplication(), storeFactory, settings, logFactory, messageFactory)
        acceptor!!.start()
        logger.info("In-memory FIX acceptor started on port {}", port)
    }

    private fun startInitiator() {
        val settings = QuickfixjFixSessionRunner.buildSettings(
            jdbcUrl = jdbcUrl,
            jdbcUser = jdbcUser,
            jdbcPassword = jdbcPassword,
            senderCompId = senderCompId,
            targetCompId = targetCompId,
            host = "localhost",
            port = port,
            resetOnLogon = resetOnLogon,
        )
        val dataSource = HikariDataSource(
            HikariConfig().also { cfg ->
                cfg.jdbcUrl = jdbcUrl
                cfg.username = jdbcUser
                cfg.password = jdbcPassword
                cfg.maximumPoolSize = 4
            },
        )
        initiatorDataSource = dataSource
        val storeFactory = JdbcStoreFactory(settings).apply { setDataSource(dataSource) }
        val logFactory = SLF4JLogFactory(settings)
        val messageFactory = DefaultMessageFactory()
        initiator = ThreadedSocketInitiator(InitiatorApplication(), storeFactory, settings, logFactory, messageFactory)
        initiator!!.start()
        logger.info("In-memory FIX initiator started, connecting to port {}", port)
    }

    private inner class AcceptorApplication : Application {

        override fun onCreate(sessionId: SessionID) {}

        override fun onLogon(sessionId: SessionID) {
            acceptorSessionId = sessionId
            logger.info("Acceptor: session logon {}", sessionId)
        }

        override fun onLogout(sessionId: SessionID) {
            logger.info("Acceptor: session logout {}", sessionId)
        }

        override fun toAdmin(message: Message, sessionId: SessionID) {}

        override fun fromAdmin(message: Message, sessionId: SessionID) {}

        override fun toApp(message: Message, sessionId: SessionID) {}

        @Throws(FieldNotFound::class, IncorrectDataFormat::class, IncorrectTagValue::class, UnsupportedMessageType::class)
        override fun fromApp(message: Message, sessionId: SessionID) {
            messagesReceivedByAcceptor.add(message)
            logger.info(
                "Acceptor received: msgType={} from={}",
                message.header.getString(35),
                sessionId,
            )
        }
    }

    private inner class InitiatorApplication : Application {

        override fun onCreate(sessionId: SessionID) {}

        override fun onLogon(sessionId: SessionID) {
            val venue = sessionId.targetCompID.uppercase()
            logger.info("Initiator: session logon {} venue={}", sessionId, venue)
            reconciliationCoordinator.onLogon(venue)
            logonLatch.countDown()
        }

        override fun onLogout(sessionId: SessionID) {
            val venue = sessionId.targetCompID.uppercase()
            logger.info("Initiator: session logout {} venue={}", sessionId, venue)
            reconciliationCoordinator.onLogout(venue)
        }

        override fun toAdmin(message: Message, sessionId: SessionID) {}

        override fun fromAdmin(message: Message, sessionId: SessionID) {}

        override fun toApp(message: Message, sessionId: SessionID) {}

        @Throws(FieldNotFound::class, IncorrectDataFormat::class, IncorrectTagValue::class, UnsupportedMessageType::class)
        override fun fromApp(message: Message, sessionId: SessionID) {
            val soh = Char(1)
            val rawMessage = message.toString().replace(soh, '|')
            val fixVersion = sessionId.beginString
            try {
                runBlocking {
                    inboundFixHandler.handle(rawMessage, sessionId.toString(), fixVersion)
                }
            } catch (e: Exception) {
                // In tests we deliberately observe the behaviour without aborting the session.
                // The durability test's one-shot spy records the failure and the test asserts
                // exactly one event on Kafka across the two attempts.
                logger.warn("Initiator fromApp: handler error (test-observed): {}", e.message)
            }
        }
    }

    companion object {
        private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
