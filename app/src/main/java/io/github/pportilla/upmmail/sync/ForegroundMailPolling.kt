package io.github.pportilla.upmmail.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Changing the cadence replaces only the waiting timer. An active check may be flushing a
 * draft or other mailbox mutation, so it finishes before the latest interval takes effect.
 * Null disables subsequent checks. Cancelling the owner still stops all foreground work.
 */
internal suspend fun pollAtIntervals(intervals: Flow<Long?>, check: suspend () -> Unit): Unit = coroutineScope {
    val updates = intervals.distinctUntilChanged().buffer(Channel.CONFLATED).produceIn(this)
    try {
        val initial = updates.receiveCatching()
        if (initial.isClosed) return@coroutineScope
        var interval = initial.getOrThrow()
        while (isActive) {
            val change = if (interval == null) updates.receiveCatching()
                else withTimeoutOrNull(interval) { updates.receiveCatching() }
            if (change == null) {
                // Outside the timer's cancellation scope: a new preference cannot abort this.
                check()
            } else {
                if (change.isClosed) return@coroutineScope
                interval = change.getOrThrow()
            }
            // A check can outlast several preference changes. Start only the latest timer.
            val latest = updates.tryReceive()
            if (latest.isClosed) return@coroutineScope
            if (latest.isSuccess) interval = latest.getOrThrow()
        }
    } finally {
        updates.cancel()
    }
}
