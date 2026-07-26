package app.berilo.reader.translate.epub

import android.util.Log
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * An EPUB document that could not be parsed, even after lenient recovery.
 *
 * Thrown instead of skipping the document. A dropped spine document takes every paragraph,
 * heading and image in it with it; CLAUDE.md §2 makes that loss loud and resumable rather than
 * a warning in a log nobody reads. Mirrors Python's `EpubParseError`, which subclasses
 * `ValueError` — hence [IllegalArgumentException] here.
 *
 * @property document Zip-internal path of the document that failed.
 * @property reason Why it failed, including the strict and post-recovery errors.
 */
class EpubParseException(val document: String, val reason: String) :
    IllegalArgumentException("EPUB document '$document' could not be parsed: $reason")

/**
 * Strict-first XHTML parsing under the leniency rule documented in [repairXhtml].
 *
 * **Order is the whole design.** A well-formed document is parsed strictly and never touches
 * the repair pass, so it takes exactly the path it took before leniency existed. Because
 * R0-R3 are near-idempotent — they leave valid markup alone, which is precisely what makes
 * them safe — comparing repaired output against strict output cannot detect a recovery pass
 * that always runs (`docs/findings.md`, 2026-07-26). The discriminating assertion is on the
 * *mechanism*: [repair] is injectable so a test can supply one that fails on invocation and
 * read a whole well-formed book through it.
 *
 * @property repair The repair pass; overridable only so its unreachability is assertable.
 * @property onRecovered Called after a document is recovered, with its path and the repairs
 *   that changed something. Defaults to a warning log line.
 */
internal class XhtmlParser(
    private val repair: (ByteArray) -> RepairedDocument = ::repairXhtml,
    private val onRecovered: (String, List<String>) -> Unit = ::logRecovery,
) {

    /**
     * Parse one EPUB document.
     *
     * @param data The raw document bytes.
     * @param document Zip-internal path, used in the recovery notice and the exception.
     * @return The parsed document root element.
     * @throws EpubParseException If the document is not well-formed and the repairs do not
     *   make it so.
     */
    fun parse(data: ByteArray, document: String): Element {
        val strictError =
            try {
                return documentElementOf(data)
            } catch (exception: SAXException) {
                exception
            }
        val repaired = repair(data)
        val root =
            try {
                documentElementOf(repaired.bytes)
            } catch (lenientError: SAXException) {
                throw EpubParseException(
                    document,
                    "strict parse failed (${strictError.message}) and recovery failed " +
                        "(${lenientError.message})",
                )
            }
        onRecovered(document, repaired.applied)
        return root
    }

    /**
     * Parse bytes into a document element with a strict, offline, namespace-aware parser.
     *
     * The bytes go in as an [java.io.InputStream], never a `Reader`: the parser must read the
     * document's own encoding declaration, exactly as `ET.fromstring(bytes)` does.
     *
     * @param data The bytes to parse.
     * @return The root element.
     * @throws SAXException If the bytes are not well-formed XML.
     */
    private fun documentElementOf(data: ByteArray): Element {
        val root = newBuilder().parse(InputSource(ByteArrayInputStream(data))).documentElement
        inlineEntityReferences(root)
        root.normalize()
        return root
    }
}

/**
 * Splice every entity reference into its parent, failing on one that was never declared.
 *
 * This exists because of a measured divergence, and it is the reason the parser is configured
 * with `expandEntityReferences = false`. Given `&nbsp;` in element content:
 *
 * - Python's expat raises `undefined entity` — **whether or not the document has a DOCTYPE**.
 * - The JVM parser raises only when there is no DOCTYPE. When a DOCTYPE names an external
 *   subset this parser deliberately does not fetch, the well-formedness constraint becomes
 *   "not checkable", and the entity is **dropped with no warning, no error, no callback**:
 *   `Bare&nbsp;entity` parses to `Bareentity`. Every real EPUB has a DOCTYPE.
 *
 * Silently swallowing a character is precisely the failure this story exists to prevent: the
 * segment text would differ from the CLI's by one character and the whole book would hash
 * differently. With references left unexpanded they are visible as nodes instead — a declared
 * entity carries its replacement as children, an undeclared one is childless — so the
 * undeclared case can be turned back into the parse failure Python reports, which routes the
 * document to R1 exactly as it does there.
 *
 * Attribute values are deliberately **not** checked: expat does not raise for an undeclared
 * entity inside one either (measured), and no attribute value reaches a segment's text.
 *
 * @param node The subtree to splice in place.
 * @throws SAXException If an entity reference has no declaration.
 */
private fun inlineEntityReferences(node: Node) {
    var child = node.firstChild
    while (child != null) {
        val next = child.nextSibling
        if (child.nodeType == Node.ENTITY_REFERENCE_NODE) {
            if (child.firstChild == null) {
                throw SAXException("The entity \"${child.nodeName}\" was referenced, but not declared.")
            }
            inlineEntityReferences(child)
            while (child.firstChild != null) {
                node.insertBefore(child.removeChild(child.firstChild), child)
            }
            node.removeChild(child)
        } else if (child.nodeType == Node.ELEMENT_NODE) {
            inlineEntityReferences(child)
        }
        child = next
    }
}

/** Xerces feature switch for fetching the external DTD subset named by a DOCTYPE. */
private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"

private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"

private const val EXTERNAL_PARAMETER_ENTITIES =
    "http://xml.org/sax/features/external-parameter-entities"

private const val LOG_TAG = "EpubReader"

/**
 * Build a parser configured to behave like `xml.etree.ElementTree.fromstring`.
 *
 * Namespace-aware (so `_local()`'s namespace-insensitive matching has local names to match),
 * non-validating, and — the load-bearing part — **it never fetches the external DTD** a
 * DOCTYPE names. Python's expat does not either, which is exactly why a bare `&nbsp;` is an
 * undeclared entity there and must be one here too: if this parser resolved XHTML's DTD it
 * would accept documents Python rejects, recover documents Python repairs, and the two sides
 * would no longer be the same algorithm. Fetching would also mean network access per chapter.
 *
 * @return A fresh builder; [DocumentBuilder] is not thread-safe, so it is never shared.
 */
private fun newBuilder(): DocumentBuilder {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isValidating = false
    // Left unexpanded on purpose — see inlineEntityReferences, which does the expansion and
    // turns an undeclared reference back into the parse failure Python reports.
    factory.isExpandEntityReferences = false
    factory.setFeatureQuietly(LOAD_EXTERNAL_DTD, false)
    factory.setFeatureQuietly(EXTERNAL_GENERAL_ENTITIES, false)
    factory.setFeatureQuietly(EXTERNAL_PARAMETER_ENTITIES, false)
    factory.setFeatureQuietly(DISALLOW_DOCTYPE, false)
    val builder = factory.newDocumentBuilder()
    // Belt and braces for parsers that ignore the feature above: resolve every external id to
    // nothing rather than to the network.
    builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
    builder.setErrorHandler(StrictErrorHandler)
    return builder
}

/**
 * Set a parser feature, tolerating an implementation that does not know it.
 *
 * Android's parser and the JDK's do not expose the same feature URIs, and an unknown one is
 * not a reason to fail a book.
 *
 * @param name The feature URI.
 * @param value The value to set.
 */
private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: javax.xml.parsers.ParserConfigurationException) {
        Log.d(LOG_TAG, "XML parser does not support feature $name")
    }
}

/**
 * Turns every parse error into the failure Python's expat raises; only warnings are ignored.
 *
 * `error()` must throw, and that is not defensiveness. A document whose DOCTYPE names an
 * external DTD this parser deliberately does not fetch reports an undeclared `&nbsp;` as a
 * **recoverable** error, not a fatal one — the well-formedness constraint is "not checkable"
 * when the external subset was not read. The default handler ignores recoverable errors, so
 * the document would parse "successfully" with the entity silently dropped, while expat
 * rejects it and Python repairs it. The two sides would recover different documents from the
 * same bytes, which shifts every later `position` and every segment id. Throwing routes the
 * document to R1 instead, exactly as Python does.
 */
private object StrictErrorHandler : org.xml.sax.ErrorHandler {
    override fun warning(exception: org.xml.sax.SAXParseException) = Unit

    override fun error(exception: org.xml.sax.SAXParseException): Unit = throw exception

    override fun fatalError(exception: org.xml.sax.SAXParseException): Unit = throw exception
}

/**
 * Default recovery notice: a warning naming the document and the repairs that fired.
 *
 * @param document Zip-internal path of the recovered document.
 * @param applied Names of the repairs that changed something.
 */
private fun logRecovery(document: String, applied: List<String>) {
    Log.w(LOG_TAG, "Recovered malformed document $document — repairs: ${applied.joinToString("; ").ifEmpty { "none" }}")
}
