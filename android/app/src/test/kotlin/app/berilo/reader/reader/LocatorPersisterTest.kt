package app.berilo.reader.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Debounce and flush behaviour of the position saver, on virtual time. Each
 * test would fail against a naive "save on every change" implementation: the
 * coalescing test would see 3 saves, and the flush test would see the write
 * delayed past the assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocatorPersisterTest {

    private val debounce = 1_000L

    @Test
    fun `a burst of changes coalesces into a single write of the last value`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val saved = mutableListOf<String>()
            val persister = LocatorPersister(backgroundScope, debounce, dispatcher) { saved += it }

            persister.onChanged("a")
            persister.onChanged("b")
            persister.onChanged("c")

            advanceTimeBy(debounce - 1)
            assertEquals("nothing written before the quiet period elapses", emptyList<String>(), saved)

            advanceTimeBy(2)
            assertEquals(listOf("c"), saved)
        }

    @Test
    fun `well-separated changes each produce a write`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val saved = mutableListOf<String>()
            val persister = LocatorPersister(backgroundScope, debounce, dispatcher) { saved += it }

            persister.onChanged("p1")
            advanceTimeBy(debounce + 1)
            persister.onChanged("p2")
            advanceTimeBy(debounce + 1)

            assertEquals(listOf("p1", "p2"), saved)
        }

    @Test
    fun `flush writes the latest position immediately, bypassing the debounce`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val saved = mutableListOf<String>()
            val persister = LocatorPersister(backgroundScope, debounce, dispatcher) { saved += it }

            persister.onChanged("x")
            persister.flush()
            // Advance far less than the debounce window: only the immediate flush
            // path can have written by now.
            advanceTimeBy(1)

            assertEquals("flush persists x without waiting for the debounce", listOf("x"), saved)
        }

    @Test
    fun `flush is a no-op when no position was ever recorded`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val saved = mutableListOf<String>()
            val persister = LocatorPersister(backgroundScope, debounce, dispatcher) { saved += it }

            persister.flush()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), saved)
        }
}
