package com.kinetix.common.demo

/**
 * Pre-computed daily factor returns indexed by day (0 = most recent).
 *
 * - market: SPX-like total market shock
 * - sectors: per-sector shock orthogonal to the market factor
 * - rates: 10Y level shock in absolute yield bps converted to fractional move
 * - dollar: DXY-like dollar index shock
 *
 * Driven by the regime calendar so STRESS days have wider draws and a negative
 * mean for market/sector factors plus a dovish rates drift in 2020-analog or
 * a hawkish drift in 2022-analog.
 */
class FactorReturns(
    val market: DoubleArray,
    val sector: Map<Sector, DoubleArray>,
    val rates: DoubleArray,
    val dollar: DoubleArray,
)
