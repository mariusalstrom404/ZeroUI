package com.example.myapplication.automation

import com.example.myapplication.automation.SettleRule.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettleRuleTest {

    private val rule = SettleRule(quietMs = 600, busyMs = 4000, maxMs = 8000)
    private val notLoading = { false }
    private val loading = { true }

    @Test
    fun aQuietScreenSettlesWellBeforeTheOldFixedWait() {
        assertEquals(State.SETTLED, rule.check(elapsedMs = 600, sinceChangeMs = 600, loading = notLoading))
    }

    @Test
    fun waitsWhileTheScreenIsStillChanging() {
        assertEquals(State.WAIT, rule.check(elapsedMs = 1500, sinceChangeMs = 200, loading = notLoading))
    }

    @Test
    fun aQuietScreenThatIsStillLoadingIsNotSettled() {
        assertEquals(State.WAIT, rule.check(elapsedMs = 2000, sinceChangeMs = 1500, loading = loading))
    }

    @Test
    fun aScreenThatNeverStopsChangingGetsTheOldFixedWait() {
        assertEquals(State.TIMED_OUT, rule.check(elapsedMs = 4000, sinceChangeMs = 100, loading = notLoading))
    }

    @Test
    fun loadingGetsLongerThanTheOldFixedWait() {
        assertEquals(State.WAIT, rule.check(elapsedMs = 6000, sinceChangeMs = 100, loading = loading))
        assertEquals(State.TIMED_OUT, rule.check(elapsedMs = 8000, sinceChangeMs = 100, loading = loading))
    }

    @Test
    fun theScreenIsOnlyReadWhenItMatters() {
        var reads = 0
        rule.check(elapsedMs = 300, sinceChangeMs = 100) { reads++; false }
        assertEquals(0, reads)
        assertFalse(rule.check(elapsedMs = 700, sinceChangeMs = 700) { reads++; false } == State.WAIT)
        assertEquals(1, reads)
    }
}
