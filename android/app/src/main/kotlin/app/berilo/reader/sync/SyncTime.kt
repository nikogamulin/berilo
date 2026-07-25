package app.berilo.reader.sync

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Conversion between the app's epoch-millis timestamps and the wire's UTC ISO-8601 strings
 * (`docs/sync_api.md` §5).
 *
 * The whole DST question is settled here by never letting a local time zone into the format.
 * Room stores epoch millis, which is an absolute instant with no offset; [toWire] renders it as
 * UTC with a `Z` suffix. There is no local-time step in between, so a write made during
 * Europe/Ljubljana's March or October transition — including the hour that repeats — converts
 * to one unambiguous instant, and last-write-wins comparisons stay monotonic across the
 * boundary. Local time appears only where a human reads it, in the UI.
 *
 * The server validates against `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$`;
 * [Instant.toString] emits either whole seconds or exactly 3/6/9 fractional digits, and
 * millisecond precision always lands on 0 or 3 — both accepted.
 */
object SyncTime {

    /** Renders [epochMillis] as a UTC ISO-8601 instant, e.g. `2026-07-25T13:00:00.123Z`. */
    fun toWire(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /**
     * Parses a wire timestamp back to epoch millis, or null if it is unparseable.
     *
     * Returning null rather than throwing is deliberate: a malformed timestamp in one pulled
     * row must not abort the whole sync round. The caller skips that row and keeps going.
     */
    fun fromWire(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
