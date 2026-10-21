package io.github.pportilla.upmmail.data.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turns table invalidations into Flows. Writers call [notifyChanged] after committing; every
 * observer whose tables intersect re-runs its query.
 */
class TableObserver(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    private val listeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    fun notifyChanged(tables: Set<String>) {
        if (tables.isEmpty()) return
        listeners.forEach { it(tables) }
    }

    /**
     * Emits [query]'s result now and again after every change to one of [tables]. The listener is
     * registered before the first query runs, so no change can slip in between. Queries run on
     * the IO dispatcher, bursts of changes are conflated, and equal results are not re-emitted.
     */
    fun <T> observe(tables: Set<String>, query: () -> T): Flow<T> =
        channelFlow {
            val trigger = Channel<Unit>(Channel.CONFLATED)
            val listener: (Set<String>) -> Unit = { changed ->
                if (changed.any { it in tables }) trigger.trySend(Unit)
            }
            listeners += listener
            try {
                trigger.trySend(Unit)
                for (ignored in trigger) send(query())
            } finally {
                listeners -= listener
            }
        }
            .flowOn(dispatcher)
            .conflate()
            .distinctUntilChanged()
}
