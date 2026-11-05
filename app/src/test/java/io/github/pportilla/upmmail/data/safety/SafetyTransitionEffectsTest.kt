package io.github.pportilla.upmmail.data.safety

import org.junit.Assert.*
import org.junit.Test

class SafetyTransitionEffectsTest {
    @Test fun foregroundChecksPauseConnectionsThenRefreshWithoutClearingOrdinaryNotifications() {
        val policy = LocalSafetyRepository().apply { completeGeneralSetup() }
        val ready = policy.state.value
        assertFalse(safetyTransitionEffects(null, ready).clearNotifications)
        policy.setPlatformCheckPending(true)
        val checking = policy.state.value
        val start = safetyTransitionEffects(ready, checking)
        assertTrue(start.closeConnections)
        assertFalse(start.clearNotifications)
        assertFalse(start.refreshForeground)
        assertTrue(checking.mailboxAccessAllowed)
        policy.setPlatformCheckPending(false)
        val finish = safetyTransitionEffects(checking, policy.state.value)
        assertFalse(finish.closeConnections)
        assertFalse(finish.clearNotifications)
        assertTrue("The initial poll may have paused during the check", finish.refreshForeground)
    }

    @Test fun explicitRestrictionCancelsAndClearsButItsRemovalRefreshesWithoutClosingNewConnections() {
        val policy = LocalSafetyRepository().apply { completeGeneralSetup() }
        val ready = policy.state.value
        policy.updatePlatformRestriction(PlatformRestriction.APPROVAL_PENDING)
        val restricted = policy.state.value
        val revoke = safetyTransitionEffects(ready, restricted)
        assertTrue(revoke.closeConnections); assertTrue(revoke.clearNotifications)
        assertFalse(revoke.refreshForeground)
        policy.updatePlatformRestriction(PlatformRestriction.NONE)
        val restore = safetyTransitionEffects(restricted, policy.state.value)
        assertFalse(restore.closeConnections); assertFalse(restore.clearNotifications)
        assertTrue(restore.refreshForeground)
    }

    @Test fun localReportsAndUnblockingDoNotInterruptMailButNewBlockHidesExistingNotifications() {
        val policy = LocalSafetyRepository().apply { completeGeneralSetup() }
        val ready = policy.state.value
        policy.reportMessage(1, "Saved concern")
        val report = policy.state.value
        assertEquals(SafetyTransitionEffects(false, false, false), safetyTransitionEffects(ready, report))
        policy.blockSender("blocked@example.invalid")
        val blocked = policy.state.value
        assertTrue(safetyTransitionEffects(report, blocked).clearNotifications)
        policy.unblockSender("blocked@example.invalid")
        assertEquals(SafetyTransitionEffects(false, false, false), safetyTransitionEffects(blocked, policy.state.value))
    }

    @Test fun initialAcknowledgementResumesForegroundRefreshOnlyAfterPendingCheckEnds() {
        val policy = LocalSafetyRepository()
        policy.setPlatformCheckPending(true)
        val before = policy.state.value
        policy.completeGeneralSetup()
        val acknowledged = policy.state.value
        assertFalse(safetyTransitionEffects(before, acknowledged).refreshForeground)
        policy.setPlatformCheckPending(false)
        assertTrue(safetyTransitionEffects(acknowledged, policy.state.value).refreshForeground)
    }
}
