package io.github.nakanomk.stacklife

import androidx.compose.foundation.background
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
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.glass.*
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
            Box(if (source == null) plane else plane.hazeSource(source)) { content(top, bottom) }
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) { overlay(top, bottom) }
        }
    }
}

@Composable
private fun ScrollEdge(source: HazeState, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.surface
    val style = remember(base) { GlassStyle.regular.then {
        shape(RoundedCornerShape(0.dp))
        backgroundColor(base)
        tint(base.copy(alpha = .46f))
        optics { copy(depth = OpticalSizeValue.Fixed(1f), blurRadius = OpticalSizeValue.Fixed(14.dp),
            refractionStrength = 0f, refractionDisplacement = 0.dp, refractionDetailIntensity = 0f) }
        chromaticAberrationStrength(0f)
        edgeShadow(Color.Transparent)
        specularIntensity(0f)
        whitePoint(0f)
    } }
    val input = remember(source) { HazeInput.Sources(source, retention = HazeSourceRetention.ClearWhenUnavailable) }
    Spacer(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val edge = Brush.verticalGradient(0f to Color.Black, .58f to Color.Black.copy(alpha = .85f), 1f to Color.Transparent)
        onDrawWithContent { drawContent(); drawRect(edge, blendMode = BlendMode.DstIn) }
    }.hazeGlass(input = input, style = style))
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
    StackLifeSurface(CapsuleScene.CHROME, modifier.size(48.dp), MaterialTheme.colorScheme.surfaceContainer, elevation = 1.dp) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(painterResource(icon), label, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        }
    }
}
