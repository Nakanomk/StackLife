package io.github.nakanomk.stacklife.transitions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled

internal data class DetailTarget(val kind: String, val id: String) {
    init { require((kind == "task" || kind == "frame") && id.isNotBlank()) }
}

/** Only the local system-transition build provides this hook. No capture or registration in the normal app. */
internal interface DetailTransitionHost {
    @Composable fun listSource(scroll: LazyListState, topClearance: Dp, bottomClearance: Dp, revision: Long): DetailListSource?
}

internal interface DetailListSource {
    val modifier: Modifier
    @Composable fun card(target: DetailTarget, shape: Shape, fallback: () -> Unit): DetailCardLink
}

internal class DetailCardLink(val modifier: Modifier, val open: () -> Unit)

@Composable
internal fun detailListSource(scroll: LazyListState, topClearance: Dp, bottomClearance: Dp, revision: Long): DetailListSource? {
    if (!LocalMotionEnabled.current) return null
    val host = LocalContext.current.applicationContext as? DetailTransitionHost ?: return null
    return host.listSource(scroll, topClearance, bottomClearance, revision)
}

@Composable
internal fun DetailCard(
    source: DetailListSource?, kind: String, id: String, onDetail: (String) -> Unit,
    modifier: Modifier, shape: Shape, color: Color, shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val open = { onDetail(id) }
    val link = source?.card(remember(kind, id) { DetailTarget(kind, id) }, shape, open)
    Box(propagateMinConstraints = true) {
        Surface(onClick = link?.open ?: open, modifier = modifier.then(link?.modifier ?: Modifier),
            shape = shape, color = color, shadowElevation = shadowElevation, content = content)
        CardBoundsAnchor("$kind:$id", Modifier.matchParentSize(), shape)
    }
}

@Composable
internal fun CardTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardMetadata(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                content()
            }
        }
    }
}

@Composable
internal fun CardSummary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}
