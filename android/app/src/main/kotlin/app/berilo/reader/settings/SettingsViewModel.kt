package app.berilo.reader.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.llm.AnthropicClient
import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.llm.OpenAiClient
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Fixed smoke-test sentence for the doctor-style "Test key" call (mirrors
 * `translator/berilo/providers/doctor.py`'s `DOCTOR_SENTENCE`). */
private const val KEY_TEST_SENTENCE = "The library was quiet, but every book in it was shouting."
private const val KEY_TEST_OPENAI_MODEL = "gpt-5-mini"
private const val KEY_TEST_ANTHROPIC_MODEL = "claude-haiku-4-5"
private const val UNKNOWN_TEST_FAILURE_MESSAGE = "Key test failed — check your connection and try again."

/** Which provider a "Test key" action or field edit applies to. */
enum class LlmProvider { OPENAI, ANTHROPIC }

/** State of a per-provider "Test key" doctor-style smoke call. */
sealed interface KeyTestState {
    data object Idle : KeyTestState

    data object Testing : KeyTestState

    data class Success(val summary: String) : KeyTestState

    data class Failure(val message: String) : KeyTestState
}

/** UI state for the Settings screen. Keys are held as plain strings only in memory —
 * [SettingsViewModel] persists them to [SettingsRepository] (backed by
 * [EncryptedKeyValueStore] in production) on every change. */
data class SettingsUiState(
    val openaiKey: String = "",
    val anthropicKey: String = "",
    val model: String = DEFAULT_MODEL,
    val targetLang: String = DEFAULT_TARGET_LANG,
    val openaiTestState: KeyTestState = KeyTestState.Idle,
    val anthropicTestState: KeyTestState = KeyTestState.Idle,
)

/**
 * Settings screen view model. Persists [LlmSettings] via [SettingsRepository] on every
 * field change (no explicit Save button — matches the "one screen, no chrome" guideline
 * in `docs/design_guidelines.md`) and drives the per-provider "Test key" doctor-style
 * smoke call through injectable [LlmClient] factories, so tests exercise the same
 * success/failure branching without ever hitting the network.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val openAiClientFactory: (apiKey: String) -> LlmClient = { OpenAiClient(it, KEY_TEST_OPENAI_MODEL) },
    private val anthropicClientFactory: (apiKey: String) -> LlmClient = { AnthropicClient(it, KEY_TEST_ANTHROPIC_MODEL) },
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): SettingsUiState {
        val settings = repository.load()
        return SettingsUiState(
            openaiKey = settings.openaiKey.orEmpty(),
            anthropicKey = settings.anthropicKey.orEmpty(),
            model = settings.model,
            targetLang = settings.targetLang,
        )
    }

    fun onOpenAiKeyChanged(value: String) =
        updateAndPersist { it.copy(openaiKey = value, openaiTestState = KeyTestState.Idle) }

    fun onAnthropicKeyChanged(value: String) =
        updateAndPersist { it.copy(anthropicKey = value, anthropicTestState = KeyTestState.Idle) }

    fun onModelChanged(model: String) = updateAndPersist { it.copy(model = model) }

    fun onTargetLangChanged(targetLang: String) = updateAndPersist { it.copy(targetLang = targetLang) }

    /** Runs the doctor-style one-sentence test call for [provider]'s currently-entered key. */
    fun testKey(provider: LlmProvider) {
        val state = _uiState.value
        val apiKey = if (provider == LlmProvider.OPENAI) state.openaiKey else state.anthropicKey
        if (apiKey.isBlank()) {
            setTestState(provider, KeyTestState.Failure("Enter an API key first."))
            return
        }
        setTestState(provider, KeyTestState.Testing)
        viewModelScope.launch {
            val outcome =
                runCatching {
                    val client = if (provider == LlmProvider.OPENAI) openAiClientFactory(apiKey) else anthropicClientFactory(apiKey)
                    client.complete(
                        prompt =
                            "Translate the following sentence into ${state.targetLang}. " +
                                "Reply with only the translation:\n\n$KEY_TEST_SENTENCE",
                    )
                }
            setTestState(provider, outcome.toKeyTestState())
        }
    }

    private fun Result<LlmResult>.toKeyTestState(): KeyTestState =
        fold(
            onSuccess = { result ->
                KeyTestState.Success("${result.text.trim()} (€${String.format(Locale.US, "%.4f", result.costEur)})")
            },
            onFailure = { error ->
                val message = (error as? LlmError)?.message ?: UNKNOWN_TEST_FAILURE_MESSAGE
                KeyTestState.Failure(message)
            },
        )

    private fun setTestState(provider: LlmProvider, state: KeyTestState) {
        _uiState.update {
            if (provider == LlmProvider.OPENAI) it.copy(openaiTestState = state) else it.copy(anthropicTestState = state)
        }
    }

    private fun updateAndPersist(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
        persistCurrentState()
    }

    private fun persistCurrentState() {
        val state = _uiState.value
        repository.save(
            LlmSettings(
                openaiKey = state.openaiKey.ifBlank { null },
                anthropicKey = state.anthropicKey.ifBlank { null },
                model = state.model,
                targetLang = state.targetLang,
            ),
        )
    }

    class Factory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
    }
}
