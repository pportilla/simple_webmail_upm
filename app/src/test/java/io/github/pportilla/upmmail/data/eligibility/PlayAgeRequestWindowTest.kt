package io.github.pportilla.upmmail.data.eligibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayAgeRequestWindowTest {
    @Test fun resumeAndExplicitRetryCannotDuplicateAnOpenPlayPrompt() {
        val window = PlayAgeRequestWindow()
        assertNotNull(window.begin(0, force = false))
        assertNull(window.begin(1, force = false))
        assertNull(window.begin(2, force = true))
    }

    @Test fun timeoutEndsCheckingOnceButLateSameRequestStillApplies() {
        val window = PlayAgeRequestWindow()
        val token = window.begin(0, force = false)!!
        assertTrue(window.finish(token, 15_000))
        assertTrue(window.isCurrent(token))
        assertFalse(window.finish(token, 16_000))
    }

    @Test fun newerRequestWinsOverLateOldApprovalOrRestriction() {
        val window = PlayAgeRequestWindow()
        val old = window.begin(0, force = false)!!
        assertTrue(window.finish(old, 15_000))
        val newer = window.begin(16_000, force = true)!!
        assertFalse(window.isCurrent(old))
        assertFalse(window.finish(old, 17_000))
        assertTrue(window.isCurrent(newer))
        assertNull(window.begin(18_000, force = true))
        assertTrue(window.finish(newer, 19_000))
    }

    @Test fun consentDialogResumeDoesNotPromptAgainButUserCanRetry() {
        val window = PlayAgeRequestWindow()
        val token = window.begin(0, force = false)!!
        assertTrue(window.finish(token, 500))
        assertNull(window.begin(501, force = false))
        assertNotNull(window.begin(501, force = true))
    }

    @Test fun laterForegroundChecksAgainAfterCooldown() {
        val window = PlayAgeRequestWindow()
        val token = window.begin(0, force = false)!!
        assertTrue(window.finish(token, 100))
        assertNull(window.begin(30_099, force = false))
        assertNotNull(window.begin(30_100, force = false))
    }
}
