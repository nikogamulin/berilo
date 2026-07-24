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
                    openAiClientFactory = { called = true; FakeLlmClient(Result.success(sampleResult())) },
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
                    openAiClientFactory = { FakeLlmClient(Result.success(sampleResult())) },
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
                    anthropicClientFactory = {
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
                    openAiClientFactory = { FakeLlmClient(Result.success(sampleResult())) },
                )
            viewModel.onOpenAiKeyChanged(FAKE_KEY)
            viewModel.testKey(LlmProvider.OPENAI)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.openaiTestState is KeyTestState.Success)

            viewModel.onOpenAiKeyChanged("$FAKE_KEY-edited")

            assertEquals(KeyTestState.Idle, viewModel.uiState.value.openaiTestState)
        }

    private fun sampleResult() =
        LlmResult(text = "Knjižnica je bila tiha.", inputTokens = 40, outputTokens = 12, costEur = 0.00003128, model = "gpt-5-mini")
}
