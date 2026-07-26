package app.berilo.reader.translate.epub

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Repairs R0-R3 of the leniency rule, ported from `_repair_xhtml` in `normalize/epub.py`.
 *
 * The rule is normative and is reproduced here verbatim in intent, because the two
 * implementations must recover exactly the same set of documents:
 *
 * 1. Parse the document bytes **strictly**. On success that tree is used and nothing here
 *    runs — which is what keeps `book_hash` stable on already-translated books, and is
 *    asserted by [XhtmlParser] being constructible with a repair that must never be called.
 * 2. On a parse error, decode and repair (R0-R3 below, in this order, each applied over the
 *    whole document), then parse the result strictly.
 * 3. If that also fails, raise [EpubParseException] naming the document. A document is never
 *    skipped: silent loss is the defect.
 *
 * - **R0** Decode as UTF-8, falling back to ISO-8859-1 (never lossy, never raises), then drop
 *   a leading `<?xml ... ?>` declaration so the repaired text can be re-encoded as UTF-8
 *   without contradicting a declared encoding. A DOCTYPE is left untouched.
 * - **R1** `&NAME;` where NAME is a known HTML5 entity other than the five XML built-ins
 *   becomes `&#CODEPOINT;`. This is the bare `&nbsp;` case: legal HTML, undeclared in XML.
 * - **R2** An `&` that does not begin a syntactically valid reference becomes `&amp;`. Runs
 *   after R1, so every entity R1 recognized is already numeric and is preserved.
 * - **R3** `<TAG ...>` where TAG is an HTML void element and the tag does not already end in
 *   `/>` becomes `<TAG .../>`. The attribute scan is quote-aware, so a literal `>` inside an
 *   attribute value (`alt="3 &gt; 2"` written literally, which is legal XML) does not
 *   terminate the tag early.
 *
 * Nothing else is repaired. Mis-nested elements, a stray `<`, a truncated document or a wrong
 * root all reach step 3 and fail loudly: a lenient parser that still fails *quietly* has fixed
 * nothing.
 */

/** One document's bytes after [repairXhtml], with the names of the repairs that changed it. */
internal data class RepairedDocument(val bytes: ByteArray, val applied: List<String>) {

    /** Value equality including the bytes — a data class would compare [bytes] by reference. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RepairedDocument) return false
        return bytes.contentEquals(other.bytes) && applied == other.applied
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + applied.hashCode()
}

/**
 * Every character Python's `\s` matches, as a regex character-class body.
 *
 * The JVM's `\s` is the six ASCII whitespace characters and its `UNICODE_CHARACTER_CLASS` `\s`
 * is Unicode `White_Space` (25 code points); Python's is 29 — `White_Space` plus U+001C-U+001F.
 * Writing the class out is the only way both sides accept the same documents.
 */
private const val PYTHON_SPACE_CLASS =
    "\\u0009-\\u000D\\u001C-\\u001F\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A" +
        "\\u2028\\u2029\\u202F\\u205F\\u3000"

private val XML_DECLARATION_RE = Regex("^[$PYTHON_SPACE_CLASS]*<\\?xml[^>]*\\?>")

private val NAMED_ENTITY_RE = Regex("&([A-Za-z][A-Za-z0-9]*);")

private val XML_BUILTIN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

private val BARE_AMPERSAND_RE =
    Regex("&(?!(?:#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);)")

/**
 * HTML void elements, plus the SVG `<image>` cover wrapper this normalizer already reads.
 *
 * They have no closing tag, so XHTML that writes them unclosed (`<br>`) is not well-formed
 * XML. Longest names first so the alternation reads naturally; the name is anchored by the
 * negative lookahead below, so `<imgfoo>` is not mistaken for `<img>`.
 */
private val VOID_ELEMENTS =
    listOf(
        "source", "image", "embed", "input", "param", "track", "area",
        "base", "link", "meta", "col", "img", "wbr", "br", "hr",
    )

private val UNCLOSED_VOID_RE =
    Regex(
        "<(" + VOID_ELEMENTS.joinToString("|") + ")(?![^$PYTHON_SPACE_CLASS/>])" +
            "((?:\"[^\"]*\"|'[^']*'|[^<>\"'])*?)(?<!/)>",
        RegexOption.IGNORE_CASE,
    )

private const val R0_NAME = "R0 dropped the XML declaration"
private const val R1_NAME = "R1 numericized undeclared named entities"
private const val R2_NAME = "R2 escaped bare ampersands"
private const val R3_NAME = "R3 self-closed void elements"

/**
 * Apply repairs R0-R3 of the leniency rule to one document's bytes.
 *
 * @param data The raw document bytes, as stored in the archive.
 * @return The repaired UTF-8 bytes and the names of the repairs that changed something. The
 *   names are for the recovery log line, not for control flow.
 */
internal fun repairXhtml(data: ByteArray): RepairedDocument {
    var text = decodeLeniently(data)
    val applied = mutableListOf<String>()

    val stripped = XML_DECLARATION_RE.replaceFirst(text, "")
    if (stripped != text) applied.add(R0_NAME)
    text = stripped

    val numericized = NAMED_ENTITY_RE.replace(text) { match ->
        val name = match.groupValues[1]
        val codePoints = if (name in XML_BUILTIN_ENTITIES) null else Html5Entities.codePointsOf(name)
        codePoints?.joinToString("") { "&#$it;" } ?: match.value
    }
    if (numericized != text) applied.add(R1_NAME)
    text = numericized

    val escaped = BARE_AMPERSAND_RE.replace(text, "&amp;")
    if (escaped != text) applied.add(R2_NAME)
    text = escaped

    val closed = UNCLOSED_VOID_RE.replace(text) { match ->
        "<" + match.groupValues[1] + match.groupValues[2] + "/>"
    }
    if (closed != text) applied.add(R3_NAME)
    text = closed

    return RepairedDocument(text.toByteArray(Charsets.UTF_8), applied)
}

/**
 * Decode document bytes as UTF-8, falling back to ISO-8859-1 (R0's decode half).
 *
 * ISO-8859-1 maps every byte, so the fallback never raises and never loses a byte.
 *
 * @param data The raw document bytes.
 * @return The decoded text.
 */
private fun decodeLeniently(data: ByteArray): String =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data))
            .toString()
    } catch (_: CharacterCodingException) {
        String(data, Charsets.ISO_8859_1)
    }
