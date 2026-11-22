// Adapted from Kern's ui/editor/MarkdownDocumentOutputTransformation.kt,
// Copyright 2026 Gary Keeler (Apache-2.0). Selection-only changes reuse the parsed document.
package io.github.nakanomk.stacklife.markdown.kern

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState

internal class MarkdownDocumentOutputTransformation(
    private val state: TextFieldState,
    private val cache: MarkdownDocumentCache,
    private val styles: MarkdownSpanStyleProvider,
    private val focused: () -> Boolean,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        if (length == 0) return
        val document = cache.get(originalText)
        if (document.edits.isEmpty() && document.elements.isEmpty()) return
        val projection = MarkdownRenderProjection(document, if (focused()) selection else null, state.composition)
        projection.applyTo { start, end, replacement -> replace(start, end, replacement) }
        for (element in document.elements) {
            val start = projection.mapped(element.start)
            val end = projection.mapped(element.end)
            if (end > start) addStyle(styles[element.type], start, end)
        }
    }
}
