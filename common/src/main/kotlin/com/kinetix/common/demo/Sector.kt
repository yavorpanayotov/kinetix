package com.kinetix.common.demo

/**
 * Coarse GICS-aligned sector tag used for factor structure during demo synthesis.
 * Non-equity instruments use OTHER and are driven by their asset-class factor instead.
 */
enum class Sector {
    TECH,
    FINANCIALS,
    HEALTHCARE,
    CONSUMER,
    ENERGY,
    INDUSTRIALS,
    OTHER,
}
