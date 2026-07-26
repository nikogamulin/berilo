package app.berilo.reader.settings

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val FAKE_KEY = "test-openai-key-abc"

/** [LlmClient] test double that never touches the network — success or a fixed [LlmError]. */
private class FakeLlmClient(private val outcome: Result<LlmResult>) : LlmClient {
    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult = outcome.getOrThrow()
}

/**
 * Records the [LlmSettings] each key test was routed with, then serves a fixed outcome.
 *
 * The recording is the point: B7's fix routes key tests through
 * [app.berilo.reader.llm.createLlmClient] instead of constructing `OpenAiClient`/`AnthropicClient`
 * directly, and the only way to see that from outside is to inspect what the factory was handed.
 */
private class RecordingClientFactory(
    private val outcome: Result<LlmResult> = Result.success(
        LlmResult("ok", inputTokens = 1, outputTokens = 1, costEur = 0.0, model = "gpt-5-mini"),
    ),
) : (LlmSettings) -> LlmClient {
    val requested: MutableList<LlmSettings> = mutableListOf()

    override fun invoke(settings: LlmSettings): LlmClient {
        requested.add(settings)
        return FakeLlmClient(outcome)
    }
}

/** [SettingsViewModel.testKey] runs on `viewModelScope`, which needs `Dispatchers.Main` set
 * via kotlinx-coroutines-test (no Robolectric) — same pattern as `LibraryViewModelTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads persisted settings`() {
        val repository = SettingsRepository(FakeKeyValueStore())
        repository.save(LlmSettings(openaiKey = FAKE_KEY, model = "claude-sonnet-4-5", targetLang = "de"))

        val viewModel = SettingsViewModel(repository)

        val state = viewModel.uiState.value
        assertEquals(FAKE_KEY, state.openaiKey)
        assertEquals("claude-sonnet-4-5", state.model)
        assertEquals("de", state.targetLang)
    }

    @Test
    fun `changing a field persists it immediately, no explicit save`() {
        val store = FakeKeyValueStore()
        val repository = SettingsRepository(store)
        val viewModel = SettingsViewModel(repository)

        viewModel.onOpenAiKeyChanged(FAKE_KEY)
        viewModel.onModelChanged("gpt-5")
        viewModel.onTargetLangChanged("fr")

        val reloaded = SettingsRepository(store).load()
        assertEquals(FAKE_KEY, reloaded.openaiKey)
        assertEquals("gpt-5", reloaded.model)
        assertEquals("fr", reloaded.targetLang)
    }

    @Test
    fun `testKey with a blank key fails without calling the client`() =
        runTest(testDispatcher) {
            var called = false
            val viewModel =
                SettingsViewModel(
                    repository = SettingsRepository(FakeKeyValueStore()),
                    clientFactory = { called = true; FakeLlmClient(Result.success(sampleResult())) },
                )

            viewModel.testKey(LlmProvider.OPENAI)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.openaiTestState is KeyTestState.Failure)
            assertTrue(!called)
        }

    @Test
    fun `testKey success surfaces the translation and cost`() =
        runTest(testDispatcher) {
            val viewModel =
                SettingsViewModel(
                    repository = SettingsRepository(FakeKeyValueStore()),
                    clientFactory = { FakeLlmClient(Result.success(sampleResult())) },
                )
            viewModel.onOpenAiKeyChanged(FAKE_KEY)

            viewModel.testKey(LlmProvider.OPENAI)
            advanceUntilIdle()

            val state = viewModel.uiState.value.openaiTestState
            assertTrue(state is KeyTestState.Success)
            assertTrue((state as KeyTestState.Success).summary.contains("Knjižnica"))
        }

    @Test
    fun `testKey failure surfaces the LlmError message, never the raw key`() =
        runTest(testDispatcher) {
            val viewModel =
                SettingsViewModel(
                    repository = SettingsRepository(FakeKeyValueStore()),
                    clientFactory = {
                        FakeLlmClient(Result.failure(LlmError("Invalid API key — check Settings", LlmError.Kind.AUTH)))
                    },
                )
            viewModel.onAnthropicKeyChanged("test-anthropic-key-xyz")

            viewModel.testKey(LlmProvider.ANTHROPIC)
            advanceUntilIdle()

            val state = viewModel.uiState.value.anthropicTestState
            assertTrue(state is KeyTestState.Failure)
            val message = (state as KeyTestState.Failure).message
            assertTrue(message.contains("Settings"))
            assertTrue(!message.contains("test-anthropic-key-xyz"))
        }

    @Test
    fun `editing the key clears a previous test result`() =
        runTest(testDispatcher) {
            val viewModel =
                SettingsViewModel(
                    repository = SettingsRepository(FakeKeyValueStore()),
                    clientFactory = { FakeLlmClient(Result.success(sampleResult())) },
                )
            viewModel.onOpenAiKeyChanged(FAKE_KEY)
            viewModel.testKey(LlmProvider.OPENAI)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.openaiTestState is KeyTestState.Success)

            viewModel.onOpenAiKeyChanged("$FAKE_KEY-edited")

            assertEquals(KeyTestState.Idle, viewModel.uiState.value.openaiTestState)
        }

    // ---------------------------------------------------------------------------------------
    // B7 defect 1: persistCurrentState() dropped the per-feature model overrides.
    // ---------------------------------------------------------------------------------------

    /**
     * Editing any field must not erase the overrides this screen does not edit.
     *
     * The defect: `persistCurrentState()` built a **fresh** `LlmSettings(...)` from the four
     * fields the UI carries, so `dictionaryModel` and `interpretationModel` fell back to their
     * `null` defaults and were written away on every keystroke — an S2.4/S2.5 setting silently
     * destroyed by typing in S2.3's target-language box.
     *
     * **Mutation-proof:** restore the `LlmSettings(...)` construction in place of the `copy()`
     * and this fails on the first assertion. A `translationModel` field added to that same
     * fresh construction would have inherited the defect, which is why the fix is the `copy()`
     * and not a third hand-carried parameter.
     */
    @Test
    fun `editing one field preserves every model override, including ones this screen never shows`() {
        val store = FakeKeyValueStore()
        SettingsRepository(store).save(
            LlmSettings(
                openaiKey = FAKE_KEY,
                model = "gpt-5-mini",
                targetLang = "sl",
                dictionaryModel = "gpt-5-nano",
                interpretationModel = "claude-haiku-4-5",
                translationModel = "gpt-5",
            ),
        )
        val viewModel = SettingsViewModel(SettingsRepository(store))

        viewModel.onTargetLangChanged("de")

        val reloaded = SettingsRepository(store).load()
        assertEquals("dictionaryModel survived", "gpt-5-nano", reloaded.dictionaryModel)
        assertEquals("interpretationModel survived", "claude-haiku-4-5", reloaded.interpretationModel)
        assertEquals("translationModel survived", "gpt-5", reloaded.translationModel)
        assertEquals("and the edit landed", "de", reloaded.targetLang)
    }

    /** The translation override round-trips through the UI state and the store. */
    @Test
    fun `translationModel is editable, persisted and clearable`() {
        val store = FakeKeyValueStore()
        val viewModel = SettingsViewModel(SettingsRepository(store))
        assertNull("no override by default", viewModel.uiState.value.translationModel)
        assertEquals(
            "so a run bills the default model",
            DEFAULT_MODEL,
            viewModel.uiState.value.resolvedTranslationModel,
        )

        viewModel.onTranslationModelChanged("claude-sonnet-4-5")
        assertEquals("claude-sonnet-4-5", SettingsRepository(store).load().translationModel)
        assertEquals("claude-sonnet-4-5", viewModel.uiState.value.resolvedTranslationModel)

        viewModel.onTranslationModelChanged(null)
        assertNull("cleared back to the default", SettingsRepository(store).load().translationModel)
    }

    // ---------------------------------------------------------------------------------------
    // B7 defect 2: the key test bypassed createLlmClient's pricing pre-flight.
    // ---------------------------------------------------------------------------------------

    /**
     * The key test goes through the `(LlmSettings) -> LlmClient` factory — i.e. through
     * [app.berilo.reader.llm.createLlmClient] in production — carrying only the key under test
     * and that provider's fixed smoke model.
     *
     * The defect: `SettingsViewModel` constructed `OpenAiClient`/`AnthropicClient` **directly**,
     * so this was the one construction site that skipped `createLlmClient`'s pricing pre-flight
     * — the guard that makes an unpriced model fail before a call is billed rather than after
     * (review finding 6). Harmless only while those two models stayed hardcoded constants.
     *
     * **Mutation-proof:** route the OpenAI branch through the user's saved `state.model` instead
     * of [KEY_TEST_OPENAI_MODEL] and the model assertion fails; drop the key from the settings
     * handed to the factory and the key assertion fails — and in production that shape would
     * make `createLlmClient` raise `AUTH` for a key the user had just typed.
     */
    @Test
    fun `the key test routes through the client factory, per provider, with only that key`() =
        runTest(testDispatcher) {
            val factory = RecordingClientFactory()
            val repository = SettingsRepository(FakeKeyValueStore())
            repository.save(LlmSettings(model = "claude-sonnet-4-5"))
            val viewModel = SettingsViewModel(repository, factory)
            viewModel.onOpenAiKeyChanged(FAKE_KEY)
            viewModel.onAnthropicKeyChanged("test-anthropic-key-xyz")

            viewModel.testKey(LlmProvider.OPENAI)
            advanceUntilIdle()
            viewModel.testKey(LlmProvider.ANTHROPIC)
            advanceUntilIdle()

            assertEquals("one factory call per key test", 2, factory.requested.size)

            val openai = factory.requested[0]
            assertEquals("the OpenAI smoke model, not the saved one", "gpt-5-mini", openai.model)
            assertEquals("carries the OpenAI key", FAKE_KEY, openai.openaiKey)
            assertNull("and only that key", openai.anthropicKey)

            val anthropic = factory.requested[1]
            assertEquals("the Anthropic smoke model", "claude-haiku-4-5", anthropic.model)
            assertEquals("carries the Anthropic key", "test-anthropic-key-xyz", anthropic.anthropicKey)
            assertNull("and only that key", anthropic.openaiKey)
        }

    private fun sampleResult() =
        LlmResult(text = "Knjižnica je bila tiha.", inputTokens = 40, outputTokens = 12, costEur = 0.00003128, model = "gpt-5-mini")
}
