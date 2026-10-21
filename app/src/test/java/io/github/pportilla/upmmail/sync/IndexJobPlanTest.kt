package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.model.IndexStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexJobPlanTest {
    private val minute = 60_000L

    private fun status(accountId: Long = 1, indexed: Int = 0, total: Int = 0, complete: Boolean = false) =
        IndexStatus(accountId, indexed, total, complete)

    private fun plan(status: List<IndexStatus>, lastMoreWork: Boolean?, idleRuns: Int = 0): IndexJobPlan.BulkSpec {
        val spec = IndexJobPlan.bulk(status, lastMoreWork, idleRuns)
        assertNotNull("a bulk job is planned", spec)
        return spec!!
    }

    @Test
    fun noJobWithoutAccounts() {
        assertNull(IndexJobPlan.bulk(emptyList(), lastMoreWork = null))
        assertNull(IndexJobPlan.bulk(emptyList(), lastMoreWork = true, idleRuns = 3))
    }

    @Test
    fun incompleteIndexRunsAtOnceWithoutChargingWhenNoRunHappenedYet() {
        val spec = plan(listOf(status(indexed = 1_000, total = 11_000)), lastMoreWork = null)
        assertEquals(0L, spec.latencyMillis)
        assertFalse(spec.requiresCharging)
        assertEquals(0, spec.idleRuns)
    }

    @Test
    fun oneIncompleteAccountMakesTheIndexIncomplete() {
        val spec = plan(
            listOf(status(1, indexed = 500, total = 500, complete = true), status(2, complete = false)),
            lastMoreWork = null,
        )
        assertEquals(0L, spec.latencyMillis)
        assertFalse(spec.requiresCharging)
    }

    @Test
    fun runThatLeftWorkContinuesAfterFifteenMinutesWithoutCharging() {
        val spec = plan(listOf(status(indexed = 1_000, total = 11_000)), lastMoreWork = true)
        assertEquals(15 * minute, spec.latencyMillis)
        assertFalse(spec.requiresCharging)
        assertEquals(0, spec.idleRuns)
    }

    @Test
    fun completeIndexWaitsSixHoursForCharging() {
        val complete = listOf(status(1, 800, 800, complete = true), status(2, 50, 50, complete = true))
        for (lastMoreWork in listOf(null, false)) {
            val spec = plan(complete, lastMoreWork)
            assertEquals(6 * 60 * minute, spec.latencyMillis)
            assertTrue(spec.requiresCharging)
            assertEquals(0, spec.idleRuns)
        }
    }

    @Test
    fun theLastRunBeatsTheStatus() {
        // An account at the row cap is never complete, but a run that found nothing to do says so.
        val capped = plan(listOf(status(indexed = 100_000, total = 100_000, complete = false)), lastMoreWork = false)
        assertTrue(capped.requiresCharging)
        assertEquals(6 * 60 * minute, capped.latencyMillis)
        // A run stopped by an error leaves work even when every folder was complete before.
        val failed = plan(listOf(status(indexed = 10, total = 10, complete = true)), lastMoreWork = true)
        assertFalse(failed.requiresCharging)
        assertEquals(15 * minute, failed.latencyMillis)
    }

    @Test
    fun idleRunsBackOffUpToSixHours() {
        val incomplete = listOf(status(indexed = 0, total = 5_000))
        val latencies = (0..7).map { plan(incomplete, lastMoreWork = true, idleRuns = it).latencyMillis / minute }
        assertEquals(listOf(15L, 30L, 60L, 120L, 240L, 360L, 360L, 360L), latencies)
        assertEquals(4, plan(incomplete, lastMoreWork = true, idleRuns = 4).idleRuns)
        assertFalse(plan(incomplete, lastMoreWork = true, idleRuns = 7).requiresCharging)
        assertEquals(15 * minute, plan(incomplete, lastMoreWork = true, idleRuns = -2).latencyMillis)
    }

    @Test
    fun idleRunsCountOnlyRunsThatLeftWorkAndGotNothingDone() {
        assertEquals(3, IndexJobPlan.idleRunsAfter(IndexRunResult(0, 0, moreWork = true, error = null), 2))
        assertEquals(0, IndexJobPlan.idleRunsAfter(IndexRunResult(250, 0, moreWork = true, error = null), 2))
        assertEquals(0, IndexJobPlan.idleRunsAfter(IndexRunResult(0, 7, moreWork = true, error = null), 2))
        assertEquals(0, IndexJobPlan.idleRunsAfter(IndexRunResult(0, 0, moreWork = false, error = null), 2))
        assertEquals(1, IndexJobPlan.idleRunsAfter(IndexRunResult(0, 0, moreWork = true, error = null), 0))
    }

    @Test
    fun byteEstimateIsNineHundredBytesPerMessageNotIndexedYet() {
        val spec = plan(
            listOf(status(1, indexed = 1_000, total = 11_000), status(2, indexed = 0, total = 2_000)),
            lastMoreWork = null,
        )
        assertEquals(12_000L * 900, spec.estimatedBytes)
        // A complete index downloads only what changed: nothing to estimate.
        assertEquals(0L, plan(listOf(status(indexed = 300, total = 300, complete = true)), null).estimatedBytes)
        // Counts that briefly disagree (a folder emptied since the check) never go negative.
        assertEquals(0L, plan(listOf(status(indexed = 300, total = 200)), null).estimatedBytes)
    }

    @Test
    fun hundredThousandMessagesFitTheEstimate() {
        val spec = plan(List(3) { status(it + 1L, indexed = 0, total = 100_000) }, lastMoreWork = null)
        assertEquals(300_000L * 900, spec.estimatedBytes)
    }
}
