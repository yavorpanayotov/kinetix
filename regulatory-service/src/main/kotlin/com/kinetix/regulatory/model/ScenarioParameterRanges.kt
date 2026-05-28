package com.kinetix.regulatory.model

/**
 * Validate a rates-shock parameter expressed in basis points. The
 * supported range is `[-500, +500]` bps inclusive — wide enough to
 * cover serious crisis moves (the 2022 BoE LDI episode saw ~500bp
 * intraday) and narrow enough that a fat-finger like 5000 is rejected
 * before the risk engine spends an hour computing garbage.
 *
 * @throws IllegalArgumentException if [shockBp] is outside the range.
 */
fun validateRatesShockBp(shockBp: Int): Int {
    require(shockBp in -500..500) {
        "rates shock $shockBp bps is outside the supported range [-500, +500]"
    }
    return shockBp
}

/**
 * Validate an FX-shock parameter expressed as a fractional change
 * (e.g. 0.20 == +20%). The supported range is `[-0.20, +0.20]`
 * inclusive — wider moves than ±20% intra-day are macro events that
 * warrant a custom scenario, not the standard library.
 *
 * @throws IllegalArgumentException if [shock] is outside the range.
 */
fun validateFxShockPercent(shock: Double): Double {
    require(shock in -0.20..0.20) {
        "FX shock ${shock * 100}% is outside the supported range [-20%, +20%]"
    }
    return shock
}
