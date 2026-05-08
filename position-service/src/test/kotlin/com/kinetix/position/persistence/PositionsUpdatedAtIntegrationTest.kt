package com.kinetix.position.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.transactions.transaction

class PositionsUpdatedAtIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()

    beforeEach {
        transaction(db) {
            exec("DELETE FROM positions WHERE book_id = 'upd-port-1'")
        }
    }

    test("updated_at is automatically set on update") {
        transaction(db) {
            exec(
                """
                INSERT INTO positions (
                    book_id, instrument_id, asset_class, quantity,
                    avg_cost_amount, market_price_amount, currency, updated_at,
                    realized_pnl_amount, instrument_type
                ) VALUES (
                    'upd-port-1', 'AAPL', 'EQUITY', 100.00,
                    150.00, 155.00, 'USD', '2025-01-01T00:00:00Z',
                    0.00, 'CASH_EQUITY'
                )
                """.trimIndent()
            )
        }

        var updatedAtBefore: String? = null
        transaction(db) {
            exec("SELECT updated_at FROM positions WHERE book_id = 'upd-port-1' AND instrument_id = 'AAPL'") { rs ->
                if (rs.next()) updatedAtBefore = rs.getString("updated_at")
            }
        }

        delay(50)

        transaction(db) {
            exec("UPDATE positions SET quantity = 200.00 WHERE book_id = 'upd-port-1' AND instrument_id = 'AAPL'")
        }

        var updatedAtAfter: String? = null
        transaction(db) {
            exec("SELECT updated_at FROM positions WHERE book_id = 'upd-port-1' AND instrument_id = 'AAPL'") { rs ->
                if (rs.next()) updatedAtAfter = rs.getString("updated_at")
            }
        }

        (updatedAtAfter != updatedAtBefore) shouldBe true
    }
})
