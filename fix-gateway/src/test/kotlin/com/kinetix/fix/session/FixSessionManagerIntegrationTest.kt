package com.kinetix.fix.session

import com.kinetix.fix.persistence.DatabaseConfig
import com.kinetix.fix.persistence.DatabaseFactory
import com.kinetix.fix.venue.VenueSessionRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Plan 2.12 — FixSessionManagerTest. Cold start + corrupt row branches both
 * gate boot-time recovery; the seq-behind / seq-ahead / GapFill branches
 * require an active QuickFIX/J Initiator + in-memory acceptor and land in
 * the follow-on advanced acceptance suite.
 */
class FixSessionManagerIntegrationTest : FunSpec({

    val postgres = PostgreSQLContainer(
        DockerImageName.parse("timescale/timescaledb:latest-pg17")
            .asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("fix_gateway_test")
        .withUsername("test")
        .withPassword("test")

    beforeSpec { postgres.start() }
    afterSpec { postgres.stop() }

    fun freshDb() = DatabaseFactory.init(
        DatabaseConfig(jdbcUrl = postgres.jdbcUrl, username = postgres.username, password = postgres.password),
    )

    beforeEach {
        // Clean state across each test
        val db = freshDb()
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE fix_session_state")
        }
    }

    test("cold start writes seq=1 row and returns it") {
        val db = freshDb()
        val repo = ExposedFixSessionStateRepository(db)
        val manager = FixSessionManager(repo, VenueSessionRegistry())

        val state = manager.recoverOne("NYSE")

        state.senderSeqNum shouldBe 1
        state.targetSeqNum shouldBe 1
        repo.findByVenue("NYSE") shouldNotBe null
    }

    test("healthy row is returned as-is, not overwritten") {
        val db = freshDb()
        val repo = ExposedFixSessionStateRepository(db)
        repo.upsert(FixSessionState("NYSE", senderSeqNum = 50, targetSeqNum = 47, lastLogonAt = null, lastLogoutAt = null))
        val manager = FixSessionManager(repo, VenueSessionRegistry())

        val state = manager.recoverOne("NYSE")

        state.senderSeqNum shouldBe 50
        state.targetSeqNum shouldBe 47
    }

    test("corrupt row throws CorruptSessionStateException — readiness probe fails NOT_READY") {
        val db = freshDb()
        // Drop the CHECK first so we can simulate a row that would never have
        // landed via FixSessionState's init validation (or the column-level
        // CHECK), exercising the defence-in-depth check inside the manager.
        newSuspendedTransaction(db = db) {
            exec("ALTER TABLE fix_session_state DROP CONSTRAINT fix_session_state_sender_seq_num_check")
            exec(
                "INSERT INTO fix_session_state (venue, sender_seq_num, target_seq_num, updated_at) " +
                    "VALUES ('NYSE', 0, 1, now())"
            )
        }

        val repo = ExposedFixSessionStateRepository(db)
        val manager = FixSessionManager(repo, VenueSessionRegistry())

        shouldThrow<CorruptSessionStateException> {
            manager.recoverOne("NYSE")
        }
    }

    test("recoverAll cold-starts every registered launch venue") {
        val db = freshDb()
        val repo = ExposedFixSessionStateRepository(db)
        val manager = FixSessionManager(repo, VenueSessionRegistry())

        val recovered = manager.recoverAll()

        recovered.keys shouldBe setOf("NYSE", "NASDAQ", "LSE", "TSE", "HKEX")
        recovered.values.forEach { state ->
            state.senderSeqNum shouldBe 1
            state.targetSeqNum shouldBe 1
        }
        repo.all().size shouldBe 5
    }
})
