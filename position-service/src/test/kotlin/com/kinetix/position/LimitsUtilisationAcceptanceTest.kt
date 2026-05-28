package com.kinetix.position

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.instrument.InstrumentTypeCode
import com.kinetix.position.model.BookHierarchyMapping
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedBookHierarchyRepository
import com.kinetix.position.persistence.ExposedLimitDefinitionRepository
import com.kinetix.position.persistence.ExposedPositionRepository
import com.kinetix.position.persistence.ExposedTemporaryLimitIncreaseRepository
import com.kinetix.position.routes.LimitDefinitionResponse
import com.kinetix.position.routes.limitRoutes
import com.kinetix.position.service.PositionBasedLimitUsageProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import kotlin.math.abs
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.util.Currency

private val USD: Currency = Currency.getInstance("USD")

/**
 * Trader-review P0: the Limits screen has historically shown `—` in the
 * Intraday / Overnight cells for almost every row, because the upstream
 * payload only carried the ceiling value. The trader needs to see "how
 * close to the wall" — current value + utilisation %.
 *
 * This test pins the position-service contribution: GET /api/v1/limits
 * populates `current` and `utilisationPct` for the limit types we can
 * compute from the position book (NOTIONAL, POSITION), at the entity
 * scopes we can resolve (FIRM, DESK, BOOK). Other limit types
 * (VAR, CONCENTRATION, ADV_CONCENTRATION, VAR_BUDGET) and scopes
 * (DIVISION, TRADER, COUNTERPARTY) must return null for both fields so
 * the UI renders em-dash instead of a misleading "$0 (0%)" figure.
 *
 * Real Postgres via Testcontainers — no mocked repositories, no
 * in-memory fakes, in line with the CLAUDE.md acceptance-test rules.
 */
private fun Application.configureUtilisationTestApp(
    limitRepo: ExposedLimitDefinitionRepository,
    temporaryIncreaseRepo: ExposedTemporaryLimitIncreaseRepository,
    usageProvider: PositionBasedLimitUsageProvider,
) {
    install(ContentNegotiation) { json() }
    routing {
        limitRoutes(limitRepo, temporaryIncreaseRepo, usageProvider)
    }
}

class LimitsUtilisationAcceptanceTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val limitRepo = ExposedLimitDefinitionRepository(db)
    val temporaryIncreaseRepo = ExposedTemporaryLimitIncreaseRepository(db)
    val positionRepo = ExposedPositionRepository(db)
    val bookHierarchyRepo = ExposedBookHierarchyRepository(db)
    val usageProvider = PositionBasedLimitUsageProvider(positionRepo, bookHierarchyRepo)

    fun seedPosition(
        bookId: String,
        instrumentId: String,
        quantity: BigDecimal,
        marketPrice: BigDecimal,
    ) {
        val price = Money(marketPrice, USD)
        val position = Position(
            bookId = BookId(bookId),
            instrumentId = InstrumentId(instrumentId),
            assetClass = AssetClass.EQUITY,
            quantity = quantity,
            averageCost = price,
            marketPrice = price,
            realizedPnl = Money.zero(USD),
            instrumentType = InstrumentTypeCode.CASH_EQUITY,
        )
        kotlinx.coroutines.runBlocking { positionRepo.save(position) }
    }

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec(
                """TRUNCATE TABLE
                    limit_temporary_increases,
                    limit_definitions,
                    positions,
                    book_hierarchy
                    RESTART IDENTITY CASCADE""",
            )
        }
    }

    test("FIRM NOTIONAL limit returns current = sum of position marketValue and utilisationPct = current / intradayLimit") {
        // Seed: two books, one position each. Sum of |marketValue| = 640,000,000.
        // Limit: FIRM NOTIONAL, intraday ceiling 800,000,000 → utilisation 80%.
        seedPosition(
            bookId = "equity-growth",
            instrumentId = "AAPL",
            quantity = BigDecimal("2000000"),
            marketPrice = BigDecimal("200"),
        )
        seedPosition(
            bookId = "equity-value",
            instrumentId = "MSFT",
            quantity = BigDecimal("1200000"),
            marketPrice = BigDecimal("200"),
        )
        limitRepo.save(
            com.kinetix.position.model.LimitDefinition(
                id = "firm-notional",
                level = com.kinetix.position.model.LimitLevel.FIRM,
                entityId = "FIRM",
                limitType = com.kinetix.position.model.LimitType.NOTIONAL,
                limitValue = BigDecimal("800000000"),
                intradayLimit = BigDecimal("800000000"),
                overnightLimit = null,
                active = true,
            ),
        )

        testApplication {
            application { configureUtilisationTestApp(limitRepo, temporaryIncreaseRepo, usageProvider) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<List<LimitDefinitionResponse>>(response.bodyAsText())
            val row = body.single { it.id == "firm-notional" }
            row.current shouldBe "640000000"
            row.utilisationPct.shouldNotBeNull()
            abs(row.utilisationPct!! - 80.0) shouldBeLessThanOrEqualTo 0.01
        }
    }

    test("BOOK NOTIONAL limit returns current scoped to the single book and utilisationPct against its intraday ceiling") {
        // Seed: two books — the BOOK limit covers only `equity-growth`.
        // marketValue(equity-growth) = 2,000,000 × 200 = 400,000,000. Intraday
        // ceiling 500,000,000 → utilisation 80%.
        seedPosition(
            bookId = "equity-growth",
            instrumentId = "AAPL",
            quantity = BigDecimal("2000000"),
            marketPrice = BigDecimal("200"),
        )
        seedPosition(
            bookId = "equity-value",
            instrumentId = "MSFT",
            quantity = BigDecimal("999"),
            marketPrice = BigDecimal("1"),
        )
        limitRepo.save(
            com.kinetix.position.model.LimitDefinition(
                id = "book-eq-growth-notional",
                level = com.kinetix.position.model.LimitLevel.BOOK,
                entityId = "equity-growth",
                limitType = com.kinetix.position.model.LimitType.NOTIONAL,
                limitValue = BigDecimal("500000000"),
                intradayLimit = BigDecimal("500000000"),
                overnightLimit = BigDecimal("400000000"),
                active = true,
            ),
        )

        testApplication {
            application { configureUtilisationTestApp(limitRepo, temporaryIncreaseRepo, usageProvider) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<List<LimitDefinitionResponse>>(response.bodyAsText())
            val row = body.single { it.id == "book-eq-growth-notional" }
            row.current shouldBe "400000000"
            row.utilisationPct.shouldNotBeNull()
            abs(row.utilisationPct!! - 80.0) shouldBeLessThanOrEqualTo 0.01
        }
    }

    test("DESK NOTIONAL limit aggregates positions across every book mapped to that desk") {
        // Book hierarchy: equity-growth + equity-value → desk-eq. Sum
        // |marketValue| = 100,000,000 + 200,000,000 = 300,000,000. Intraday
        // ceiling 500,000,000 → utilisation 60%.
        bookHierarchyRepo.save(
            BookHierarchyMapping(
                bookId = "equity-growth",
                deskId = "desk-eq",
                bookName = "Equity Growth",
                bookType = "TRADING",
            ),
        )
        bookHierarchyRepo.save(
            BookHierarchyMapping(
                bookId = "equity-value",
                deskId = "desk-eq",
                bookName = "Equity Value",
                bookType = "TRADING",
            ),
        )
        seedPosition(
            bookId = "equity-growth",
            instrumentId = "AAPL",
            quantity = BigDecimal("500000"),
            marketPrice = BigDecimal("200"),
        )
        seedPosition(
            bookId = "equity-value",
            instrumentId = "MSFT",
            quantity = BigDecimal("1000000"),
            marketPrice = BigDecimal("200"),
        )
        limitRepo.save(
            com.kinetix.position.model.LimitDefinition(
                id = "desk-eq-notional",
                level = com.kinetix.position.model.LimitLevel.DESK,
                entityId = "desk-eq",
                limitType = com.kinetix.position.model.LimitType.NOTIONAL,
                limitValue = BigDecimal("500000000"),
                intradayLimit = BigDecimal("500000000"),
                overnightLimit = null,
                active = true,
            ),
        )

        testApplication {
            application { configureUtilisationTestApp(limitRepo, temporaryIncreaseRepo, usageProvider) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<List<LimitDefinitionResponse>>(response.bodyAsText())
            val row = body.single { it.id == "desk-eq-notional" }
            row.current shouldBe "300000000"
            row.utilisationPct.shouldNotBeNull()
            abs(row.utilisationPct!! - 60.0) shouldBeLessThanOrEqualTo 0.01
        }
    }

    test("FIRM VAR limit returns current = null and utilisationPct = null because position-service has no VaR source") {
        // VAR can't be computed from positions alone — risk-engine output
        // doesn't live here. The route must emit explicit nulls so the UI
        // renders em-dash rather than a misleading "$0 (0%)".
        seedPosition(
            bookId = "equity-growth",
            instrumentId = "AAPL",
            quantity = BigDecimal("1000000"),
            marketPrice = BigDecimal("200"),
        )
        limitRepo.save(
            com.kinetix.position.model.LimitDefinition(
                id = "firm-var",
                level = com.kinetix.position.model.LimitLevel.FIRM,
                entityId = "FIRM",
                limitType = com.kinetix.position.model.LimitType.VAR,
                limitValue = BigDecimal("5000000"),
                intradayLimit = null,
                overnightLimit = null,
                active = true,
            ),
        )

        testApplication {
            application { configureUtilisationTestApp(limitRepo, temporaryIncreaseRepo, usageProvider) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<List<LimitDefinitionResponse>>(response.bodyAsText())
            val row = body.single { it.id == "firm-var" }
            row.current.shouldBeNull()
            row.utilisationPct.shouldBeNull()
        }
    }

    test("FIRM POSITION limit returns current = sum of |quantity| across all books") {
        // POSITION limit counts absolute shares, not dollars. Long 1,000,000 +
        // short -500,000 = 1,500,000 |quantity|. Limit 10,000,000 → 15%.
        seedPosition(
            bookId = "equity-growth",
            instrumentId = "AAPL",
            quantity = BigDecimal("1000000"),
            marketPrice = BigDecimal("200"),
        )
        seedPosition(
            bookId = "equity-value",
            instrumentId = "MSFT",
            quantity = BigDecimal("-500000"),
            marketPrice = BigDecimal("400"),
        )
        limitRepo.save(
            com.kinetix.position.model.LimitDefinition(
                id = "firm-position",
                level = com.kinetix.position.model.LimitLevel.FIRM,
                entityId = "FIRM",
                limitType = com.kinetix.position.model.LimitType.POSITION,
                limitValue = BigDecimal("10000000"),
                intradayLimit = BigDecimal("10000000"),
                overnightLimit = null,
                active = true,
            ),
        )

        testApplication {
            application { configureUtilisationTestApp(limitRepo, temporaryIncreaseRepo, usageProvider) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<List<LimitDefinitionResponse>>(response.bodyAsText())
            val row = body.single { it.id == "firm-position" }
            row.current shouldBe "1500000"
            row.utilisationPct.shouldNotBeNull()
            abs(row.utilisationPct!! - 15.0) shouldBeLessThanOrEqualTo 0.01
        }
    }

    test("limit with no positions in scope returns current = 0 and utilisationPct = 0") {
        // No positions seeded — but the limit must still surface, with a 0
        // utilisation rather than null. (Null is reserved for "we can't
        // compute usage for this row".)
        limitRepo.save(
            com.kinetix.position.model.LimitDefinition(
                id = "empty-firm-notional",
                level = com.kinetix.position.model.LimitLevel.FIRM,
                entityId = "FIRM",
                limitType = com.kinetix.position.model.LimitType.NOTIONAL,
                limitValue = BigDecimal("800000000"),
                intradayLimit = BigDecimal("800000000"),
                overnightLimit = null,
                active = true,
            ),
        )

        testApplication {
            application { configureUtilisationTestApp(limitRepo, temporaryIncreaseRepo, usageProvider) }

            val response = client.get("/api/v1/limits")
            response.status shouldBe HttpStatusCode.OK

            val body = Json.decodeFromString<List<LimitDefinitionResponse>>(response.bodyAsText())
            val row = body.single { it.id == "empty-firm-notional" }
            row.current shouldBe "0"
            row.utilisationPct.shouldNotBeNull()
            abs(row.utilisationPct!! - 0.0) shouldBeLessThanOrEqualTo 0.01
        }
    }
})
