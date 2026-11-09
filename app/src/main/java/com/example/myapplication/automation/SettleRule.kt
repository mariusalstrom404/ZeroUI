package com.example.myapplication.automation

/**
 * Decides when the app on screen has finished loading, so the next step can start then instead
 * of after a fixed wait.
 *
 * Settled means the app hasn't changed for [quietMs] and shows no loading indicator. An app
 * that keeps changing without one, such as an animated banner, is only waited for up to
 * [busyMs], the old fixed wait. One that's still loading gets up to [maxMs].
 */
class SettleRule(
    private val quietMs: Long = 600,
    private val busyMs: Long = 4000,
    private val maxMs: Long = 8000
) {
    enum class State { WAIT, SETTLED, TIMED_OUT }

    /**
     * [elapsedMs] counts from the start of the wait, and [sinceChangeMs] from the last change or
     * the start, whichever is later. [loading] is only called when its answer matters, since it
     * reads the whole screen.
     */
    fun check(elapsedMs: Long, sinceChangeMs: Long, loading: () -> Boolean): State {
        if (elapsedMs >= maxMs) return State.TIMED_OUT
        val quiet = sinceChangeMs >= quietMs
        if (!quiet && elapsedMs < busyMs) return State.WAIT
        if (loading()) return State.WAIT
        return if (quiet) State.SETTLED else State.TIMED_OUT
    }
}
