package app.berilo.reader.interpretation

import app.berilo.reader.llm.LlmClient
import app.berilo.reader.llm.LlmError
import app.berilo.reader.llm.LlmResult
import app.berilo.reader.settings.FakeKeyValueStore
import app.berilo.reader.settings.LlmSettings
import app.berilo.reader.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
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

/** [LlmClient] test double that suspends until released — used to prove `dismiss()` cancels
 * an in-flight interpretation rather than letting a late result reopen the sheet. */
private class SuspendingLlmClient : LlmClient {
    val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<LlmResult>()

    fun release(result: LlmResult) = release.complete(result)

    override suspend fun complete(prompt: String, system: String?, maxTokens: Int?): LlmResult {
        started.complete(Unit)
        return release.await()
    }
}

/** Same `Dispatchers.setMain`/`resetMain` pattern as `DictionaryViewModelTest` — `viewModelScope.launch`
 * failures are silently swallowed without it (`docs/findings.md`). */
@OptIn(ExperimentalCoroutinesApi::class)
class InterpretationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(client: LlmClient, dao: FakeInterpretationDao = FakeInterpretationDao()) =
        InterpretationRepository(
            dao = dao,
            service = InterpretationService(createClient = { client }),
            ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
        )

    private fun settingsRepository() = SettingsRepository(FakeKeyValueStore()).also { it.save(LlmSettings(model = "gpt-5-mini")) }

    @Test
    fun `initial state is Idle`() {
        val viewModel = InterpretationViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))

        assertEquals(InterpretationUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `interpret shows Loading immediately, before the coroutine resumes`() =
        runTest(testDispatcher) {
            val viewModel = InterpretationViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))

            viewModel.interpret("A passage about the fall of a city.", "The Last Reign")

            assertEquals(InterpretationUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `interpret success surfaces the text, passage, cost, and cache flag`() =
        runTest(testDispatcher) {
            val viewModel = InterpretationViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))

            viewModel.interpret("A passage about the fall of a city.", "The Last Reign")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is InterpretationUiState.Success)
            state as InterpretationUiState.Success
            assertEquals("This passage foreshadows the betrayal.", state.text)
            assertEquals("A passage about the fall of a city.", state.passage)
            assertEquals(false, state.fromCache)
        }

    @Test
    fun `a second identical interpret surfaces fromCache true`() =
        runTest(testDispatcher) {
            val client = FakeLlmClient(Result.success(sampleResult()))
            val sharedRepository = repository(client)
            val viewModel1 = InterpretationViewModel(settingsRepository(), sharedRepository)
            viewModel1.interpret("A passage about the fall of a city.", "The Last Reign")
            advanceUntilIdle()

            val viewModel2 = InterpretationViewModel(settingsRepository(), sharedRepository)
            viewModel2.interpret("A passage about the fall of a city.", "The Last Reign")
            advanceUntilIdle()

            val state = viewModel2.uiState.value
            assertTrue(state is InterpretationUiState.Success)
            assertTrue((state as InterpretationUiState.Success).fromCache)
        }

    @Test
    fun `NETWORK error maps to a distinct Error state`() =
        runTest(testDispatcher) {
            val failing = FakeLlmClient(Result.failure(LlmError("offline", LlmError.Kind.NETWORK)))
            val viewModel = InterpretationViewModel(settingsRepository(), repository(failing))

            viewModel.interpret("A passage.", "A Book")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is InterpretationUiState.Error)
            assertEquals(InterpretationErrorKind.NETWORK, (state as InterpretationUiState.Error).kind)
        }

    @Test
    fun `AUTH error maps to a distinct Error state from NETWORK`() =
        runTest(testDispatcher) {
            val failing = FakeLlmClient(Result.failure(LlmError("no key — check Settings", LlmError.Kind.AUTH)))
            val viewModel = InterpretationViewModel(settingsRepository(), repository(failing))

            viewModel.interpret("A passage.", "A Book")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is InterpretationUiState.Error)
            assertEquals(InterpretationErrorKind.AUTH, (state as InterpretationUiState.Error).kind)
        }

    @Test
    fun `dismiss returns to Idle`() =
        runTest(testDispatcher) {
            val viewModel = InterpretationViewModel(settingsRepository(), repository(FakeLlmClient(Result.success(sampleResult()))))
            viewModel.interpret("A passage.", "A Book")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is InterpretationUiState.Success)

            viewModel.dismiss()

            assertEquals(InterpretationUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `dismiss cancels an in-flight interpretation so a late result cannot reopen the sheet`() =
        runTest(testDispatcher) {
            val client = SuspendingLlmClient()
            val viewModel = InterpretationViewModel(settingsRepository(), repository(client))

            viewModel.interpret("A passage.", "A Book")
            advanceUntilIdle()
            client.started.await()
            assertEquals(InterpretationUiState.Loading, viewModel.uiState.value)

            viewModel.dismiss()
            assertEquals(InterpretationUiState.Idle, viewModel.uiState.value)

            // The in-flight call finally "resolves" after dismiss — its result must never land.
            client.release(sampleResult())
            advanceUntilIdle()

            assertEquals(InterpretationUiState.Idle, viewModel.uiState.value)
        }

    private fun sampleResult() =
        LlmResult(
            text = "This passage foreshadows the betrayal.",
            inputTokens = 40,
            outputTokens = 120,
            costEur = 0.0021,
            model = "gpt-5-mini",
        )
}
