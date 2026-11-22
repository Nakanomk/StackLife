// Adapted from Kern's parser/MarkdownParser.kt, Copyright 2026 Gary Keeler (Apache-2.0).
package io.github.nakanomk.stacklife.markdown.kern

import io.github.nakanomk.stacklife.markdown.kern.MarkdownElement.Type
import org.intellij.markdown.MarkdownElementTypes as E
import org.intellij.markdown.MarkdownTokenTypes as T
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes as G
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser as GfmParser

/** Keep Kern's construct-owned ranges; let the reader's lexer, rather than an inline regex,
 * resolve precedence, delimiter runs, intraword underscores, code spans and link destinations. */
internal class MarkdownParser(private val source: String, private val text: String, private val offsets: IntArray?) {
    private val edits = ArrayList<SyntaxEdit>()
    private val styles = ArrayList<MarkdownElement>()
    private val listMarkerEnds = ArrayList<Int>()
    private var activeTable: ASTNode? = null
    private val root = GfmParser(GFMFlavourDescriptor(), false, CancellationToken.NonCancellable)
        .buildMarkdownTreeFromString(text as CharSequence)
    private val links by lazy(LazyThreadSafetyMode.NONE) { LinkMap.buildLinkMap(root, text) }

    fun parse(): ParsedMarkdown {
        visit(root)
        edits.sortBy { it.start }
        return ParsedMarkdown(source, edits, styles, listMarkerEnds)
    }

    private fun pos(offset: Int) = offsets?.get(offset) ?: offset
    private fun edit(start: Int, end: Int, owner: ASTNode, replacement: String = "") =
        edit(start, end, owner.startOffset, owner.endOffset, replacement)

    private fun edit(start: Int, end: Int, ownerStart: Int, ownerEnd: Int, replacement: String = "") {
        if (start < end) edits += SyntaxEdit(pos(start), pos(end), replacement,
            pos(activeTable?.startOffset ?: ownerStart), pos(activeTable?.endOffset ?: ownerEnd))
    }

    private fun style(type: Type, start: Int, end: Int) {
        if (start < end) styles += MarkdownElement(type, pos(start), pos(end))
    }

    private fun visit(n: ASTNode) {
        val start = n.startOffset
        val end = n.endOffset
        when (n.type) {
            E.STRONG, E.EMPH, G.STRIKETHROUGH -> {
                val width = when (n.type) {
                    E.STRONG -> 2
                    G.STRIKETHROUGH -> if (text.getOrNull(start + 1) == '~') 2 else 1
                    else -> 1
                }
                val a = start + width
                val b = end - width
                edit(start, a, n)
                edit(b, end, n)
                style(when (n.type) { E.STRONG -> Type.BOLD; E.EMPH -> Type.ITALIC; else -> Type.STRIKE }, a, b)
                for (child in n.children) if (child.startOffset >= a && child.endOffset <= b) visit(child)
            }
            E.CODE_SPAN -> {
                val first = n.children.first()
                val last = n.children.last()
                var a = first.endOffset
                var b = last.startOffset
                // GFM collapses one bounding space on each side except for all-space spans.
                if (a < b && text[a] in " \n" && text[b - 1] in " \n" && (a until b).any { text[it] !in " \n" }) {
                    a++; b--
                }
                // An escaped backtick may precede an opening run or end the literal content.
                val escapedOpen = first.type == T.ESCAPED_BACKTICKS
                if (escapedOpen) edit(start, start + 1, n)
                edit(if (escapedOpen) start + 2 else start, a, n)
                if (last.type == T.ESCAPED_BACKTICKS) b++
                edit(b, end, n)
                for (i in a until b) {
                    if (text[i] == '\n') edit(i, i + 1, n, " ")
                    else if (activeTable != null && text[i] == '\\' && i + 1 < b && text[i + 1] == '|') edit(i, i + 1, n)
                }
                style(Type.CODE, a, b)
                // Never recurse into literal code, including backslashes.
            }
            E.CODE_BLOCK, E.CODE_FENCE -> {
                style(Type.CODE, start, end)
                if (n.type == E.CODE_FENCE && n.children.lastOrNull()?.type == T.CODE_FENCE_END) {
                    val body = n.children.firstOrNull { it.type == T.EOL }?.endOffset ?: end
                    val last = n.children.last().startOffset
                    edit(start, body, n)
                    edit(if (last > body && text[last - 1] == '\n') last - 1 else last, end, n)
                }
            }
            E.ATX_1, E.ATX_2, E.ATX_3, E.ATX_4, E.ATX_5, E.ATX_6, E.SETEXT_1, E.SETEXT_2 -> {
                val content = n.children.firstOrNull { it.type == T.ATX_CONTENT || it.type == T.SETEXT_CONTENT }
                if (content == null) { edit(start, end, n); return }
                var a = content.startOffset
                var b = content.endOffset
                while (a < b && text[a] in " \t") a++
                while (b > a && text[b - 1] in " \t") b--
                edit(start, a, n)
                edit(b, end, n)
                style(when (n.type) {
                    E.ATX_1, E.SETEXT_1 -> Type.H1; E.ATX_2, E.SETEXT_2 -> Type.H2; E.ATX_3 -> Type.H3; else -> Type.HEADING
                }, a, b)
                content.children.forEach(::visit)
            }
            E.BLOCK_QUOTE -> { style(Type.QUOTE, start, end); n.children.forEach(::visit) }
            T.BLOCK_QUOTE -> {
                if (generateSequence(n.parent) { it.parent }.none { it.type == E.BLOCK_QUOTE }) return
                val b = if (text.getOrNull(end) == ' ') end + 1 else end
                edit(start, b, start, lineEnd(b), "│ ")
            }
            T.LIST_BULLET, T.LIST_NUMBER -> {
                val eol = lineEnd(start)
                val marker = MarkdownListSyntax.parse(text.substring(start, eol)) ?: return
                listMarkerEnds += pos(start + marker.end)
                marker.display?.let { edit(start, start + marker.end, start, eol, it) }
            }
            T.HORIZONTAL_RULE -> edit(start, end, n, "─")
            E.INLINE_LINK, E.SHORT_REFERENCE_LINK, E.FULL_REFERENCE_LINK, E.IMAGE -> link(n)
            E.LINK_DEFINITION -> edit(start, end, n)
            E.AUTOLINK -> {
                if (text[start] == '<' && text[end - 1] == '>') {
                    edit(start, start + 1, n); edit(end - 1, end, n)
                }
                style(Type.LINK, start, end)
            }
            G.TABLE -> { activeTable = n; table(n); activeTable = null }
            T.TEXT, T.ESCAPED_BACKTICKS -> {
                var i = start
                while (i + 1 < end) {
                    val c = text[i + 1]
                    if (text[i] == '\\' && (c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~')) {
                        edit(i, i + 1, i, i + 2)
                        i += 2
                    } else i++
                }
            }
            else -> n.children.forEach(::visit)
        }
    }

    private fun lineEnd(start: Int) = text.indexOf('\n', start).let { if (it == -1) text.length else it }

    private fun link(owner: ASTNode) {
        val n = if (owner.type == E.IMAGE) owner.children.firstOrNull {
            it.type == E.INLINE_LINK || it.type == E.SHORT_REFERENCE_LINK || it.type == E.FULL_REFERENCE_LINK
        } ?: return else owner
        val label = n.children.firstOrNull { it.type == E.LINK_LABEL }
        val shown = n.children.firstOrNull { it.type == E.LINK_TEXT } ?: label ?: return
        if (label != null && links.getLinkInfo(text.substring(label.startOffset, label.endOffset)) == null) {
            shown.children.forEach(::visit)
            return
        }
        val a = shown.startOffset + 1
        val b = shown.endOffset - 1
        edit(owner.startOffset, a, owner); edit(b, owner.endOffset, owner)
        style(Type.LINK, a, b)
        shown.children.forEach(::visit)
    }

    /** Kern's tab-separated table projection, expressed as local edits rather than replacing
     * the whole table, so selection handles still have individual cell and word boundaries. */
    private fun table(n: ASTNode) {
        for (row in n.children) when (row.type) {
            GFMTokenTypes.TABLE_SEPARATOR -> {
                val start = row.startOffset
                edit(if (start > n.startOffset && text[start - 1] == '\n') start - 1 else start, row.endOffset, n)
            }
            G.ROW, G.HEADER -> {
                val end = row.endOffset
                var at = row.startOffset
                while (at < end && text[at] in " \t") at++
                if (at < end && text[at] == '|') at++
                while (at < end && text[at] in " \t") at++
                edit(row.startOffset, at, n)
                var cellStart = at
                while (at < end) {
                    if (text[at] != '|' || (at > cellStart && text[at - 1] == '\\')) { at++; continue }
                    var from = at
                    while (from > cellStart && text[from - 1] in " \t") from--
                    at++
                    while (at < end && text[at] in " \t") at++
                    edit(from, at, n, if (at == end) "" else "\t")
                    cellStart = at
                }
                var tail = end
                while (tail > cellStart && text[tail - 1] in " \t") tail--
                edit(tail, end, n)
                for (cell in row.children) if (cell.type == GFMTokenTypes.CELL) cell.children.forEach(::visit)
            }
        }
    }
}
