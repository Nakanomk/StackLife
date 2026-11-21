package io.github.nakanomk.stacklife.appearance

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.transitions.landingAlpha

import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private val LocalPanelReaders = compositionLocalOf<MutableIntState?> { null }
private val LocalPanelPlane = compositionLocalOf<PlannerBackdrop?> { null }
private val LocalPanelHostView = compositionLocalOf<View?> { null }
private val menuShape = RoundedCornerShape(20.dp)
private val dialogShape = RoundedCornerShape(28.dp)
private val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

private val LocalPanelLaunches = staticCompositionLocalOf<MutableMap<String, PanelLaunch>?> { null }
internal val LocalPanelBack = compositionLocalOf<(() -> Unit)?> { null }

internal class PanelLaunch internal constructor(private val key: String, private val launches: MutableMap<String, PanelLaunch>?) {
    private var node: LayoutCoordinates? = null
    internal var opening: Rect? = null
        private set
    val modifier = Modifier.onGloballyPositioned { node = it }
    internal fun liveBounds(): Rect? = node?.takeIf { it.isAttached && !it.boundsInWindow().isEmpty }?.let {
        val topLeft = it.localToScreen(Offset.Zero)
        Rect(topLeft.x, topLeft.y, topLeft.x + it.size.width, topLeft.y + it.size.height)
    }
    fun open(action: () -> Unit) {
        opening = liveBounds()
        launches?.set(key, this)
        action()
    }
}

@Composable
internal fun rememberPanelLaunch(key: String): PanelLaunch {
    val launches = LocalPanelLaunches.current
    return remember(key, launches) { PanelLaunch(key, launches) }
}

@Composable
internal fun PlannerPopupHost(enabled: Boolean = true, content: @Composable () -> Unit) {
    val readers = if (enabled) remember { mutableIntStateOf(0) } else null
    val plane = rememberPlannerBackdrop(enabled = readers != null && readers.intValue > 0, acrossWindows = true)
    val launches = LocalPanelLaunches.current ?: remember { mutableMapOf<String, PanelLaunch>() }
    CompositionLocalProvider(LocalPanelReaders provides readers, LocalPanelPlane provides plane, LocalPanelHostView provides LocalView.current, LocalPanelLaunches provides launches) {
        Box(Modifier.fillMaxSize().plannerBackdrop(plane)) { content() }
    }
}

@Composable
private fun PopupPanel(shape: RoundedCornerShape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = LocalAppearance.current
    val readers = LocalPanelReaders.current.takeIf { LocalPlannerGlassAllowed.current && a.glass && glassUnavailableReason(a) == null }
    DisposableEffect(readers) {
        if (readers != null) readers.intValue++
        onDispose { if (readers != null) readers.intValue-- }
    }
    val plane = LocalPanelPlane.current.takeIf { readers != null }
    val host = LocalPanelHostView.current?.takeIf { plane != null }
    val popup = LocalView.current
    DisposableEffect(host, popup, plane) {
        val frame = plane?.windowFrame
        if (host == null || frame == null || host.rootView === popup.rootView) return@DisposableEffect onDispose {}
        // A window invalidation alone does not invalidate cached RenderEffects in the other window.
        val observer = host.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener { frame.intValue++ }
        observer.addOnDrawListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnDrawListener(listener) }
    }
    CompositionLocalProvider(LocalCapsuleBackdrop provides plane) {
        StackLifeSurface(CapsuleScene.PANEL, modifier, MaterialTheme.colorScheme.surfaceContainerHigh, 4.dp, shape, content = content)
    }
}

@Composable
internal fun PlannerDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenuPopup(expanded, onDismissRequest) {
        PopupPanel(menuShape) {
            Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 6.dp), content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    BasicAlertDialog(onDismissRequest, modifier) {
        PopupPanel(dialogShape) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                if (text != null) Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium, text) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    dismissButton?.invoke(); confirmButton()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerModalBottomSheet(onDismissRequest: () -> Unit, sheetState: SheetState, nested: Boolean = false,
    onNestedBack: (() -> Unit)? = null, windowKey: String? = null, canDismiss: Boolean = true,
    dragHandle: (@Composable (close: () -> Unit) -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val launches = LocalPanelLaunches.current
    val launch = remember(windowKey) { windowKey?.let { launches?.remove(it) } }
    val motion = LocalMotionEnabled.current && launch?.opening != null
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    var closing by remember { mutableStateOf(false) }
    var outer by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var inner by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(motion) { if (motion) progress.animateTo(1f, tween(300)) else progress.snapTo(1f) }
    suspend fun shrink() {
        if (!canDismiss) return
        closing = true
        if (launch?.liveBounds() == null) {
            sheetState.hide()
            if (sheetState.isVisible) return
        } else {
            progress.animateTo(0f, tween(220))
            // Let the cleared shell and scrim reach the display before releasing the dialog window.
            withFrameNanos { }
        }
        onDismissRequest()
    }
    val shell = if (!motion) Modifier else Modifier.onGloballyPositioned { outer = it }.drawWithCache {
        val path = Path()
        val landing = Paint()
        onDrawWithContent {
            val p = progress.value
            if (p <= 0f) return@onDrawWithContent
            if (p >= 1f) { drawContent(); return@onDrawWithContent }
            val host = outer
            val sheet = inner
            val anchor = if (closing) launch?.liveBounds() else launch?.opening
            if (anchor == null || host == null || !host.isAttached || sheet == null || !sheet.isAttached) {
                if (p > 0f) drawContent()
                return@onDrawWithContent
            }
            val target = host.localBoundingBoxOf(sheet, clipBounds = false)
            val start = anchor.translate(-host.localToScreen(Offset.Zero))
            val bounds = lerp(start, target, p)
            path.reset(); path.addRoundRect(RoundRect(bounds, CornerRadius(lerpRadius(start, p, 28.dp.toPx()))))
            clipPath(path) {
                val opacity = landingAlpha(p)
                if (opacity < 1f) {
                    landing.alpha = opacity
                    drawContext.canvas.saveLayer(bounds, landing)
                }
                translate(bounds.left - target.left, bounds.top - target.top) { this@onDrawWithContent.drawContent() }
                if (opacity < 1f) drawContext.canvas.restore()
            }
        }
    }
    val close = {
        if (nested) onNestedBack?.invoke()
        else if (motion) scope.launch { shrink() }
        else scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismissRequest() }
        Unit
    }
    val scrim = BottomSheetDefaults.ScrimColor
    ModalBottomSheet(onDismissRequest, modifier = shell, sheetState = sheetState, shape = sheetShape, containerColor = Color.Transparent,
        scrimColor = if (motion) scrim.copy(alpha = scrim.alpha * progress.value) else scrim,
        // Back within a nested level belongs to its live pane transition, not the sheet's hide animation.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !nested && !motion, shouldDismissOnClickOutside = !nested && canDismiss),
        sheetGesturesEnabled = !nested, contentColor = MaterialTheme.colorScheme.onSurface, dragHandle = null, contentWindowInsets = { WindowInsets(0) }) {
        PredictiveBackHandler(enabled = motion && !nested) { events ->
            try {
                events.collect {
                    if (canDismiss && launch?.liveBounds() != null) { closing = true; progress.snapTo(1f - it.progress) }
                }
                shrink()
            } catch (cancel: CancellationException) {
                scope.launch { progress.animateTo(1f, tween(180)); closing = false }
                throw cancel
            }
        }
        PopupPanel(sheetShape, Modifier.fillMaxWidth().onGloballyPositioned { inner = it }) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(BottomSheetDefaults.modalWindowInsets)) {
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    if (dragHandle != null) dragHandle(close)
                    else Box(Modifier.heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.ui_close_panel), onClick = close)
                        .semantics { dismiss { close(); true } }, contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                }
                CompositionLocalProvider(LocalPanelBack provides close) { content() }
            }
        }
    }
}

private fun lerpRadius(start: Rect, p: Float, sheetRadius: Float): Float =
    minOf(sheetRadius, start.minDimension / 2) * (1f - p) + sheetRadius * p
