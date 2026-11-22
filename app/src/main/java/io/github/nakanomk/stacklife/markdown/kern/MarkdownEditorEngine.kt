// Adapted from Kern's parser/MarkdownEditorEngine.kt, Copyright 2026 Gary Keeler (Apache-2.0).
// Only the confirmed Enter list operation is used; arbitrary typing and paste remain native edits.
package io.github.nakanomk.stacklife.markdown.kern

internal object MarkdownEditorEngine {
    data class Continuation(val markerStart: Int, val markerEnd: Int, val nextPrefix: String?)

    fun continueDocumentList(source: String, offset: Int, cache: MarkdownDocumentCache): Continuation? {
        var start = offset
        while (start > 0 && source[start - 1] != '\n' && source[start - 1] != '\r') start--
        var end = offset
        while (end < source.length && source[end] != '\n' && source[end] != '\r') end++
        val line = source.substring(start, end)
        val marker = MarkdownListSyntax.parse(line) ?: return null
        if (offset < start + marker.end) return null
        // The GFM tree, not a list-looking string inside code, a table or a thematic break,
        // owns the marker. Parser and input continuation consume the very same marker range.
        if (cache.get(source).listMarkerEnds.binarySearch(start + marker.end) < 0) return null
        return Continuation(start, start + marker.end, marker.next.takeUnless { line.substring(marker.end).isBlank() })
    }
}
