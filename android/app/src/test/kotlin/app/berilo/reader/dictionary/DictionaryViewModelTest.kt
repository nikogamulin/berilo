package app.berilo.reader.dictionary

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.settings.FakeKeyValueStore
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [LlmClient] test double: never touches the network — a fixed success or [LlmError]. */
private class FakeLlmClient(private val outcome: Result<LlmResult>) : LlmClient {
    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult = outcome.getOrThrow()
}

/** Same `Dispatchers.setMain`/`resetMain` pattern as `SettingsViewModelTest` — `viewModelScope.launch`
 * failures are silently swallowed without it (`docs/findings.md`). */
@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(client: LlmClient) =
        DictionaryRepository(
            dao = FakeDictionaryDao(),
            service = DictionaryService(createClient = { client }),
            ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
        )

    private fun settingsRepository() = SettingsRepository(FakeKeyValueStore()).also { it.save(LlmSettings(model = "gpt-5-mini")) }

    @Test
    fun `initial state is Idle`() {
        val viewModel = DictionaryViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))

        assertEquals(DictionaryUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `lookup shows Loading immediately, before the coroutine resumes`() =
        runTest(testDispatcher) {
            val viewModel = DictionaryViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))

            viewModel.lookup(SelectionContext("bank", "She sat by the river bank."))

            assertEquals(DictionaryUiState.Loading("bank"), viewModel.uiState.value)
        }

    @Test
    fun `lookup success surfaces the definition, sentence, cost, and cache flag`() =
        runTest(testDispatcher) {
            val viewModel = DictionaryViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))

            viewModel.lookup(SelectionContext("bank", "She sat by the river bank."))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is DictionaryUiState.Success)
            state as DictionaryUiState.Success
            assertEquals("banka", state.definition.definition)
            assertEquals("She sat by the river bank.", state.sentence)
            assertEquals(false, state.fromCache)
        }

    @Test
    fun `a second identical lookup surfaces fromCache true`() =
        runTest(testDispatcher) {
            val client = FakeLlmClient(Result.success(sampleResult()))
            val sharedRepository = repository(client)
            val viewModel1 = DictionaryViewModel(settingsRepository(), sharedRepository)
            viewModel1.lookup(SelectionContext("bank", "She sat by the river bank."))
            advanceUntilIdle()

            val viewModel2 = DictionaryViewModel(settingsRepository(), sharedRepository)
            viewModel2.lookup(SelectionContext("bank", "She sat by the river bank."))
            advanceUntilIdle()

            val state = viewModel2.uiState.value
            assertTrue(state is DictionaryUiState.Success)
            assertTrue((state as DictionaryUiState.Success).fromCache)
        }

    @Test
    fun `NETWORK error maps to a distinct Error state`() =
        runTest(testDispatcher) {
            val failing = FakeLlmClient(Result.failure(LlmError("offline", LlmError.Kind.NETWORK)))
            val viewModel = DictionaryViewModel(settingsRepository(), repository(failing))

            viewModel.lookup(SelectionContext("bank", "A sentence."))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is DictionaryUiState.Error)
            assertEquals(DictionaryErrorKind.NETWORK, (state as DictionaryUiState.Error).kind)
        }

    @Test
    fun `AUTH error maps to a distinct Error state from NETWORK`() =
        runTest(testDispatcher) {
            val failing = FakeLlmClient(Result.failure(LlmError("no key — check Settings", LlmError.Kind.AUTH)))
            val viewModel = DictionaryViewModel(settingsRepository(), repository(failing))

            viewModel.lookup(SelectionContext("bank", "A sentence."))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is DictionaryUiState.Error)
            assertEquals(DictionaryErrorKind.AUTH, (state as DictionaryUiState.Error).kind)
        }

    @Test
    fun `dismiss returns to Idle`() =
        runTest(testDispatcher) {
            val viewModel = DictionaryViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))
            viewModel.lookup(SelectionContext("bank", "A sentence."))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is DictionaryUiState.Success)

            viewModel.dismiss()

            assertEquals(DictionaryUiState.Idle, viewModel.uiState.value)
        }

    private fun sampleResult() =
        LlmResult(text = "DEFINITION: banka", inputTokens = 5, outputTokens = 5, costEur = 0.0002, model = "gpt-5-mini")
}
