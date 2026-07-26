package app.berilo.reader.translate.epub

import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The `xml.etree.ElementTree` view of a W3C DOM tree.
 *
 * `normalize/epub.py` is written against ElementTree, whose model differs from the DOM in two
 * ways that matter for segment text:
 *
 * - **Comments and processing instructions do not exist.** ElementTree's default tree builder
 *   drops them, so `<p>a<!--x-->b</p>` has the single text `ab`. The DOM keeps a comment node
 *   that splits the text in two, so every walk here must step over them and concatenate — not
 *   substitute anything for them.
 * - **Text lives on elements, not in nodes.** `element.text` is the run before the first child
 *   element, `child.tail` the run after that child's end tag. Walking child nodes in order
 *   reproduces both without needing either concept, which is what [visibleChildren] is for.
 */

/** Child nodes that carry characters: text and CDATA, which ElementTree merges alike. */
private val CHARACTER_NODE_TYPES = setOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE)

/**
 * The lowercase local name of a node, as `_local()` in `normalize/epub.py`.
 *
 * @return The name without any namespace prefix, lowercased.
 */
internal val Node.localTagName: String
    get() = (localName ?: nodeName).lowercase()

/** This element's child nodes in document order, comments and PIs already dropped. */
internal val Node.visibleChildren: List<Node>
    get() =
        (0 until childNodes.length)
            .map(childNodes::item)
            .filter { it.nodeType in CHARACTER_NODE_TYPES || it.nodeType == Node.ELEMENT_NODE }

/** This element's child *elements* in document order — ElementTree's `for child in element`. */
internal val Node.childElements: List<Element>
    get() =
        (0 until childNodes.length)
            .map(childNodes::item)
            .filterIsInstance<Element>()

/**
 * This element and every descendant element, in document order — ElementTree's `element.iter()`.
 *
 * @return Preorder traversal starting with the receiver.
 */
internal fun Element.selfAndDescendants(): List<Element> {
    val collected = mutableListOf<Element>()
    fun walk(element: Element) {
        collected.add(element)
        element.childElements.forEach(::walk)
    }
    walk(this)
    return collected
}

/**
 * The text run before this element's first child element — ElementTree's `element.text`.
 *
 * @return The concatenated leading character data; empty when there is none.
 */
internal val Element.leadingText: String
    get() = buildString {
        for (node in visibleChildren) {
            if (node.nodeType == Node.ELEMENT_NODE) break
            append(node.nodeValue.orEmpty())
        }
    }

/**
 * An attribute's value, or `null` when it is absent or empty.
 *
 * ElementTree's `element.get(name)` returns `None` for an absent attribute, and every call
 * site in `normalize/epub.py` tests it for truthiness — under which `None` and `""` are the
 * same thing. Collapsing them here keeps those call sites readable.
 *
 * @param name The attribute's qualified name.
 * @return The value, or `null` when absent or empty.
 */
internal fun Element.attributeOrNull(name: String): String? =
    getAttribute(name).takeIf(String::isNotEmpty)

/**
 * The first attribute whose *local* name matches, in document order.
 *
 * Mirrors `next((v for k, v in element.attrib.items() if _local(k) == name), None)`, which is
 * how the normalizer reads namespaced attributes (`xlink:href`, `epub:type`) without binding
 * itself to a prefix.
 *
 * @param name The lowercase local name to match.
 * @return The value, or `null` when no attribute has that local name or its value is empty.
 */
internal fun Element.attributeByLocalName(name: String): String? {
    val map = attributes ?: return null
    for (index in 0 until map.length) {
        val attribute = map.item(index)
        if (attribute.localTagName == name) {
            attribute.nodeValue?.takeIf(String::isNotEmpty)?.let { return it }
        }
    }
    return null
}

/**
 * All character data in this element's subtree — ElementTree's `"".join(element.itertext())`.
 *
 * @return The concatenated text of every descendant, comments excluded.
 */
internal val Element.allText: String
    get() = buildString {
        for (node in visibleChildren) {
            when (node.nodeType) {
                Node.ELEMENT_NODE -> append((node as Element).allText)
                else -> append(node.nodeValue.orEmpty())
            }
        }
    }
