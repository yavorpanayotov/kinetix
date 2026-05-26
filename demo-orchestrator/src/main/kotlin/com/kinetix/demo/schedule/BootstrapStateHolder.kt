package com.kinetix.demo.schedule

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory holder for the startup VaR bootstrap state.
 *
 * Thread-safe via [AtomicReference]. The holder is created once on startup,
 * injected into both the bootstrap launch coroutine and the
 * `GET /demo/bootstrap-status` route.
 *
 * State machine: [BootstrapState.NOT_STARTED] → [BootstrapState.IN_PROGRESS]
 * → [BootstrapState.READY] or [BootstrapState.FAILED].
 */
class BootstrapStateHolder {

    private val state = AtomicReference(BootstrapState.NOT_STARTED)
    private val resultRef = AtomicReference<BootstrapResult?>(null)

    /** Returns the current [BootstrapState]. */
    fun get(): BootstrapState = state.get()

    /** Returns the [BootstrapResult] when the sweep has completed, or `null` otherwise. */
    fun getResult(): BootstrapResult? = resultRef.get()

    /** Transitions the state to [BootstrapState.IN_PROGRESS]. */
    fun setInProgress() {
        state.set(BootstrapState.IN_PROGRESS)
    }

    /** Transitions the state to [BootstrapState.READY] and stores the result. */
    fun setReady(result: BootstrapResult) {
        resultRef.set(result)
        state.set(BootstrapState.READY)
    }

    /** Transitions the state to [BootstrapState.FAILED] and stores the optional result. */
    fun setFailed(result: BootstrapResult?) {
        resultRef.set(result)
        state.set(BootstrapState.FAILED)
    }
}
