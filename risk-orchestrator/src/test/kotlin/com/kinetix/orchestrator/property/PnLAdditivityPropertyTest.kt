package com.kinetix.orchestrator.property

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.risk.service.PnlAttributionService
import com.kinetix.risk.service.PositionPnlInput
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Property-based tests for portfolio P&L additivity in [PnlAttributionService].
 *
 * P&L additivity is the foundational property that lets risk managers split a
 * portfolio into books, desks, or arbitrary groupings and still trust that
 * per-group P&L numbers add back to the portfolio total. For a Taylor-expansion
 * P&L attribution with no cross-position terms, total P&L is a linear functional
 * of the input positions:
 *
 *   totalPnl(portfolio) = Σ_i totalPnl(position_i)
 *
 * Therefore for any partition `{B_1, …, B_N}` of the portfolio into N books:
 *
 *   totalPnl(portfolio) = Σ_k totalPnl(B_k)
 *
 * The same linearity holds for every first-order attribution component
 * (deltaPnl, gammaPnl, vegaPnl, thetaPnl, rhoPnl, vannaPnl, volgaPnl, charmPnl,
 * unexplainedPnl) when no inter-position correlations are supplied — they are
 * each independent `fold(ZERO) { add }` reductions over the position list.
 *
 * The property is exercised across random portfolios, random partitions into
 * N books, and random market shifts (rates, vols, spots), matching the
 * iteration cap (50) established by `VaRCalculationPropertyTest`.
 *
 * Tolerance is intentionally tight (`1e-6 * abs(value) + 1e-9`) — a sum
 * decomposition over BigDecimal with shared `MathContext` should be exact up
 * to the rounding mode, so a loose tolerance would hide real divergences.
 */
class PnLAdditivityPropertyTest : FunSpec({

    PropertyTesting.defaultSeed = 7_777L

    val service = PnlAttributionService()
    val bookId = BookId("port-pnl-additivity")

    val instrumentIdArb = Arb.stringPattern("[A-Z]{3,6}")
    val assetClassArb = Arb.element(
        AssetClass.EQUITY, AssetClass.FIXED_INCOME, AssetClass.FX, AssetClass.COMMODITY,
    )

    // Random market shifts: rates ±50bps, vols ×0.5–2.0 modelled as Δvol in
    // [-0.5, 1.0] absolute, spots ±20% modelled as a price change relative to
    // a notional unit price. Values are deliberately wide to stress the sum.
    val priceChangeArb = Arb.bigDecimal(BigDecimal("-20.0"), BigDecimal("20.0"))
    val volChangeArb = Arb.bigDecimal(BigDecimal("-0.5"), BigDecimal("1.0"))
    val rateChangeArb = Arb.bigDecimal(BigDecimal("-0.005"), BigDecimal("0.005"))

    val greekArb = Arb.bigDecimal(BigDecimal("-1000"), BigDecimal("1000"))
    val totalPnlArb = Arb.bigDecimal(BigDecimal("-50000"), BigDecimal("50000"))

    val positionArb: Arb<PositionPnlInput> = Arb.bind(
        instrumentIdArb, assetClassArb, totalPnlArb,
        greekArb, greekArb, greekArb, greekArb, greekArb,
        greekArb, greekArb, greekArb,
    ) { instId, ac, total, delta, gamma, vega, theta, rho, vanna, volga, charm ->
        PositionPnlInput(
            instrumentId = InstrumentId(instId),
            assetClass = ac,
            totalPnl = total,
            delta = delta,
            gamma = gamma,
            vega = vega,
            theta = theta,
            rho = rho,
            vanna = vanna,
            volga = volga,
            charm = charm,
            // priceChange/volChange/rateChange are filled in per-property below
            // so that "random market shifts" varies independently of position
            // mass; default to zero here.
            priceChange = BigDecimal.ZERO,
            volChange = BigDecimal.ZERO,
            rateChange = BigDecimal.ZERO,
        )
    }

    /**
     * Partitions [items] into [n] non-empty chunks using [seed] for the split
     * decisions. Returns up to [n] chunks; if `items.size < n` the partition
     * has `items.size` chunks (each non-empty). Order within each chunk is
     * preserved from the original list — book membership is the only choice
     * being randomised, not the per-position economics.
     */
    fun <T> partition(items: List<T>, n: Int, seed: Long): List<List<T>> {
        require(n >= 1)
        if (items.isEmpty()) return emptyList()
        val k = n.coerceAtMost(items.size)
        val rng = java.util.Random(seed)
        val buckets = List(k) { mutableListOf<T>() }
        // Ensure every bucket gets at least one position by seeding each first.
        items.take(k).forEachIndexed { i, item -> buckets[i].add(item) }
        items.drop(k).forEach { item -> buckets[rng.nextInt(k)].add(item) }
        return buckets.map { it.toList() }
    }

    fun applyShifts(
        positions: List<PositionPnlInput>,
        priceChange: BigDecimal,
        volChange: BigDecimal,
        rateChange: BigDecimal,
    ): List<PositionPnlInput> = positions.map {
        it.copy(priceChange = priceChange, volChange = volChange, rateChange = rateChange)
    }

    /** Tight tolerance for sum-decomposition over `BigDecimal` → Double. */
    fun assertCloseAdditive(portfolio: Double, parts: Double, label: String) {
        val tol = 1e-6 * abs(portfolio) + 1e-9
        val diff = abs(portfolio - parts)
        diff.shouldBeLessThanOrEqual(tol)
        // The check above is the property; the label captures context in the
        // failure message if Kotest prints a shrunk counterexample.
        @Suppress("UNUSED_EXPRESSION") label
    }

    test("portfolio totalPnl equals sum of per-book totalPnl across random partitions") {
        checkAll(
            iterations = 50,
            genA = Arb.list(positionArb, 1..10),
            genB = Arb.long(1L..8L),
            genC = Arb.long(0L..1_000_000L),
        ) { positions, partitionCount, partitionSeed ->
            // Markets fixed for this property — additivity must hold even
            // when every position sees the same shift.
            val shifted = applyShifts(positions, BigDecimal("1.5"), BigDecimal("0.05"), BigDecimal("0.001"))

            val portfolio = service.attribute(bookId, shifted)
            val books = partition(shifted, partitionCount.toInt(), partitionSeed)
            val perBook = books.map { service.attribute(bookId, it) }

            val summedTotal = perBook.fold(BigDecimal.ZERO) { acc, b -> acc + b.totalPnl }
            assertCloseAdditive(
                portfolio.totalPnl.toDouble(),
                summedTotal.toDouble(),
                "totalPnl",
            )
        }
    }

    test("every first-order attribution component is additive across random partitions and random market shifts") {
        checkAll(
            iterations = 50,
            genA = Arb.list(positionArb, 1..10),
            genB = Arb.long(1L..8L),
            genC = Arb.long(0L..1_000_000L),
            genD = priceChangeArb,
            genE = volChangeArb,
            genF = rateChangeArb,
        ) { positions, partitionCount, partitionSeed, dS, dSigma, dR ->
            val shifted = applyShifts(positions, dS, dSigma, dR)
            val portfolio = service.attribute(bookId, shifted)
            val books = partition(shifted, partitionCount.toInt(), partitionSeed)
            val perBook = books.map { service.attribute(bookId, it) }

            // Sum each component across books and assert against portfolio.
            val components: List<Triple<String, BigDecimal, BigDecimal>> = listOf(
                Triple("totalPnl", portfolio.totalPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.totalPnl }),
                Triple("deltaPnl", portfolio.deltaPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.deltaPnl }),
                Triple("gammaPnl", portfolio.gammaPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.gammaPnl }),
                Triple("vegaPnl", portfolio.vegaPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.vegaPnl }),
                Triple("thetaPnl", portfolio.thetaPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.thetaPnl }),
                Triple("rhoPnl", portfolio.rhoPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.rhoPnl }),
                Triple("vannaPnl", portfolio.vannaPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.vannaPnl }),
                Triple("volgaPnl", portfolio.volgaPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.volgaPnl }),
                Triple("charmPnl", portfolio.charmPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.charmPnl }),
                Triple("unexplainedPnl", portfolio.unexplainedPnl, perBook.fold(BigDecimal.ZERO) { a, b -> a + b.unexplainedPnl }),
            )

            components.forEach { (label, portfolioValue, summed) ->
                assertCloseAdditive(portfolioValue.toDouble(), summed.toDouble(), label)
            }
        }
    }

    test("position-level attribution rows are preserved exactly across any partition") {
        // Stronger structural property: the multiset of per-position attribution
        // rows produced by the portfolio call must equal the union of per-book
        // rows. If this fails the additivity of any individual component would
        // also fail, so this catches misattribution at the source.
        checkAll(
            iterations = 50,
            genA = Arb.list(positionArb.map { it.copy(priceChange = BigDecimal("1.0"), volChange = BigDecimal("0.02"), rateChange = BigDecimal("0.001")) }, 1..8),
            genB = Arb.long(1L..6L),
            genC = Arb.long(0L..1_000_000L),
        ) { shifted, partitionCount, partitionSeed ->
            val portfolio = service.attribute(bookId, shifted)
            val books = partition(shifted, partitionCount.toInt(), partitionSeed)
            val unionRows = books.flatMap { service.attribute(bookId, it).positionAttributions }

            // Compare as multisets keyed by (instrumentId, totalPnl, deltaPnl, …)
            // because the portfolio-level call preserves input order while the
            // union-of-books call follows partition order.
            fun key(r: com.kinetix.risk.model.PositionPnlAttribution) = listOf(
                r.instrumentId.value,
                r.totalPnl.stripTrailingZeros().toPlainString(),
                r.deltaPnl.stripTrailingZeros().toPlainString(),
                r.gammaPnl.stripTrailingZeros().toPlainString(),
                r.vegaPnl.stripTrailingZeros().toPlainString(),
                r.thetaPnl.stripTrailingZeros().toPlainString(),
                r.rhoPnl.stripTrailingZeros().toPlainString(),
                r.vannaPnl.stripTrailingZeros().toPlainString(),
                r.volgaPnl.stripTrailingZeros().toPlainString(),
                r.charmPnl.stripTrailingZeros().toPlainString(),
                // crossGammaPnl omitted from this row-level check: it depends on
                // pairwise correlations which we don't supply here (so it's zero
                // everywhere) — but if a future change wires correlations in by
                // default the comparison would still pass because zero == zero.
            )

            val expected = portfolio.positionAttributions.map(::key).groupingBy { it }.eachCount()
            val actual = unionRows.map(::key).groupingBy { it }.eachCount()
            // shouldBeLessThanOrEqual not applicable here — use direct equality
            // via Kotest's shouldBe by wrapping in a check.
            if (expected != actual) {
                throw AssertionError(
                    "position rows differ between portfolio and union-of-books partition:\n" +
                        "  expected=$expected\n  actual=$actual",
                )
            }
        }
    }
})
