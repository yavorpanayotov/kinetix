package com.kinetix.regulatory.model

import kotlin.math.abs

/**
 * Reconcile desk-reported P&L against the trade-derived P&L for a trading
 * day. Returns [ReconciliationStatus.WithinTolerance] when the mismatch
 * fits inside both the absolute and relative tolerance bands; otherwise
 * a [ReconciliationStatus.Break] carrying the observed mismatches.
 *
 * A break passes its tolerance check when EITHER the absolute mismatch
 * is within [absoluteTolerance] OR the relative mismatch is within
 * [relativeTolerance] — book-sized differences need both axes because
 * a $1k mismatch on a $1M book is fine on relative terms while a $1k
 * mismatch on a $1k book is not.
 *
 * When [tradeDerived] is zero the relative mismatch is undefined; the
 * function falls back to the absolute check only.
 */
fun reconcilePnL(
    tradeDerived: Double,
    deskReported: Double,
    absoluteTolerance: Double,
    relativeTolerance: Double,
): ReconciliationStatus {
    val absMismatch = abs(deskReported - tradeDerived)
    val withinAbs = absMismatch <= absoluteTolerance

    if (tradeDerived == 0.0) {
        return if (withinAbs) {
            ReconciliationStatus.WithinTolerance
        } else {
            ReconciliationStatus.Break(absoluteMismatch = absMismatch, relativeMismatch = Double.NaN)
        }
    }

    val relMismatch = absMismatch / abs(tradeDerived)
    val withinRel = relMismatch <= relativeTolerance

    return if (withinAbs || withinRel) {
        ReconciliationStatus.WithinTolerance
    } else {
        ReconciliationStatus.Break(absoluteMismatch = absMismatch, relativeMismatch = relMismatch)
    }
}

/** Outcome of [reconcilePnL]. */
sealed interface ReconciliationStatus {
    data object WithinTolerance : ReconciliationStatus
    data class Break(
        val absoluteMismatch: Double,
        val relativeMismatch: Double,
    ) : ReconciliationStatus
}
