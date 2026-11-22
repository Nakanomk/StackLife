// Adapted from Kern's parser/MarkdownRenderProjection.kt, Copyright 2026 Gary Keeler (Apache-2.0).
package io.github.nakanomk.stacklife.markdown.kern

import androidx.compose.ui.text.TextRange

/** Each syntax token is a local replacement. The same replacements drive the plain-text summary. */
internal class MarkdownRenderProjection(
    private val document: ParsedMarkdown,
    private val selection: TextRange?,
    private val composition: TextRange?,
) {
    private val edits get() = document.edits
    private val delta = IntArray(edits.size + 1)

    init {
        for (i in edits.indices) {
            val e = edits[i]
            delta[i + 1] = delta[i] + if (visibleSyntax(e)) 0 else e.replacement.length - (e.end - e.start)
        }
    }

    private fun TextRange.touches(e: SyntaxEdit): Boolean = min <= e.constructEnd && max >= e.constructStart
    private fun visibleSyntax(e: SyntaxEdit) = selection?.touches(e) == true || composition?.touches(e) == true

    /** Apply edits from right to left, retaining native caret/handle/IME offset mapping. */
    inline fun applyTo(replace: (Int, Int, String) -> Unit) {
        for (i in edits.indices.reversed()) {
            val e = edits[i]
            if (!visibleSyntax(e)) replace(e.start, e.end, e.replacement)
        }
    }

    /** Only style annotations need our map; native selection is never written by the projection. */
    fun mapped(offset: Int): Int {
        var lo = 0
        var hi = edits.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (edits[mid].end <= offset) lo = mid + 1 else hi = mid
        }
        val e = edits.getOrNull(lo)
        return if (e != null && e.start < offset && !visibleSyntax(e)) e.start + delta[lo] else offset + delta[lo]
    }
}

internal fun ParsedMarkdown.plainText(): String {
    if (edits.isEmpty()) return source
    return buildString(source.length) {
        var at = 0
        for (edit in edits) {
            append(source, at, edit.start)
            append(edit.replacement)
            at = edit.end
        }
        append(source, at, source.length)
    }
}
