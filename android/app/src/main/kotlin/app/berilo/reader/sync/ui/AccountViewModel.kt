package app.berilo.reader.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.sync.SyncManager
import app.berilo.reader.sync.SyncStatus
import app.berilo.reader.sync.auth.AccountState
import app.berilo.reader.sync.auth.AuthGateway
import app.berilo.reader.sync.auth.AuthStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which leg of sign-in the screen is showing. */
enum class SignInStep {
    /** Asking for the email address (and optionally a password). */
    IDENTIFY,

    /** A one-time code has been sent and is being awaited. */
    CODE,
}

data class AccountUiState(
    val account: AccountState = AccountState.Loading,
    val step: SignInStep = SignInStep.IDENTIFY,
    val email: String = "",
    val code: String = "",
    val password: String = "",
    val usePassword: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val syncStatus: SyncStatus = SyncStatus.Idle,
)

/**
 * Drives the account screen (S3.2).
 *
 * Sign-in defaults to an emailed one-time code, with a password as an opt-in alternative. On
 * e-ink that ordering is the accessible one: a code is six digits typed once, while a password
 * means a long secret entered on a keyboard that redraws at a fraction of a second per
 * keystroke, with no reliable way to check what was typed.
 */
class AccountViewModel(
    private val authGateway: AuthGateway,
    private val syncManager: SyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authGateway.state.collect { account ->
                _uiState.update {
                    // Signing in ends the code leg and clears whatever was typed, so the
                    // credential does not linger in memory behind the signed-in view.
                    if (account is AccountState.SignedIn) {
                        it.copy(
                            account = account,
                            step = SignInStep.IDENTIFY,
                            code = "",
                            password = "",
                            error = null,
                        )
                    } else {
                        it.copy(account = account)
                    }
                }
            }
        }
        viewModelScope.launch {
            syncManager.status.collect { status -> _uiState.update { it.copy(syncStatus = status) } }
        }
    }

    fun onEmailChanged(value: String) = _uiState.update { it.copy(email = value, error = null) }

    fun onCodeChanged(value: String) = _uiState.update { it.copy(code = value, error = null) }

    fun onPasswordChanged(value: String) =
        _uiState.update { it.copy(password = value, error = null) }

    fun onUsePasswordChanged(value: Boolean) =
        _uiState.update { it.copy(usePassword = value, error = null) }

    /** Returns to the email leg, e.g. after a typo in the address the code went to. */
    fun onStartOver() =
        _uiState.update { it.copy(step = SignInStep.IDENTIFY, code = "", error = null, notice = null) }

    fun onSignIn() {
        val state = _uiState.value
        val email = state.email.trim()
        if (email.isEmpty()) return
        if (state.usePassword) {
            submit { authGateway.signInWithPassword(email, state.password) }
        } else {
            submit { authGateway.startEmailCodeSignIn(email) }
        }
    }

    /** Creates a new account for the typed address; Clerk verifies it with the same code flow. */
    fun onSignUp() {
        val email = _uiState.value.email.trim()
        if (email.isEmpty()) return
        submit { authGateway.startEmailCodeSignUp(email) }
    }

    fun onSubmitCode() {
        val code = _uiState.value.code.trim()
        if (code.isEmpty()) return
        submit { authGateway.verifyCode(code) }
    }

    fun onSignOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            val result = syncManager.signOut()
            _uiState.update {
                it.copy(
                    busy = false,
                    error = result.exceptionOrNull()?.message,
                    email = "",
                    password = "",
                    code = "",
                )
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch { syncManager.syncNow() }
    }

    /**
     * Runs one auth call, mapping its outcome onto the screen.
     *
     * Every branch either advances the step or shows a message — a failure never leaves the
     * button spinning, which on a slow-refresh screen is indistinguishable from a frozen app.
     */
    private fun submit(call: suspend () -> Result<AuthStep>) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, notice = null) }
            val result = call()
            _uiState.update { state ->
                result.fold(
                    onSuccess = { step ->
                        when (step) {
                            AuthStep.Complete ->
                                state.copy(busy = false, step = SignInStep.IDENTIFY)
                            is AuthStep.NeedsCode ->
                                state.copy(
                                    busy = false,
                                    step = SignInStep.CODE,
                                    email = step.email.ifBlank { state.email },
                                )
                            is AuthStep.Unsupported ->
                                state.copy(
                                    busy = false,
                                    error =
                                        "This account needs a step the app can't do yet " +
                                            "(${step.status}). Finish signing in on berilo.app.",
                                )
                        }
                    },
                    onFailure = { error ->
                        state.copy(busy = false, error = error.message ?: "Something went wrong.")
                    },
                )
            }
            if (_uiState.value.account is AccountState.SignedIn) syncManager.syncNow()
        }
    }

    class Factory(
        private val authGateway: AuthGateway,
        private val syncManager: SyncManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AccountViewModel(authGateway, syncManager) as T
    }
}
