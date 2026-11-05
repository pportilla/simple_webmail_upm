package io.github.pportilla.upmmail.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundMailPollingTest {
    @Test fun intervalChangePreservesActiveCheckAndUsesNewDelayAfterItCompletes() = runTest {
        val intervals = MutableStateFlow<Long?>(5_000)
        val finish = CompletableDeferred<Unit>()
        var started = 0
        var completed = 0
        var cancelled = false
        val polling = launch {
            pollAtIntervals(intervals) {
                started++
                try { if (started == 1) finish.await(); completed++ }
                catch (e: CancellationException) { cancelled = true; throw e }
            }
        }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, started)
        intervals.value = 10_000; runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertFalse("Changing cadence must not abort an in-flight mailbox mutation", cancelled)
        assertEquals(1, started)
        assertEquals(0, completed)
        finish.complete(Unit); runCurrent()
        assertEquals(1, completed)
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, started)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, started)
        polling.cancelAndJoin()
    }

    @Test fun manualSelectionDuringCheckFinishesItAndRemovesFutureTicks() = runTest {
        val intervals = MutableStateFlow<Long?>(5_000)
        val finish = CompletableDeferred<Unit>()
        var started = 0
        var completed = 0
        val polling = launch {
            pollAtIntervals(intervals) { started++; finish.await(); completed++ }
        }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        intervals.value = 10_000; runCurrent()
        intervals.value = 15_000; runCurrent()
        intervals.value = null; runCurrent()
        finish.complete(Unit); runCurrent()
        assertEquals(1, completed)
        advanceTimeBy(120_000); runCurrent()
        assertEquals("No old tick survives manual mode", 1, started)
        intervals.value = 10_000; runCurrent()
        advanceTimeBy(9_999); runCurrent(); assertEquals(1, started)
        advanceTimeBy(1); runCurrent(); assertEquals(2, started)
        polling.cancelAndJoin()
    }

    @Test fun intervalChangeWhileWaitingReplacesOnlyTheTimer() = runTest {
        val intervals = MutableStateFlow<Long?>(15_000)
        var checks = 0
        val polling = launch { pollAtIntervals(intervals) { checks++ } }
        runCurrent(); advanceTimeBy(4_000)
        intervals.value = 5_000; runCurrent()
        advanceTimeBy(4_999); runCurrent(); assertEquals(0, checks)
        advanceTimeBy(1); runCurrent(); assertEquals(1, checks)
        intervals.value = null; runCurrent()
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, checks)
        polling.cancelAndJoin()
    }

    @Test fun leavingForegroundStillCancelsTheActiveCheckAndItsTimer() = runTest {
        val intervals = MutableStateFlow<Long?>(5_000)
        var cancelled = false
        var checks = 0
        val polling = launch {
            pollAtIntervals(intervals) {
                checks++
                try { awaitCancellation() }
                finally { cancelled = true }
            }
        }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, checks)
        polling.cancelAndJoin()
        assertTrue(cancelled)
        intervals.value = 10_000
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, checks)
    }
}
