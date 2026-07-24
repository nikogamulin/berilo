package app.berilo.reader.reader

import android.util.Log
import app.berilo.reader.BuildConfig

/**
 * Page-turn render timing for Rubric R2 (page turn ≤ 150 ms on the Boox). The
 * navigator host calls [pageTurnRequested] when a turn is initiated and
 * [pageTurnRendered] once the new page has painted; the elapsed milliseconds are
 * logged under the "BeriloPerf" tag so `adb logcat -s BeriloPerf` yields the R2
 * measurement series.
 *
 * Off in release builds and cheap when off (no allocation, no formatting).
 * Defaults to [BuildConfig.DEBUG] so the overlay is a debug-only diagnostic; the
 * reader's debug settings can still flip [enabled] at runtime.
 */
object PerfLog {

    const val TAG = "BeriloPerf"

    /** Debug-gated by default; the reader's debug timing-overlay setting can flip it. */
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    /** Returns a start marker (monotonic nanos) for a page-turn request. */
    fun pageTurnRequested(): Long = System.nanoTime()

    /** Logs the render duration for the turn that began at [startNanos]. */
    // Timber is a Readium runtime-only dependency, not on this module's compile
    // classpath; android.util.Log is the available sink for this diagnostic tag.
    @Suppress("LogNotTimber")
    fun pageTurnRendered(startNanos: Long) {
        if (!enabled) return
        Log.d(TAG, "page_turn_render_ms=${renderMillis(startNanos, System.nanoTime())}")
    }

    /**
     * Elapsed whole milliseconds between two [System.nanoTime] marks, floored at
     * zero. Pure so the R2 timing math is unit-testable off-device.
     */
    fun renderMillis(startNanos: Long, endNanos: Long): Long =
        ((endNanos - startNanos).coerceAtLeast(0)) / NANOS_PER_MILLI

    private const val NANOS_PER_MILLI = 1_000_000L
}
