package io.github.nakanomk.stacklife.markdown

import androidx.compose.ui.text.TextRange
import io.github.nakanomk.stacklife.markdown.kern.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownProjectionTest {
    private fun projected(doc: ParsedMarkdown, selection: TextRange?, composition: TextRange? = null): String {
        val result = StringBuilder(doc.source)
        MarkdownRenderProjection(doc, selection, composition).applyTo { a, b, replacement -> result.replace(a, b, replacement) }
        return result.toString()
    }

    @Test fun readerGrammarRecognizesStrongButNotIntrawordUnderscoresOrLiteralCode() {
        val source = "__中文__ foo_bar_baz `` `**not bold**` `` \\*星\\* ~~gone~~ *open"
        val doc = MarkdownDocumentScanner.scan(source)
        assertEquals("中文 foo_bar_baz `**not bold**` *星* gone *open", doc.plainText())
        assertEquals(listOf("中文"), doc.elements.filter { it.type == MarkdownElement.Type.BOLD }.map { source.substring(it.start, it.end) })
        assertEquals(doc.plainText(), projected(doc, null))
        assertEquals("__中文__ foo_bar_baz `**not bold**` *星* gone *open", projected(doc, TextRange(3)))
        assertEquals(source, projected(doc, TextRange(source.length, 0)))
        assertEquals("**not closed", markdownSummary("**not closed"))
        assertEquals("```kt\r\n*literal*", markdownSummary("```kt\r\n*literal*"))
    }

    @Test fun rawCoordinatesSurviveEmojiCrLfAndComposingOutsideTheSelection() {
        val source = "😀 __bold__\r\n\r\n*한글*\r# 日本"
        val doc = MarkdownDocumentScanner.scan(source)
        assertEquals("😀 bold\r\n\r\n한글\r日本", doc.plainText())
        val active = MarkdownRenderProjection(doc, TextRange(0), TextRange(source.indexOf('한'), source.indexOf('글') + 1))
        val shown = projected(doc, TextRange(0), TextRange(source.indexOf('한'), source.indexOf('글') + 1))
        assertEquals("😀 bold\r\n\r\n*한글*\r日本", shown)
        assertEquals(shown.indexOf('한'), active.mapped(source.indexOf('한')))
        assertEquals(shown.indexOf('日'), active.mapped(source.indexOf('日')))
        assertEquals(source, doc.source)
    }

    @Test fun fencedCodeTablesAndImageAltAreOfflineTextAndSelectionRestoresSource() {
        val table = "| left | right |\r\n| --- | --- |\r\n| __x__ | a\\|b |"
        val source = "$table\r\n\r\n~~~kt\r\n**literal**\r\n~~~~\r\n\r\n![alt](https://example.invalid/image.png)"
        val doc = MarkdownDocumentScanner.scan(source)
        assertEquals("left\tright\r\nx\ta|b\r\n\r\n**literal**\r\n\r\nalt", doc.plainText())
        assertEquals(doc.plainText(), projected(doc, null))
        assertTrue(projected(doc, TextRange(source.indexOf('x'))).startsWith(table))
        assertTrue(projected(doc, TextRange(source.indexOf("literal"))).contains("~~~kt\r\n**literal**\r\n~~~~"))
    }

    @Test fun listContinuationRequiresARealGfmListItemAndKeepsIndentAndCheckbox() {
        val cache = MarkdownDocumentCache()
        fun next(s: String) = MarkdownEditorEngine.continueDocumentList(s, s.length, cache)
        assertEquals("  - [ ] ", next("  - [x] 完成")?.nextPrefix)
        assertEquals("10) ", next("9) 한글")?.nextPrefix)
        assertEquals(MarkdownEditorEngine.Continuation(0, 2, null), next("- "))
        assertNull(next("- - -"))
        assertNull(next("```\n- no continuation"))
        assertNull(MarkdownEditorEngine.continueDocumentList("- [ ] task", 1, cache))
    }

    @Test fun nativeEditorMatchesReaderTextStylesAndOffsetsAsPlainProseBecomesMarkdown() {
        val cache = MarkdownDocumentCache()
        for (source in listOf("", "今天 123", "今天 123\n\nNext line", "中  \n文", "中\\\n文",
            "    code", "text\n\n\tcode", "contact <a@example.org>", "one\r\n**two**", "1. item", "😀 __中文__")) {
            val reader = MarkdownDocumentScanner.scan(source)
            val editor = cache.get(source)
            assertEquals(source, reader.elements, editor.elements)
            assertEquals(source, reader.listMarkerEnds, editor.listMarkerEnds)
            for (caret in listOf(null, TextRange(source.length / 2))) {
                assertEquals(source, projected(reader, caret), projected(editor, caret))
                val expected = MarkdownRenderProjection(reader, caret, null)
                val actual = MarkdownRenderProjection(editor, caret, null)
                for (i in 0..source.length) assertEquals("$source @ $i", expected.mapped(i), actual.mapped(i))
            }
        }
    }

    @Test fun hundredKilobyteDocumentReusesOneParseAcrossCursorAndSelectionChanges() {
        val body = "plain中😀 ".repeat(13_000)
        val source = "__start__\n\n$body\n\n**end**"
        assertTrue(source.length > 100_000)
        val cache = MarkdownDocumentCache()
        val first = cache.get(source)
        for (position in 0..source.length step 997) {
            val doc = cache.get(source)
            assertSame(first, doc)
            val projection = MarkdownRenderProjection(doc, TextRange(position), null)
            assertTrue(projection.mapped(position) in 0..source.length)
        }
        // A fresh but equal platform text snapshot (selection-only update) is not a parse miss.
        assertSame(first, cache.get(String(source.toCharArray())))
        val changed = cache.get(source.replaceFirst("start", "other"))
        assertNotSame(first, changed)
        assertTrue(changed.plainText().startsWith("other"))
    }
}
