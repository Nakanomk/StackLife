package io.github.nakanomk.stacklife.markdown

import android.content.ClipData
import android.util.LruCache
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.State as Parsed
import com.mikepenz.markdown.model.parseMarkdown
import io.github.nakanomk.stacklife.R
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A LazyColumn may dispose offscreen items. Keep their immutable parses in the host,
// not in each row or a process-global store of private notes.
internal class MarkdownReadCache : LruCache<String, Parsed>(512 * 1024) {
    override fun sizeOf(key: String, value: Parsed) = key.length.coerceAtLeast(2048)
}
internal val LocalMarkdownReadCache = staticCompositionLocalOf<MarkdownReadCache?> { null }

internal fun allowedMarkdownLink(value: String): Boolean {
    val uri = try { URI(value) } catch (_: URISyntaxException) { return false }
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "http", "https" -> !uri.host.isNullOrEmpty()
        "mailto" -> uri.isOpaque && uri.rawSchemeSpecificPart.isNotEmpty()
        else -> false
    }
}

@Composable
internal fun PlannerMarkdown(source: String, modifier: Modifier = Modifier) = key(source) {
    val platform = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    val links = remember(platform) { object : UriHandler {
        override fun openUri(uri: String) {
            linkFailed = if (!allowedMarkdownLink(uri)) true else try { platform.openUri(uri); false }
            catch (_: IllegalArgumentException) { true }
            catch (_: SecurityException) { true }
        }
    } }
    val type = MaterialTheme.typography
    val cache = LocalMarkdownReadCache.current
    val state by produceState(remember(cache) { cache?.get(source) ?: Parsed.Loading() }, cache) {
        if (value is Parsed.Loading) value = withContext(Dispatchers.Default) {
            parseMarkdown(source).also { if (it is Parsed.Success) cache?.put(source, it) }
        }
    }
    Column(modifier) {
        CompositionLocalProvider(LocalUriHandler provides links) {
            SelectionContainer {
                Markdown(state = state, modifier = Modifier, components = Gfm.components,
                    colors = markdownColor(text = LocalContentColor.current),
                    typography = markdownTypography(h1 = type.titleLarge, h2 = type.titleMedium, h3 = type.titleSmall,
                        h4 = type.titleSmall, h5 = type.titleSmall, h6 = type.titleSmall),
                    animations = markdownAnimations { this },
                    error = { Column(it) { Text(stringResource(R.string.markdown_read_error), style = type.labelMedium); Text(source) } })
            }
        }
        if (linkFailed) Text(stringResource(R.string.markdown_link_unavailable), color = MaterialTheme.colorScheme.error, style = type.bodySmall)
    }
}

// Native Compose only: the renderer's default image transformer does not open URIs.
private object Gfm {
    val components = markdownComponents(
    codeFence = { ParsedCode(it.content, it.node, it.typography.code, fenced = true) },
    codeBlock = { ParsedCode(it.content, it.node, it.typography.code, fenced = false) },
    checkbox = {
        val checked = it.content.regionMatches(it.node.startOffset, "[x]", 0, 3, ignoreCase = true)
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(end = 4.dp).semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        })
    },
    table = { MarkdownTable(it.content, it.node, it.typography.table,
        headerBlock = { text, row, width, style -> MarkdownTableHeader(text, row, width, style, maxLines = Int.MAX_VALUE) },
        rowBlock = { text, row, width, style -> MarkdownTableRow(text, row, width, style, maxLines = Int.MAX_VALUE) }) },
    )
}

// JetBrains markdown's fenced-code provider walks CONTENT/EOL tokens. The renderer's
// replaceIndent() instead removes meaningful indentation from the code the user copies.
@Composable
private fun ParsedCode(source: String, node: ASTNode, style: TextStyle, fenced: Boolean) {
    val code = remember(node) {
        val indent = if (!fenced) 4 else {
            var i = node.startOffset
            while (i < node.endOffset && source[i] == ' ') i++
            i - node.startOffset
        }
        buildString {
            var body = !fenced
            for (child in node.children) when (child.type) {
                MarkdownTokenTypes.EOL -> if (body) append(source, child.startOffset, child.endOffset) else body = true
                MarkdownTokenTypes.CODE_LINE, MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                    var i = child.startOffset
                    val limit = minOf(child.endOffset, i + indent)
                    while (i < limit && source[i] == ' ') i++
                    if (i < limit && source[i] == '\t') i++
                    append(source, i, child.endOffset)
                }
            }
        }
    }
    val language = remember(node) { node.children.find { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.let { source.substring(it.startOffset, it.endOffset).substringBefore(' ') } }
    CodeWithCopy(code, language, style)
}

@Composable
private fun CodeWithCopy(code: String, language: String?, style: TextStyle) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.markdown_copy_code)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            DisableSelection {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(language.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, code))) } }) { Text(copyLabel) }
                }
            }
            Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), style = style, softWrap = false)
        }
    }
}
