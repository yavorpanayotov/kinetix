package com.kinetix.position.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class TradeEventImmutabilityIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE trade_events RESTART IDENTITY CASCADE")
        }
    }

    fun insertTradeEvent(tradeId: String, traderId: String? = null) {
        val traderIdLiteral = traderId?.let { "'$it'" } ?: "NULL"
        transaction(db) {
            exec(
                """
                INSERT INTO trade_events (
                    trade_id, book_id, instrument_id, asset_class, side,
                    quantity, price_amount, price_currency, traded_at, created_at,
                    event_type, status, instrument_type, trader_id
                ) VALUES (
                    '$tradeId', 'port-1', 'AAPL', 'EQUITY', 'BUY',
                    100.00, 150.00, 'USD',
                    '2025-01-15T10:00:00Z', '2025-01-15T10:00:00Z',
                    'NEW', 'LIVE', 'CASH_EQUITY', $traderIdLiteral
                )
                """.trimIndent()
            )
        }
    }

    test("rejects update to core trade fields") {
        insertTradeEvent("immut-1")

        val ex = shouldThrow<Exception> {
            transaction(db) {
                exec("UPDATE trade_events SET quantity = 999 WHERE trade_id = 'immut-1'")
            }
        }
        val psql = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
        val message = psql?.message ?: ex.message ?: ""
        message shouldContain "immutable"
    }

    test("rejects update to instrument_type") {
        insertTradeEvent("immut-itype")

        val ex = shouldThrow<Exception> {
            transaction(db) {
                exec("UPDATE trade_events SET instrument_type = 'EQUITY_OPTION' WHERE trade_id = 'immut-itype'")
            }
        }
        val psql = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
        val message = psql?.message ?: ex.message ?: ""
        message shouldContain "immutable"
    }

    test("rejects update to trader_id") {
        insertTradeEvent("immut-trader", traderId = "trader-1")

        val ex = shouldThrow<Exception> {
            transaction(db) {
                exec("UPDATE trade_events SET trader_id = 'trader-2' WHERE trade_id = 'immut-trader'")
            }
        }
        val psql = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
        val message = psql?.message ?: ex.message ?: ""
        message shouldContain "immutable"
    }

    test("allows update to status column") {
        insertTradeEvent("immut-2")

        transaction(db) {
            exec("UPDATE trade_events SET status = 'ARCHIVED' WHERE trade_id = 'immut-2'")
        }

        var status: String? = null
        transaction(db) {
            exec("SELECT status FROM trade_events WHERE trade_id = 'immut-2'") { rs ->
                if (rs.next()) status = rs.getString("status")
            }
        }
        status shouldBe "ARCHIVED"
    }

    test("rejects deletion of trade events") {
        insertTradeEvent("immut-3")

        val ex = shouldThrow<Exception> {
            transaction(db) {
                exec("DELETE FROM trade_events WHERE trade_id = 'immut-3'")
            }
        }
        val psql = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
        val message = psql?.message ?: ex.message ?: ""
        message shouldContain "cannot be deleted"
    }
})
