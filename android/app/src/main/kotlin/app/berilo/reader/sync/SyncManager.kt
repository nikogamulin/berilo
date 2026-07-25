package app.berilo.reader.sync

import app.berilo.reader.sync.auth.AccountState
import app.berilo.reader.sync.auth.AuthGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The last sync round's result, as the settings screen shows it. */
sealed interface SyncStatus {
    data object Idle : SyncStatus

    data object Running : SyncStatus

    data class Completed(val at: Long, val report: SyncReport) : SyncStatus
}

/**
 * The app's entry point to cloud sync: one place that owns "is a round already running", the
 * last result, and the sign-out sequencing.
 *
 * @param clock Time source for the completion timestamp, injectable for tests.
 */
class SyncManager(
    private val authGateway: AuthGateway,
    private val engine: SyncEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val accountState: Flow<AccountState> = authGateway.state

    val isConfigured: Boolean
        get() = authGateway.isConfigured

    /**
     * Runs one sync round, or returns immediately if one is already in flight.
     *
     * The mutex matters because sync has three triggers — app start, the manual button, and the
     * background worker — which can easily overlap. Two concurrent rounds would race on the
     * same watermarks and could advance one past rows the other never pushed.
     */
    suspend fun syncNow(): SyncReport {
        if (!authGateway.isConfigured) {
            return SyncReport(outcome = SyncOutcome.SignedOut)
        }
        if (!mutex.tryLock()) {
            return SyncReport(outcome = SyncOutcome.Success)
        }
        return try {
            _status.value = SyncStatus.Running
            val report = engine.sync()
            _status.value = SyncStatus.Completed(clock(), report)
            report
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Signs out, then clears sync bookkeeping.
     *
     * Order matters: dropping cursors before the session is actually gone would leave a signed-
     * in device that believes it has never synced, and the next round would re-pull everything.
     * Local books and notes survive either way — they never required an account.
     */
    suspend fun signOut(): Result<Unit> =
        authGateway.signOut().onSuccess {
            mutex.withLock {
                engine.resetState()
                _status.value = SyncStatus.Idle
            }
        }
}
