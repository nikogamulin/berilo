package app.berilo.reader.reader

import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * Serializes a Readium [Locator] to and from the JSON string persisted in
 * `BookEntity.progressionJson`.
 *
 * The round-trip is Readium's own canonical Locator JSON (href, type, title,
 * locations, text), so a position saved by one app version restores exactly in
 * the next. Decoding never throws: malformed or stale JSON yields null and the
 * reader opens at the start instead of crashing.
 */
object LocatorCodec {

    /** Encodes [locator] to its canonical Readium Locator JSON string. */
    fun encode(locator: Locator): String = locator.toJSON().toString()

    /**
     * Decodes a Locator JSON string, or returns null if [json] is blank,
     * malformed, or not a valid Locator.
     */
    fun decode(json: String?): Locator? {
        if (json.isNullOrBlank()) return null
        return try {
            Locator.fromJSON(JSONObject(json))
        } catch (_: JSONException) {
            null
        }
    }
}
