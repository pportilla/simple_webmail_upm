package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.data.safety.AgeBand
import io.github.pportilla.upmmail.data.safety.LocalSafetyRepository
import io.github.pportilla.upmmail.mail.ImapIdler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PushSafetyTest {
    private class Connection : PushConnection {
        var connections = 0
        var refreshes = 0
        var closed = 0
        override suspend fun run(onIdle: () -> Unit, onChange: (ImapIdler.Change) -> Unit) {
            connections++
            try {
                onIdle()
                awaitCancellation()
            } finally {
                closed++
            }
        }
        override suspend fun refresh(): Boolean {
            refreshes++
            try {
                awaitCancellation()
            } finally {
                closed++
            }
        }
    }

    @Test
    fun unconfiguredAndUnacceptedAdultProfilesNeverReachTheConnection() = runTest {
        val safety = LocalSafetyRepository()
        val connection = Connection()
        val guarded = connection.withPushSafety(safety)
        val initial = launch { guarded.run({}, {}) }
        runCurrent()
        assertTrue(initial.isCancelled)
        safety.chooseAgeBand(AgeBand.ADULT)
        val missingTerms = launch { guarded.refresh() }
        runCurrent()
        assertTrue(missingTerms.isCancelled)
        assertEquals(0, connection.connections)
        assertEquals(0, connection.refreshes)
    }

    @Test
    fun switchingToSupervisedCancelsAnActiveIdleConnectionAndBlocksReconnect() = runTest {
        val safety = LocalSafetyRepository().apply { acceptTerms(); chooseAgeBand(AgeBand.ADULT) }
        val connection = Connection()
        val guarded = connection.withPushSafety(safety)
        val active = launch { guarded.run({}, {}) }
        runCurrent()
        assertEquals(1, connection.connections)
        assertFalse(active.isCompleted)

        safety.enableSupervised(AgeBand.UNDER_13, "246810", LocalDate.of(1980, 1, 1))
        runCurrent()
        assertTrue(active.isCancelled)
        assertEquals(1, connection.closed)
        safety.setCommunicationEnabled(true)
        assertTrue(safety.state.value.guardianUnlocked)
        assertFalse(pushAllowedBySafety(safety))
        val retry = launch { guarded.run({}, {}) }
        runCurrent()
        assertTrue(retry.isCancelled)
        assertEquals(1, connection.connections)
    }

    @Test
    fun aKeepAliveIsCancelledWhenTheAdultProfileBecomesSupervised() = runTest {
        val safety = LocalSafetyRepository().apply { acceptTerms(); chooseAgeBand(AgeBand.ADULT) }
        val connection = Connection()
        val guarded = connection.withPushSafety(safety)
        val active = launch { guarded.refresh() }
        runCurrent()
        assertEquals(1, connection.refreshes)

        safety.enableSupervised(AgeBand.TEEN, "246810", LocalDate.of(1980, 1, 1))
        runCurrent()
        assertTrue(active.isCancelled)
        assertEquals(1, connection.closed)
        safety.lockGuardian()
        val retry = launch { guarded.refresh() }
        runCurrent()
        assertTrue(retry.isCancelled)
        assertEquals(1, connection.refreshes)
    }
}
