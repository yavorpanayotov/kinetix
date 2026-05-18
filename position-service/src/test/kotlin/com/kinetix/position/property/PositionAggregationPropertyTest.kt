package com.kinetix.position.property

import com.kinetix.common.model.AssetClass
import com.kinetix.common.model.BookId
import com.kinetix.common.model.InstrumentId
import com.kinetix.common.model.Money
import com.kinetix.common.model.Position
import com.kinetix.common.model.Side
import com.kinetix.common.model.Trade
import com.kinetix.common.model.TradeId
import com.kinetix.common.model.instrument.InstrumentTypeCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Property-based tests for trade-to-position aggregation in
 * [Position.applyTrade].
 *
 * Position aggregation reduces a sequence of trades into a single [Position]
 * via `trades.fold(Position.fromFirstTrade(t0)) { pos, t -> pos.applyTrade(t) }`.
 * This is the kernel of the position-keeping algebra used by
 * `TradeLifecycleService` and the rest of position-service. Two algebraic
 * invariants must hold:
 *
 *  1. **Commutativity** — for any sequence of trades on the same
 *     `(bookId, instrumentId)` key, aggregating them in any order yields the
 *     same net position. We assert this on:
 *       * **net signed quantity**, which is a pure signed sum of
 *         `trade.signedQuantity` and is universally order-invariant for any
 *         mix of BUY/SELL trades;
 *       * **average cost** under same-side (all-BUY) sequences, where
 *         `applyTrade`'s weighted-average branch is symmetric in its inputs
 *         (the closed-form is `Σ p_i q_i / Σ q_i`).
 *
 *  2. **Associativity** — splitting a sequence into N sub-sequences and
 *     aggregating their results (by net signed quantity) equals aggregating
 *     the full sequence. Equivalent to the monoid law for signed addition.
 *
 *  3. **Multi-instrument portfolio aggregation** — for a portfolio of trades
 *     across many `(bookId, instrumentId)` keys, the resulting per-key map of
 *     net signed quantity is invariant under permutation of the full trade
 *     stream.
 *
 * These properties already hold by the algebra; the tests are regression
 * guards that lock them in against accidental future refactors of
 * [Position.applyTrade].
 *
 * No Python sidecar, no I/O, no DB — pure-Kotlin algebra, so iterations are
 * generous (100) without slowing the suite.
 */
class PositionAggregationPropertyTest : FunSpec({

    PropertyTesting.defaultSeed = 7_777L

    val usd = Currency.getInstance("USD")
    val bookId = BookId("port-aggregate")
    val instrumentId = InstrumentId("AAPL")

    // ---- Generators -------------------------------------------------------

    val sideArb: Arb<Side> = Arb.element(Side.BUY, Side.SELL)

    // Quantities and prices kept to plain integers so BigDecimal arithmetic
    // is exact across reorderings (no rounding from MathContext.DECIMAL128
    // can perturb the weighted-average closed-form).
    val intQtyArb: Arb<BigDecimal> = Arb.bigDecimal(BigDecimal("1"), BigDecimal("1000"))
        .map { it.setScale(0, java.math.RoundingMode.HALF_UP) }
    val intPriceArb: Arb<BigDecimal> = Arb.bigDecimal(BigDecimal("10"), BigDecimal("500"))
        .map { it.setScale(0, java.math.RoundingMode.HALF_UP) }

    fun tradeArb(side: Arb<Side> = sideArb): Arb<Trade> = Arb.bind(
        Arb.stringPattern("[a-z0-9]{8}"),
        side,
        intQtyArb,
        intPriceArb,
        Arb.long(0L..1_000_000_000L),
    ) { tid, s, qty, price, micros ->
        Trade(
            tradeId = TradeId(tid),
            bookId = bookId,
            instrumentId = instrumentId,
            assetClass = AssetClass.EQUITY,
            side = s,
            quantity = qty,
            price = Money(price, usd),
            tradedAt = Instant.ofEpochMilli(micros),
            instrumentType = InstrumentTypeCode.CASH_EQUITY,
        )
    }

    val buyTradeArb: Arb<Trade> = tradeArb(Arb.element(Side.BUY))

    // For multi-instrument tests, vary (bookId, instrumentId) per trade.
    val multiKeyTradeArb: Arb<Trade> = Arb.bind(
        Arb.stringPattern("[a-z0-9]{8}"),
        Arb.stringPattern("port-[A-Z]{2}"),
        Arb.stringPattern("[A-Z]{3,5}"),
        sideArb,
        intQtyArb,
        intPriceArb,
        Arb.long(0L..1_000_000_000L),
    ) { tid, book, inst, s, qty, price, micros ->
        Trade(
            tradeId = TradeId(tid),
            bookId = BookId(book),
            instrumentId = InstrumentId(inst),
            assetClass = AssetClass.EQUITY,
            side = s,
            quantity = qty,
            price = Money(price, usd),
            tradedAt = Instant.ofEpochMilli(micros),
            instrumentType = InstrumentTypeCode.CASH_EQUITY,
        )
    }

    // ---- Aggregation helpers ---------------------------------------------

    /**
     * Folds a same-key trade list into a [Position] via the canonical
     * `applyTrade` reduction. Returns null for an empty input (no first
     * trade to seed metadata from).
     */
    fun aggregate(trades: List<Trade>): Position? {
        if (trades.isEmpty()) return null
        val seed = Position.fromFirstTrade(trades.first())
        return trades.fold(seed) { pos, t -> pos.applyTrade(t) }
    }

    /** Per-key net signed quantity across a stream of multi-key trades. */
    fun netByKey(trades: List<Trade>): Map<Pair<BookId, InstrumentId>, BigDecimal> =
        trades.groupBy { it.bookId to it.instrumentId }
            .mapValues { (_, ts) -> ts.fold(BigDecimal.ZERO) { acc, t -> acc + t.signedQuantity } }

    // ---- Properties -------------------------------------------------------

    test("net signed quantity is commutative for any sequence of trades (mixed BUY/SELL)") {
        checkAll(
            iterations = 100,
            genA = Arb.list(tradeArb(), 1..50),
            genB = Arb.long(0L..1_000_000L),
        ) { trades, shuffleSeed ->
            val permuted = trades.shuffled(java.util.Random(shuffleSeed))

            val original = aggregate(trades)!!
            val reordered = aggregate(permuted)!!

            // Compare quantities by value, not BigDecimal.equals (which is
            // scale-sensitive). Net signed quantity is a pure signed sum, so
            // any reordering must yield the identical value.
            original.quantity.compareTo(reordered.quantity) shouldBe 0
        }
    }

    test("net signed quantity is associative across arbitrary partitions") {
        checkAll(
            iterations = 100,
            genA = Arb.list(tradeArb(), 1..50),
            genB = Arb.int(1..8),
            genC = Arb.long(0L..1_000_000L),
        ) { trades, partitionCount, partitionSeed ->
            val full = aggregate(trades)!!.quantity

            val chunks = randomPartition(trades, partitionCount, partitionSeed)
            // Sum of per-chunk net signed quantity must equal the whole.
            val summed = chunks.fold(BigDecimal.ZERO) { acc, sub ->
                acc + sub.fold(BigDecimal.ZERO) { a, t -> a + t.signedQuantity }
            }

            full.compareTo(summed) shouldBe 0
        }
    }

    test("same-side (all-BUY) aggregation is commutative on both quantity and average cost") {
        // For same-direction trades, `applyTrade`'s weighted-average branch is
        // mathematically symmetric: avg = Σ(price_i * qty_i) / Σ(qty_i). So
        // both quantity AND averageCost are order-invariant in exact arithmetic.
        // The implementation runs each step through `divide(…, MathContext.DECIMAL128)`,
        // which rounds at 34 significant digits. Reorderings can therefore
        // produce last-bit rounding differences far below any business-meaningful
        // precision — we assert with a tight relative tolerance to lock in the
        // algebraic property without being fooled by sub-1e-30 rounding noise.
        checkAll(
            iterations = 100,
            genA = Arb.list(buyTradeArb, 1..30),
            genB = Arb.long(0L..1_000_000L),
        ) { trades, shuffleSeed ->
            val permuted = trades.shuffled(java.util.Random(shuffleSeed))

            val original = aggregate(trades)!!
            val reordered = aggregate(permuted)!!

            // Quantity is exact integer arithmetic — strict equality.
            original.quantity.compareTo(reordered.quantity) shouldBe 0
            original.averageCost.currency shouldBe reordered.averageCost.currency
            assertNearlyEqual(original.averageCost.amount, reordered.averageCost.amount)
        }
    }

    test("same-side (all-BUY) aggregation is associative on both quantity and average cost") {
        checkAll(
            iterations = 100,
            genA = Arb.list(buyTradeArb, 2..30),
            genB = Arb.int(1..6),
            genC = Arb.long(0L..1_000_000L),
        ) { trades, partitionCount, partitionSeed ->
            val full = aggregate(trades)!!

            // Aggregate per chunk, then combine the partial aggregates via the
            // closed-form weighted average. Under the same-side algebra this is
            // equivalent to a single fold over all trades; in finite precision
            // we expect agreement up to DECIMAL128 rounding noise.
            val chunks = randomPartition(trades, partitionCount, partitionSeed)
                .filter { it.isNotEmpty() }
            val partialAggregates = chunks.map { aggregate(it)!! }

            val combinedQty = partialAggregates.fold(BigDecimal.ZERO) { acc, p -> acc + p.quantity }
            val combinedNotional = partialAggregates.fold(BigDecimal.ZERO) { acc, p ->
                acc + (p.averageCost.amount * p.quantity)
            }
            val combinedAvg = combinedNotional.divide(combinedQty, java.math.MathContext.DECIMAL128)

            full.quantity.compareTo(combinedQty) shouldBe 0
            assertNearlyEqual(full.averageCost.amount, combinedAvg)
        }
    }

    test("multi-instrument portfolio aggregation: per-(book,instrument) net quantity is permutation-invariant") {
        checkAll(
            iterations = 100,
            genA = Arb.list(multiKeyTradeArb, 1..50),
            genB = Arb.long(0L..1_000_000L),
        ) { trades, shuffleSeed ->
            val permuted = trades.shuffled(java.util.Random(shuffleSeed))

            val original = netByKey(trades)
            val reordered = netByKey(permuted)

            // Both maps must cover the same set of keys with the same values.
            original.keys shouldBe reordered.keys
            for (k in original.keys) {
                original.getValue(k).compareTo(reordered.getValue(k)) shouldBe 0
            }
        }
    }
})

/**
 * Asserts that two BigDecimal values agree up to DECIMAL128 rounding noise.
 *
 * `Position.applyTrade` computes the running average cost via
 * `divide(…, MathContext.DECIMAL128)`, which rounds at 34 significant digits.
 * Algebraically the closed-form weighted average is commutative and
 * associative; in finite precision, different reduction orders may differ in
 * their last few digits. Tolerance: `1e-25 * max(|a|, |b|) + 1e-25`.
 */
private fun assertNearlyEqual(a: java.math.BigDecimal, b: java.math.BigDecimal) {
    val tol = a.abs().max(b.abs())
        .multiply(java.math.BigDecimal("1e-25"))
        .add(java.math.BigDecimal("1e-25"))
    val diff = a.subtract(b).abs()
    if (diff > tol) {
        throw AssertionError("expected ~$a but was $b (diff=$diff, tol=$tol)")
    }
}

/**
 * Partitions [items] into [n] non-empty chunks using [seed] for split
 * decisions. Mirrors `partition` in [com.kinetix.orchestrator.property.PnLAdditivityPropertyTest].
 */
private fun <T> randomPartition(items: List<T>, n: Int, seed: Long): List<List<T>> {
    require(n >= 1)
    if (items.isEmpty()) return emptyList()
    val k = n.coerceAtMost(items.size)
    val rng = java.util.Random(seed)
    val buckets = List(k) { mutableListOf<T>() }
    items.take(k).forEachIndexed { i, item -> buckets[i].add(item) }
    items.drop(k).forEach { item -> buckets[rng.nextInt(k)].add(item) }
    return buckets.map { it.toList() }
}
