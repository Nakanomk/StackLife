package io.github.nakanomk.stacklife

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import io.github.nakanomk.stacklife.appearance.*

internal val LocalRootBottom = compositionLocalOf { 0.dp }
internal val chromeGutter = 16.dp

@Composable
internal fun RootPane(header: @Composable () -> Unit,
    overlay: @Composable BoxScope.(top: Dp, bottom: Dp) -> Unit = { _, _ -> },
    content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    val source = rememberPlannerBackdrop()
    val bottom = LocalRootBottom.current
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), topBar = {
        Box(Modifier.fillMaxWidth().then(if (source == null) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)) {
            if (source != null) ScrollEdge(source, Modifier.matchParentSize())
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) { header() }
        }
    }) { insets ->
        val top = insets.calculateTopPadding()
        Box(Modifier.fillMaxSize()) {
            val plane = Modifier.fillMaxSize()
            Box(plane.plannerBackdrop(source)) { content(top, bottom) }
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) { overlay(top, bottom) }
        }
    }
}

@Composable
private fun ScrollEdge(source: PlannerBackdrop, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.surface
    val rectangle = RoundedCornerShape(0.dp)
    Spacer(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val edge = Brush.verticalGradient(0f to Color.Black, .58f to Color.Black.copy(alpha = .85f), 1f to Color.Transparent)
        onDrawWithContent { drawContent(); drawRect(edge, blendMode = BlendMode.DstIn) }
    }.drawPlainBackdrop(source, shape = { rectangle }, effects = { blur(16.dp.toPx()) },
        onDrawSurface = { drawRect(base.copy(alpha = .40f)) }))
}

@Composable
internal fun RootToolbar(onSettings: () -> Unit, leading: (@Composable () -> Unit)? = null,
    controls: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = chromeGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { leading?.invoke() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            StackLifeSurface(CapsuleScene.CHROME, Modifier.testTag("root-toolbar"), MaterialTheme.colorScheme.surfaceContainer, elevation = 1.dp) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center, content = controls)
            }
        }
        GlassIcon(R.drawable.ic_settings, "设置", onSettings)
    }
}

@Composable
internal fun GlassIcon(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    StackLifeSurface(CapsuleScene.ACTION, modifier.size(48.dp), MaterialTheme.colorScheme.surfaceContainer,
        elevation = 1.dp, interactionSource = interaction) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize(), interactionSource = interaction) {
            Icon(painterResource(icon), label)
        }
    }
}
