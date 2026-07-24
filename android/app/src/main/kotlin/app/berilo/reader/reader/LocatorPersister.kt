package app.berilo.reader.reader

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounces reading-position writes. The Readium navigator emits a new locator
 * on every layout settle and page turn; persisting each one would hammer the
 * database. [onChanged] coalesces a burst into a single write after
 * [debounceMillis] of quiet, while [flush] forces the latest position out
 * immediately (used on `onPause`, so a position is never lost when the reader
 * leaves the foreground).
 *
 * Both paths feed one long-lived collector: the debounced [pending] flow and an
 * immediate [flushRequests] channel are merged, so a flush is served by the same
 * coroutine that serves debounced writes rather than racing a freshly launched
 * one. Identical consecutive positions are written once — re-persisting the same
 * Locator JSON is skipped, mirroring the translator's sha1 dedupe philosophy.
 *
 * @param scope Lifecycle scope the collector runs in (the ViewModel's).
 * @param debounceMillis Quiet period before a coalesced write.
 * @param dispatcher Dispatcher writes run on; injectable so tests stay on
 *   virtual time (findings.md Android testability pattern).
 * @param save Suspending sink that performs the actual persistence.
 */
@OptIn(FlowPreview::class)
class LocatorPersister(
    scope: CoroutineScope,
    debounceMillis: Long,
    dispatcher: CoroutineContext,
    private val save: suspend (String) -> Unit,
) {
    private val pending = MutableStateFlow<String?>(null)
    private val flushRequests = Channel<String>(Channel.CONFLATED)
    private val writeLock = Mutex()
    private var lastSaved: String? = null

    init {
        scope.launch(dispatcher) {
            merge(
                pending.filterNotNull().debounce(debounceMillis),
                flushRequests.receiveAsFlow(),
            ).collect { saveOnce(it) }
        }
    }

    /** Records a new position; a write follows after [debounceMillis] of quiet. */
    fun onChanged(locatorJson: String) {
        pending.value = locatorJson
    }

    /**
     * Writes the latest recorded position now, bypassing the debounce. A no-op
     * when nothing was ever recorded; a duplicate of the last write is deduped.
     */
    fun flush() {
        val latest = pending.value ?: return
        flushRequests.trySend(latest)
    }

    private suspend fun saveOnce(locatorJson: String) {
        writeLock.withLock {
            if (locatorJson == lastSaved) return
            lastSaved = locatorJson
            save(locatorJson)
        }
    }
}
