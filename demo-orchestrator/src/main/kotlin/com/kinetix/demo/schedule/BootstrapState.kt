package com.kinetix.demo.schedule

/**
 * Lifecycle state of the startup VaR bootstrap sweep driven by
 * [DemoVaRBootstrapJob].
 *
 * Transitions in normal operation:
 *   [NOT_STARTED] → [IN_PROGRESS] → [READY]
 *
 * Transition on full failure (zero books succeeded):
 *   [NOT_STARTED] → [IN_PROGRESS] → [FAILED]
 */
enum class BootstrapState {
    NOT_STARTED,
    IN_PROGRESS,
    READY,
    FAILED,
}
