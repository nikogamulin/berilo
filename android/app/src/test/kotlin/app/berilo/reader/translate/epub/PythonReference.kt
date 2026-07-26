package app.berilo.reader.translate.epub

/**
 * Values CPython produced, pinned so the Kotlin port can be compared against them offline.
 *
 * Generated from `translator/berilo/normalize/epub.py` itself — `_block_text`,
 * `_is_heading_like`, `_repair_xhtml` — plus `posixpath`, run against synthetic fragments.
 * No book text: every input here was written for this file.
 *
 * Every literal is `\uXXXX`-escaped on purpose. Half of these cases turn on characters that
 * render as nothing (U+0085, U+200B, U+2060, U+2028), and a test whose meaning depends on an
 * editor preserving an invisible byte is a test that will quietly stop testing.
 */
internal object PythonReference {

    /** `re.sub(r"\s+", " ", raw).strip()` — includes U+001C-U+001F, which no JVM `\s` matches. */
    data class CollapseCase(val raw: String, val collapsed: String)

    /** `_block_text(ET.fromstring(xml))` — the inline subset and whitespace collapsing. */
    data class BlockCase(val xml: String, val text: String)

    /** `_is_heading_like(...)` alongside the block text it measures. */
    data class HeadingCase(val xml: String, val headingLike: Boolean, val text: String)

    /** `_repair_xhtml(raw.encode("latin-1"))`, decoded back for readability. */
    data class RepairCase(val raw: String, val repaired: String, val applied: List<String>)

    /** `posixpath.normpath(posixpath.join(base, href))`. */
    data class PathCase(val base: String, val href: String, val resolved: String)

    val COLLAPSE =
        listOf(
            CollapseCase(raw = "\u0007bell\u0007 keeps its \u0007 edges\u0007", collapsed = "\u0007bell\u0007 keeps its \u0007 edges\u0007"),
            CollapseCase(raw = "\u001cFile\u001fseparator\u001dpadding\u001e", collapsed = "File separator padding"),
            CollapseCase(raw = "\u0085\u00a0\u2028mixed\u205f\u3000exotic\u1680space\u0085", collapsed = "mixed exotic space"),
            CollapseCase(raw = "   ", collapsed = ""),
            CollapseCase(raw = "", collapsed = ""),
            CollapseCase(raw = "\u200bzero\u2060width stays\u200b", collapsed = "\u200bzero\u2060width stays\u200b"),
        )

    val BLOCK_TEXT =
        listOf(
            BlockCase(xml = "<p>Plain paragraph.</p>", text = "Plain paragraph."),
            BlockCase(xml = "<p>  Leading and   internal\n\twhitespace  </p>", text = "Leading and internal whitespace"),
            BlockCase(xml = "<p>\u0085U+0085\u0085padded\u0085</p>", text = "U+0085 padded"),
            BlockCase(xml = "<p>\u00a0Non-breaking\u00a0space\u2028and\u2029line\u205fseparator\u00a0</p>", text = "Non-breaking space and line separator"),
            BlockCase(xml = "<p>\u200bZero\u2060width joiner stays\u200b</p>", text = "\u200bZero\u2060width joiner stays\u200b"),
            BlockCase(xml = "<p>\u0160umniki: \u010debela \u017eveji \u0161ipek.</p>", text = "\u0160umniki: \u010debela \u017eveji \u0161ipek."),
            BlockCase(xml = "<p>An <em>emphasis</em> and a <strong>strong</strong> stay</p>", text = "An <em>emphasis</em> and a <strong>strong</strong> stay"),
            BlockCase(xml = "<p>An <i>i</i>, a <b>b</b>, a <sub>sub</sub> and a <sup>sup</sup></p>", text = "An <i>i</i>, a <b>b</b>, a <sub>sub</sub> and a <sup>sup</sup>"),
            BlockCase(xml = "<p>A <span>span</span> and a <a href='x'>link</a> unwrap</p>", text = "A span and a link unwrap"),
            BlockCase(xml = "<p>First line<br />second line.</p>", text = "First linesecond line."),
            BlockCase(xml = "<p>Nested <em>emphasis with <strong>strong</strong> inside</em>.</p>", text = "Nested <em>emphasis with <strong>strong</strong> inside</em>."),
            BlockCase(xml = "<p>Comment<!-- dropped -->joins</p>", text = "Commentjoins"),
            BlockCase(xml = "<p><![CDATA[Raw <not markup> text]]></p>", text = "Raw <not markup> text"),
            BlockCase(xml = "<p>Zmaj \ud83d\udc09 in ognjeni \ud83d\udd25 znak</p>", text = "Zmaj \ud83d\udc09 in ognjeni \ud83d\udd25 znak"),
            BlockCase(xml = "<p>Entity &amp; and &#233; and &#x41;</p>", text = "Entity & and \u00e9 and A"),
            BlockCase(xml = "<p>   </p>", text = ""),
            BlockCase(xml = "<p><em>  padded emphasis  </em></p>", text = "<em> padded emphasis </em>"),
            BlockCase(xml = "<p>a<br/>b<br/>c</p>", text = "abc"),
            BlockCase(xml = "<p>Deep <span><span><em>nesting</em></span></span> here</p>", text = "Deep <em>nesting</em> here"),
        )

    val HEADING_LIKE =
        listOf(
            HeadingCase(xml = "<p><b>Short and wholly bold</b></p>", headingLike = true, text = "<b>Short and wholly bold</b>"),
            HeadingCase(xml = "<p><span class='bold'>Calibre style heading</span></p>", headingLike = true, text = "Calibre style heading"),
            HeadingCase(xml = "<p><span class='BOLD'>Upper class token</span></p>", headingLike = true, text = "Upper class token"),
            HeadingCase(xml = "<p>Prose with <b>a bold phrase</b> inside</p>", headingLike = false, text = "Prose with <b>a bold phrase</b> inside"),
            HeadingCase(xml = "<p><b>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</b></p>", headingLike = false, text = "<b>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</b>"),
            HeadingCase(xml = "<p><b>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</b></p>", headingLike = false, text = "<b>xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</b>"),
            HeadingCase(xml = "<p><b>\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09</b></p>", headingLike = false, text = "<b>\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09</b>"),
            HeadingCase(xml = "<p><b>\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09</b></p>", headingLike = true, text = "<b>\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09\ud83d\udc09</b>"),
            HeadingCase(xml = "<p>No bold at all</p>", headingLike = false, text = "No bold at all"),
            HeadingCase(xml = "<p><b>  </b></p>", headingLike = true, text = "<b> </b>"),
            HeadingCase(xml = "<p><strong>Strong tag</strong> </p>", headingLike = true, text = "<strong>Strong tag</strong>"),
            HeadingCase(xml = "<p><span class='bold'>Bold</span><span class='strong'>Two</span></p>", headingLike = true, text = "BoldTwo"),
            HeadingCase(xml = "<p><span class='bold\u00a0strong'>NBSP separated class tokens</span></p>", headingLike = true, text = "NBSP separated class tokens"),
        )

    val REPAIRS =
        listOf(
            RepairCase(raw = "<?xml version=\"1.0\" encoding=\"utf-8\"?><p>Bare &nbsp; entity</p>", repaired = "<p>Bare &#160; entity</p>", applied = listOf("R0 dropped the XML declaration", "R1 numericized undeclared named entities")),
            RepairCase(raw = "<p>Stray & ampersand</p>", repaired = "<p>Stray &amp; ampersand</p>", applied = listOf("R2 escaped bare ampersands")),
            RepairCase(raw = "<p>Unclosed<br>break</p>", repaired = "<p>Unclosed<br/>break</p>", applied = listOf("R3 self-closed void elements")),
            RepairCase(raw = "<img src=\"a.png\" alt=\"3 > 2\">", repaired = "<img src=\"a.png\" alt=\"3 > 2\"/>", applied = listOf("R3 self-closed void elements")),
            RepairCase(raw = "<p>&amp; &lt; &#65; &#x42; stay</p>", repaired = "<p>&amp; &lt; &#65; &#x42; stay</p>", applied = listOf()),
            RepairCase(raw = "<p>&notanentity; stays</p>", repaired = "<p>&notanentity; stays</p>", applied = listOf()),
            RepairCase(raw = "<p>&Aacute; &nbsp; &copy; &NotEqualTilde;</p>", repaired = "<p>&#193; &#160; &#169; &#8770;&#824;</p>", applied = listOf("R1 numericized undeclared named entities")),
            RepairCase(raw = "<hr/><br /><img src='x'/>", repaired = "<hr/><br /><img src='x'/>", applied = listOf()),
            RepairCase(raw = "<imgfoo>not a void element</imgfoo>", repaired = "<imgfoo>not a void element</imgfoo>", applied = listOf()),
            RepairCase(raw = "<input type='text' value='a>b'>", repaired = "<input type='text' value='a>b'/>", applied = listOf("R3 self-closed void elements")),
            RepairCase(raw = "\u00ff\u00fe<p>invalid utf-8</p>", repaired = "\u00ff\u00fe<p>invalid utf-8</p>", applied = listOf()),
            RepairCase(raw = "<p>AT&T and R&D</p>", repaired = "<p>AT&amp;T and R&amp;D</p>", applied = listOf("R2 escaped bare ampersands")),
        )

    val PATHS =
        listOf(
            PathCase(base = "", href = "toc.ncx", resolved = "toc.ncx"),
            PathCase(base = "OEBPS", href = "text/ch1.xhtml", resolved = "OEBPS/text/ch1.xhtml"),
            PathCase(base = "OEBPS/text", href = "../images/a.png", resolved = "OEBPS/images/a.png"),
            PathCase(base = "OEBPS/text", href = "./b.xhtml", resolved = "OEBPS/text/b.xhtml"),
            PathCase(base = "OEBPS", href = "/absolute.xhtml", resolved = "/absolute.xhtml"),
            PathCase(base = "OEBPS/a/b", href = "../../c.xhtml", resolved = "OEBPS/c.xhtml"),
            PathCase(base = "", href = "../escape.xhtml", resolved = "../escape.xhtml"),
            PathCase(base = "OEBPS", href = "", resolved = "OEBPS"),
            PathCase(base = "OEBPS/", href = "x.xhtml", resolved = "OEBPS/x.xhtml"),
            PathCase(base = "a", href = "b//c.xhtml", resolved = "a/b/c.xhtml"),
            PathCase(base = "", href = "", resolved = "."),
            PathCase(base = "a/b", href = "../..", resolved = "."),
        )

    /** `posixpath.dirname`. */
    val DIRECTORY_NAMES =
        listOf(
            "a/b/c.x" to "a/b",
            "c.x" to "",
            "/a/b" to "/a",
            "/a" to "/",
            "/" to "/",
            "a/" to "a",
        )

    /** `posixpath.splitext(path)[1]`. */
    val EXTENSIONS =
        listOf(
            "a/b.PNG" to ".PNG",
            "a/b" to "",
            "a/.hidden" to "",
            "a.b/c" to "",
            "a/b.tar.gz" to ".gz",
            ".x" to "",
            "a/." to "",
        )
}
