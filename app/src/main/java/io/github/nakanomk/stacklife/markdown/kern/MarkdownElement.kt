// Adapted from Kern's parser/MarkdownElement.kt, Copyright 2026 Gary Keeler (Apache-2.0).
package io.github.nakanomk.stacklife.markdown.kern

/** All positions are UTF-16 offsets in the unchanged TextFieldState document. */
internal data class MarkdownElement(val type: Type, val start: Int, val end: Int) {
    enum class Type { H1, H2, H3, HEADING, BOLD, ITALIC, STRIKE, CODE, LINK, QUOTE }
}

internal data class SyntaxEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val constructStart: Int,
    val constructEnd: Int,
)

internal data class ParsedMarkdown(
    val source: String,
    val edits: List<SyntaxEdit>,
    val elements: List<MarkdownElement>,
    val listMarkerEnds: List<Int>,
)
