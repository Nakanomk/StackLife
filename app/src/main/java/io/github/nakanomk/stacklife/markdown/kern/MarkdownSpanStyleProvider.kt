// Adapted from Kern's ui/editor/MarkdownSpanStyleProvider.kt, Copyright 2026 Gary Keeler (Apache-2.0).
package io.github.nakanomk.stacklife.markdown.kern

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.nakanomk.stacklife.markdown.kern.MarkdownElement.Type

internal class MarkdownSpanStyleProvider(type: Typography, colors: ColorScheme) {
    private val styles = Type.entries.map { kind -> when (kind) {
        Type.H1 -> type.headlineSmall.toSpanStyle()
        Type.H2 -> type.titleLarge.toSpanStyle()
        Type.H3 -> type.titleMedium.toSpanStyle()
        Type.HEADING -> type.titleSmall.toSpanStyle()
        Type.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Type.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Type.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        Type.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHighest)
        Type.LINK -> SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
        Type.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
    } }

    operator fun get(type: Type): SpanStyle = styles[type.ordinal]
}
