package app.berilo.reader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `load returns defaults when the store is empty`() {
        val repository = SettingsRepository(FakeKeyValueStore())

        val settings = repository.load()

        assertEquals(DEFAULT_MODEL, settings.model)
        assertEquals(DEFAULT_TARGET_LANG, settings.targetLang)
        assertNull(settings.openaiKey)
        assertNull(settings.anthropicKey)
        assertNull(settings.dictionaryModel)
        assertNull(settings.interpretationModel)
    }

    @Test
    fun `save then load round-trips every field`() {
        val repository = SettingsRepository(FakeKeyValueStore())
        val settings =
            LlmSettings(
                openaiKey = "test-openai-key-abc",
                anthropicKey = "test-anthropic-key-xyz",
                model = "claude-sonnet-4-5",
                targetLang = "de",
                dictionaryModel = "gpt-5-nano",
                interpretationModel = "claude-haiku-4-5",
            )

        repository.save(settings)

        assertEquals(settings, repository.load())
    }

    @Test
    fun `saving a null key clears a previously saved one`() {
        val store = FakeKeyValueStore()
        val repository = SettingsRepository(store)
        repository.save(LlmSettings(openaiKey = "test-openai-key-abc"))

        repository.save(LlmSettings(openaiKey = null))

        assertNull(repository.load().openaiKey)
    }
}
