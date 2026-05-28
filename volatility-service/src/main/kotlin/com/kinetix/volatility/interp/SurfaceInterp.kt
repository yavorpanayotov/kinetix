package com.kinetix.volatility.interp

/**
 * Bilinear vol-surface interpolation with flat extrapolation.
 *
 * Mirrors the Python risk-engine helper but lives on the Kotlin side
 * so the volatility-service can serve interpolated vols without a
 * gRPC round-trip. Inputs are strike + expiry knot arrays + a 2D vol
 * grid; queries return the interpolated vol at any (strike, expiry).
 */
data class VolSurfaceGrid(
    val strikes: List<Double>,
    val expiryDays: List<Int>,
    val vols: List<List<Double>>,
) {
    init {
        require(strikes.isNotEmpty() && expiryDays.isNotEmpty()) {
            "VolSurfaceGrid needs at least one strike and one expiry"
        }
        require(vols.size == strikes.size) {
            "vols has ${vols.size} rows but strikes has ${strikes.size}"
        }
        for (row in vols) {
            require(row.size == expiryDays.size) {
                "vols row length must equal expiryDays size"
            }
        }
    }
}

fun interpolateSurfaceVol(grid: VolSurfaceGrid, strike: Double, expiryDays: Int): Double {
    val (iLo, iHi, wi) = bracket(grid.strikes, strike)
    val (jLo, jHi, wj) = bracket(grid.expiryDays.map { it.toDouble() }, expiryDays.toDouble())
    val v00 = grid.vols[iLo][jLo]
    val v01 = grid.vols[iLo][jHi]
    val v10 = grid.vols[iHi][jLo]
    val v11 = grid.vols[iHi][jHi]
    return (1 - wi) * (1 - wj) * v00 +
        (1 - wi) * wj * v01 +
        wi * (1 - wj) * v10 +
        wi * wj * v11
}

private fun bracket(axis: List<Double>, value: Double): Triple<Int, Int, Double> {
    if (value <= axis.first()) return Triple(0, 0, 0.0)
    if (value >= axis.last()) return Triple(axis.size - 1, axis.size - 1, 0.0)
    for (i in 0 until axis.size - 1) {
        if (axis[i] <= value && value <= axis[i + 1]) {
            val span = axis[i + 1] - axis[i]
            if (span == 0.0) return Triple(i, i + 1, 0.0)
            return Triple(i, i + 1, (value - axis[i]) / span)
        }
    }
    return Triple(axis.size - 1, axis.size - 1, 0.0)
}
