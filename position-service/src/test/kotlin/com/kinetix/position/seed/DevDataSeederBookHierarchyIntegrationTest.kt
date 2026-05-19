package com.kinetix.position.seed

import com.kinetix.common.model.BookId
import com.kinetix.common.model.Position
import com.kinetix.position.fix.ExecutionCostRepository
import com.kinetix.position.persistence.DatabaseTestSetup
import com.kinetix.position.persistence.ExposedBookHierarchyRepository
import com.kinetix.position.persistence.PositionRepository
import com.kinetix.position.service.BookTradeCommand
import com.kinetix.position.service.BookTradeResult
import com.kinetix.position.service.TradeBookingService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Integration test for the book-hierarchy seed path that drives the firm
 * aggregation bug. Uses the real `ExposedBookHierarchyRepository` against the
 * Postgres Testcontainer so we exercise the SQL upsert, not a mock.
 *
 * The remaining `DevDataSeeder` collaborators (trade booking, position repo,
 * execution-cost repo) are mocked because they sit outside the unit under
 * test — the goal here is to assert that `DevDataSeeder.seed()` populates
 * `book_hierarchy` with all 8 expected mappings end-to-end. The seeder's
 * book-hierarchy branch is independent of the trade-booking path, so this
 * narrow harness is sufficient and keeps the test focused.
 *
 * Marked `*IntegrationTest` (not `*AcceptanceTest`) per the project's
 * acceptance-compliance gate, which forbids `mockk<*Repository>` etc., and
 * because the surrounding collaborators here are unavoidably mocked.
 */
class DevDataSeederBookHierarchyIntegrationTest : FunSpec({

    val db = DatabaseTestSetup.startAndMigrate()
    val bookHierarchyRepo = ExposedBookHierarchyRepository(db)

    val tradeBookingService = mockk<TradeBookingService>()
    val positionRepository = mockk<PositionRepository>()
    val executionCostRepo = mockk<ExecutionCostRepository>()

    beforeEach {
        newSuspendedTransaction(db = db) {
            exec("TRUNCATE TABLE book_hierarchy RESTART IDENTITY CASCADE")
        }
        coEvery { tradeBookingService.handle(any()) } answers {
            val cmd = firstArg<BookTradeCommand>()
            BookTradeResult(
                trade = com.kinetix.common.model.Trade(
                    tradeId = cmd.tradeId,
                    bookId = cmd.bookId,
                    instrumentId = cmd.instrumentId,
                    assetClass = cmd.assetClass,
                    side = cmd.side,
                    quantity = cmd.quantity,
                    price = cmd.price,
                    tradedAt = cmd.tradedAt,
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.CASH_EQUITY,
                ),
                position = Position(
                    bookId = cmd.bookId,
                    instrumentId = cmd.instrumentId,
                    assetClass = cmd.assetClass,
                    quantity = java.math.BigDecimal.ZERO,
                    averageCost = com.kinetix.common.model.Money.zero(cmd.price.currency),
                    marketPrice = com.kinetix.common.model.Money.zero(cmd.price.currency),
                    instrumentType = com.kinetix.common.model.instrument.InstrumentTypeCode.fromString(cmd.instrumentType),
                ),
            )
        }
        coEvery { positionRepository.findByKey(any(), any()) } returns null
        coEvery { positionRepository.save(any()) } just runs
        // Skip the trade-booking path — we only care about the book-hierarchy
        // branch in this test.
        coEvery { positionRepository.findDistinctBookIds() } returns listOf(BookId("equity-growth"))
        coEvery { executionCostRepo.findByBookId(any()) } returns listOf(DevDataSeeder.EXECUTION_COSTS.first())
        coEvery { executionCostRepo.save(any()) } just runs
    }

    test("DevDataSeeder.seed() populates book_hierarchy with all 8 demo book→desk mappings") {
        val seeder = DevDataSeeder(
            tradeBookingService = tradeBookingService,
            positionRepository = positionRepository,
            executionCostRepo = executionCostRepo,
            bookHierarchyRepository = bookHierarchyRepo,
        )

        bookHierarchyRepo.findAll() shouldBe emptyList()

        seeder.seed()

        val persisted = bookHierarchyRepo.findAll().sortedBy { it.bookId }
        persisted.size shouldBe 8

        val byBook = persisted.associateBy { it.bookId }
        byBook["balanced-income"]!!.deskId shouldBe "balanced-income"
        byBook["derivatives-book"]!!.deskId shouldBe "derivatives-trading"
        byBook["emerging-markets"]!!.deskId shouldBe "emerging-markets"
        byBook["equity-growth"]!!.deskId shouldBe "equity-growth"
        byBook["fixed-income"]!!.deskId shouldBe "rates-trading"
        byBook["macro-hedge"]!!.deskId shouldBe "macro-hedge"
        byBook["multi-asset"]!!.deskId shouldBe "multi-asset-strategies"
        byBook["tech-momentum"]!!.deskId shouldBe "tech-momentum"
    }

    test("DevDataSeeder.seed() is idempotent: re-seeding when mappings already exist is a no-op") {
        val seeder = DevDataSeeder(
            tradeBookingService = tradeBookingService,
            positionRepository = positionRepository,
            executionCostRepo = executionCostRepo,
            bookHierarchyRepository = bookHierarchyRepo,
        )

        seeder.seed()
        val firstPass = bookHierarchyRepo.findAll().sortedBy { it.bookId }
        firstPass.size shouldBe 8

        seeder.seed()
        val secondPass = bookHierarchyRepo.findAll().sortedBy { it.bookId }
        secondPass.size shouldBe 8
        secondPass.map { it.bookId } shouldBe firstPass.map { it.bookId }
    }
})
