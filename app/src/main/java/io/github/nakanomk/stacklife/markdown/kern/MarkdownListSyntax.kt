// Adapted from Kern's parser/MarkdownListSyntax.kt, Copyright 2026 Gary Keeler (Apache-2.0).
package io.github.nakanomk.stacklife.markdown.kern

internal object MarkdownListSyntax {
    data class Marker(val end: Int, val next: String, val display: String?)

    // CommonMark permits at most nine ASCII digits in a list marker.
    private val unordered = Regex("^([ \\t]*)([-*+])([ \\t]+|$)(?:\\[([ xX])\\]([ \\t]+|$))?")
    private val ordered = Regex("^([ \\t]*)([0-9]{1,9})([.)])([ \\t]+|$)")

    fun parse(line: String): Marker? {
        unordered.find(line)?.let { m ->
            val task = m.groups[4]?.value
            val prefix = m.groupValues[1] + m.groupValues[2] + m.groupValues[3].ifEmpty { " " }
            return if (task == null) Marker(m.value.length, prefix, "• ")
            else Marker(m.value.length, "$prefix[ ] ", if (task.equals("x", true)) "☑ " else "☐ ")
        }
        val m = ordered.find(line) ?: return null
        return Marker(m.value.length, m.groupValues[1] + (m.groupValues[2].toInt() + 1) +
            m.groupValues[3] + m.groupValues[4].ifEmpty { " " }, null)
    }
}
