package io.github.pportilla.upmmail.sync

import io.github.pportilla.upmmail.mail.ImapIdler
import io.github.pportilla.upmmail.mail.MailErrors
import io.github.pportilla.upmmail.model.MailErrorKind
import io.github.pportilla.upmmail.model.MailException
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What one connection attempt of [PushWatcherTest.FakeConnection] does. */
private typealias Step = suspend (onIdle: () -> Unit) -> Unit

@OptIn(ExperimentalCoroutinesApi::class)
class PushWatcherTest {

    /** Runs the scripted [then] steps in order, one per connection attempt; the last one repeats. */
    private class FakeConnection(private val now: () -> Long) : PushConnection {
        private val steps = ArrayDeque<Step>()
        val starts = mutableListOf<Long>()
        var refreshes = 0
        var onChange: (ImapIdler.Change) -> Unit = {}

        fun then(step: Step) = apply { steps += step }

        override suspend fun run(onIdle: () -> Unit, onChange: (ImapIdler.Change) -> Unit) {
            starts += now()
            this.onChange = onChange
            val step = if (steps.size > 1) steps.removeFirst() else steps.first()
            step(onIdle)
        }

        override suspend fun refresh(): Boolean {
            refreshes++
            return true
        }
    }

    private val idleForever: Step = { onIdle ->
        onIdle()
        awaitCancellation()
    }

    private val noIdleSupport: Step = {}

    private fun failing(kind: MailErrorKind): Step = { throw MailException(kind) }

    private fun idleThenDrop(after: Long): Step = { onIdle ->
        onIdle()
        delay(after)
        throw MailException(MailErrorKind.NETWORK)
    }

    private fun TestScope.connection() = FakeConnection { testScheduler.currentTime }

    private fun TestScope.start(
        connection: FakeConnection,
        syncs: MutableList<Pair<Long, Boolean>> = mutableListOf(),
        failures: MutableList<MailErrorKind> = mutableListOf(),
        isRelevant: (ImapIdler.Change) -> Boolean = { true },
        sync: suspend (Boolean) -> Unit = {},
    ): PushWatcher {
        val watcher = PushWatcher(
            connection = connection,
            sync = { force ->
                syncs += testScheduler.currentTime to force
                sync(force)
            },
            isRelevant = isRelevant,
            onFailure = { failures += it.kind },
            clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { watcher.run() }
        runCurrent()
        return watcher
    }

    @Test
    fun changesAreSyncedTogetherAfterTheDebounce() = runTest {
        val connection = connection().then(idleForever)
        val syncs = mutableListOf<Pair<Long, Boolean>>()
        val watcher = start(connection, syncs)
        assertEquals(PushState.IDLING, watcher.state.value)

        advanceTimeBy(2_000)
        // Catching up after connecting.
        assertEquals(listOf(1_500L to true), syncs)

        repeat(3) {
            connection.onChange(ImapIdler.Change.NEW_MESSAGES)
            advanceTimeBy(200)
        }
        advanceTimeBy(5_000)
        assertEquals(listOf(1_500L to true, 3_500L to true), syncs)
    }

    @Test
    fun changesDuringASyncLeadToOneMoreSyncAfterIt() = runTest {
        val connection = connection().then(idleForever)
        val syncs = mutableListOf<Pair<Long, Boolean>>()
        var running = false
        start(connection, syncs) {
            assertFalse("syncs overlap", running)
            running = true
            delay(10_000)
            running = false
        }
        advanceTimeBy(3_000)
        connection.onChange(ImapIdler.Change.NEW_MESSAGES)
        advanceTimeBy(1_000)
        connection.onChange(ImapIdler.Change.FLAGS_CHANGED)
        advanceTimeBy(60_000)
        assertEquals(listOf(1_500L to true, 13_000L to true), syncs)
    }

    @Test
    fun failedSyncIsReportedAndLaterChangesStillSync() = runTest {
        val connection = connection().then(idleForever)
        val syncs = mutableListOf<Pair<Long, Boolean>>()
        val failures = mutableListOf<MailErrorKind>()
        start(connection, syncs, failures) {
            if (syncs.size == 1) throw MailException(MailErrorKind.SERVER)
        }
        advanceTimeBy(2_000)
        connection.onChange(ImapIdler.Change.NEW_MESSAGES)
        advanceTimeBy(2_000)
        assertEquals(listOf(1_500L to true, 3_500L to true), syncs)
        assertEquals(listOf(MailErrorKind.SERVER), failures)
    }

    @Test
    fun irrelevantChangesAreNotSynced() = runTest {
        val connection = connection().then(idleForever)
        val syncs = mutableListOf<Pair<Long, Boolean>>()
        start(connection, syncs, isRelevant = { it == ImapIdler.Change.NEW_MESSAGES })
        advanceTimeBy(2_000)
        connection.onChange(ImapIdler.Change.FLAGS_CHANGED)
        connection.onChange(ImapIdler.Change.MESSAGES_REMOVED)
        advanceTimeBy(5_000)
        assertEquals(listOf(1_500L to true), syncs)

        connection.onChange(ImapIdler.Change.NEW_MESSAGES)
        advanceTimeBy(2_000)
        assertEquals(listOf(1_500L to true, 8_500L to true), syncs)
    }

    @Test
    fun everyNewConnectionCatchesUp() = runTest {
        val connection = connection()
            .then(idleThenDrop(after = 10_000))
            .then(idleForever)
        val syncs = mutableListOf<Pair<Long, Boolean>>()
        start(connection, syncs)
        advanceTimeBy(60_000)
        // Forced even shortly after the last sync: mail that arrived while disconnected is not
        // announced by the new connection.
        assertEquals(listOf(0L, 25_000L), connection.starts)
        assertEquals(listOf(1_500L to true, 26_500L to true), syncs)
    }

    @Test
    fun failedConnectionsAreRetriedWithExponentialBackoff() = runTest {
        val connection = connection().then(failing(MailErrorKind.NETWORK))
        val failures = mutableListOf<MailErrorKind>()
        val watcher = start(connection, failures = failures)
        advanceTimeBy(60 * 60_000L)
        val waits = connection.starts.zipWithNext { a, b -> b - a }
        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L),
            waits.take(8),
        )
        assertEquals(PushState.RETRYING, watcher.state.value)
        assertEquals(setOf(MailErrorKind.NETWORK), failures.toSet())
    }

    @Test
    fun healthyConnectionResetsTheBackoff() = runTest {
        val connection = connection()
            .then(failing(MailErrorKind.NETWORK))
            .then(failing(MailErrorKind.NETWORK))
            .then(idleThenDrop(after = 6 * 60_000L))
            .then(failing(MailErrorKind.NETWORK))
        start(connection)
        advanceTimeBy(460_000)
        assertEquals(listOf(0L, 15_000L, 45_000L, 420_000L, 450_000L), connection.starts)
    }

    @Test
    fun serverWithoutIdleIsPolled() = runTest {
        val connection = connection().then(noIdleSupport)
        val syncs = mutableListOf<Pair<Long, Boolean>>()
        val watcher = start(connection, syncs)
        advanceTimeBy(5 * 60_000L)
        assertEquals(PushState.POLLING, watcher.state.value)
        assertEquals(listOf(0L to false, 120_000L to false, 240_000L to false), syncs)
        assertEquals(1, connection.starts.size)

        // A refresh (alarm) polls at once.
        watcher.refresh()
        assertEquals(300_000L to false, syncs.last())
    }

    @Test
    fun refusedLoginIsRetriedOnlyAfterAnHour() = runTest {
        val connection = connection()
            .then(failing(MailErrorKind.AUTHENTICATION))
            .then(idleForever)
        val watcher = start(connection)
        assertEquals(PushState.LOGIN_FAILED, watcher.state.value)

        advanceTimeBy(10 * 60_000L)
        watcher.refresh()
        assertEquals(listOf(0L), connection.starts)

        advanceTimeBy(51 * 60_000L)
        assertEquals(listOf(0L, 60 * 60_000L), connection.starts)
        assertEquals(PushState.IDLING, watcher.state.value)
    }

    @Test
    fun temporaryLoginRefusalIsRetriedLikeAFailedConnection() = runTest {
        val temporary: Step = {
            throw MailErrors.map(AuthenticationFailedException("[UNAVAILABLE] Temporary authentication failure."))
        }
        val connection = connection()
            .then(temporary)
            .then(idleForever)
        val watcher = start(connection)
        assertEquals(PushState.RETRYING, watcher.state.value)

        advanceTimeBy(16_000)
        assertEquals(listOf(0L, 15_000L), connection.starts)
        assertEquals(PushState.IDLING, watcher.state.value)
    }

    @Test
    fun refreshWhileWaitingReconnectsAtOnce() = runTest {
        val connection = connection()
            .then(failing(MailErrorKind.NETWORK))
            .then(idleForever)
        val watcher = start(connection)
        assertEquals(PushState.RETRYING, watcher.state.value)

        advanceTimeBy(5_000)
        watcher.refresh()
        assertEquals(listOf(0L, 5_000L), connection.starts)
        assertEquals(PushState.IDLING, watcher.state.value)
    }

    @Test
    fun refreshWhileIdlingChecksTheConnection() = runTest {
        val connection = connection().then(idleForever)
        val watcher = start(connection)
        watcher.refresh()
        assertEquals(1, connection.refreshes)
        assertEquals(1, connection.starts.size)
    }
}
