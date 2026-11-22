// Adapted from Kern's parser/MarkdownDocumentScanner.kt, Copyright 2026 Gary Keeler (Apache-2.0).
package io.github.nakanomk.stacklife.markdown.kern

/** Kern keeps exact source separators. The reader's GFM lexer expects LF, so only its view
 * is normalized; map every syntax endpoint back to the original document before storing it. */
internal object MarkdownDocumentScanner {
    fun scan(source: String): ParsedMarkdown {
        val firstCr = source.indexOf('\r')
        if (firstCr < 0) return MarkdownParser(source, source, null).parse()
        val positions = IntArray(source.length + 1)
        val text = StringBuilder(source.length)
        text.append(source, 0, firstCr)
        for (i in 0 until firstCr) positions[i] = i
        var i = firstCr
        while (i < source.length) {
            positions[text.length] = i
            val c = source[i++]
            if (c == '\r') {
                if (i < source.length && source[i] == '\n') i++
                text.append('\n')
            } else text.append(c)
        }
        positions[text.length] = i
        return MarkdownParser(source, text.toString(), positions).parse()
    }
}

/** One immutable document per field; selection and composing information are not parse keys. */
internal class MarkdownDocumentCache {
    private var last: ParsedMarkdown? = null
    private var version: String? = null

    fun selectionOnly(previous: CharSequence, unchanged: CharSequence) {
        // The native change list proves identical bytes, even if Compose allocated a fresh
        // immutable text snapshot when moving the selection. No whole-document comparison.
        if (previous === version) version = unchanged.toString()
    }

    fun get(source: CharSequence): ParsedMarkdown {
        val text = source.toString()
        last?.let {
            if (version === text) return it
            if (it.source == text) { version = text; return it }
        }
        return MarkdownDocumentScanner.scan(text).also { last = it; version = text }
    }
}
