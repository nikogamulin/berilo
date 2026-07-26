package app.berilo.reader.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.llm.createLlmClient
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
    val translationModel: String? = null,
    val openaiTestState: KeyTestState = KeyTestState.Idle,
    val anthropicTestState: KeyTestState = KeyTestState.Idle,
) {
    /** Model a whole-book translation would bill against — the override, else [model]. */
    val resolvedTranslationModel: String
        get() = translationModel ?: model
}

/**
 * Settings screen view model. Persists [LlmSettings] via [SettingsRepository] on every
 * field change (no explicit Save button — matches the "one screen, no chrome" guideline
 * in `docs/design_guidelines.md`) and drives the per-provider "Test key" doctor-style
 * smoke call through an injectable [LlmClient] factory, so tests exercise the same
 * success/failure branching without ever hitting the network.
 *
 * **The factory is `(LlmSettings) -> LlmClient`, defaulted to [createLlmClient], not a
 * per-provider `(apiKey) -> LlmClient`.** Constructing [app.berilo.reader.llm.OpenAiClient] /
 * [app.berilo.reader.llm.AnthropicClient] directly here bypassed [createLlmClient]'s pricing
 * pre-flight, which is the guard that makes a model with no pricing entry fail *before* a call
 * is billed rather than after (review finding 6). It was harmless only because the two key-test
 * models are hardcoded constants; routing through the factory closes the second construction
 * site so the guard has no bypass at all (B7).
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val clientFactory: (LlmSettings) -> LlmClient = ::createLlmClient,
) : ViewModel() {

    /**
     * The last full [LlmSettings] written or loaded.
     *
     * Held so [persistCurrentState] can `copy()` the edited fields onto it instead of building
     * a fresh object from the UI state. A fresh construction silently defaults every field the
     * screen does not carry, which is exactly how `dictionaryModel` and `interpretationModel`
     * were erased on every keystroke (fixed in B7). Keeping the whole object means a field
     * added to [LlmSettings] later survives by construction rather than by remembering.
     */
    private var persisted: LlmSettings = repository.load()

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): SettingsUiState =
        SettingsUiState(
            openaiKey = persisted.openaiKey.orEmpty(),
            anthropicKey = persisted.anthropicKey.orEmpty(),
            model = persisted.model,
            targetLang = persisted.targetLang,
            translationModel = persisted.translationModel,
        )

    fun onOpenAiKeyChanged(value: String) =
        updateAndPersist { it.copy(openaiKey = value, openaiTestState = KeyTestState.Idle) }

    fun onAnthropicKeyChanged(value: String) =
        updateAndPersist { it.copy(anthropicKey = value, anthropicTestState = KeyTestState.Idle) }

    fun onModelChanged(model: String) = updateAndPersist { it.copy(model = model) }

    fun onTargetLangChanged(targetLang: String) = updateAndPersist { it.copy(targetLang = targetLang) }

    /**
     * Sets the whole-book translation model override (B7).
     *
     * @param model The override, or `null` to fall back to [SettingsUiState.model].
     */
    fun onTranslationModelChanged(model: String?) = updateAndPersist { it.copy(translationModel = model) }

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
                    val client = clientFactory(keyTestSettings(provider, apiKey))
                    client.complete(
                        prompt =
                            "Translate the following sentence into ${state.targetLang}. " +
                                "Reply with only the translation:\n\n$KEY_TEST_SENTENCE",
                    )
                }
            setTestState(provider, outcome.toKeyTestState())
        }
    }

    /**
     * Minimal [LlmSettings] describing one provider's key-test call.
     *
     * Carries only the key being tested and that provider's fixed smoke model, so
     * [createLlmClient] routes by prefix to the intended vendor and pre-flights its pricing —
     * never the user's saved [SettingsUiState.model], which may belong to the other provider.
     */
    private fun keyTestSettings(provider: LlmProvider, apiKey: String): LlmSettings =
        when (provider) {
            LlmProvider.OPENAI -> LlmSettings(openaiKey = apiKey, model = KEY_TEST_OPENAI_MODEL)
            LlmProvider.ANTHROPIC -> LlmSettings(anthropicKey = apiKey, model = KEY_TEST_ANTHROPIC_MODEL)
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

    /**
     * Writes the edited fields onto [persisted] and saves the whole object.
     *
     * `copy()`, never `LlmSettings(...)`: see [persisted]. Every field this screen does not
     * edit rides along untouched.
     */
    private fun persistCurrentState() {
        val state = _uiState.value
        persisted =
            persisted.copy(
                openaiKey = state.openaiKey.ifBlank { null },
                anthropicKey = state.anthropicKey.ifBlank { null },
                model = state.model,
                targetLang = state.targetLang,
                translationModel = state.translationModel,
            )
        repository.save(persisted)
    }

    class Factory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
    }
}
