package io.github.nakanomk.stacklife.appearance

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import kotlin.math.roundToInt
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val LocalPanelPlane = compositionLocalOf<PanelBackdrop?> { null }
private val menuShape = RoundedCornerShape(20.dp)
private val dialogShape = RoundedCornerShape(28.dp)
private val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

internal val LocalPanelBack = compositionLocalOf<(() -> Unit)?> { null }

// A stable receiver crosses into the dialog on its first composition. Only drawing
// observes the recording changing: opening a reader doesn't replace the entire
// solid sheet (including its list, focus and input nodes) with a glass sheet.
private class PanelBackdrop(private val graphics: GraphicsContext, val view: View,
    private val base: Color, private val below: Backdrop?) : Backdrop {
    override val isCoordinatesDependent = true
    private var recording by mutableStateOf<PlannerBackdrop?>(null)
    private var readers = 0
    private var origin = Offset.Unspecified
    private val frame = mutableIntStateOf(0)

    fun acquire() {
        if (readers++ != 0) return
        recording = PlannerBackdrop(graphics.createGraphicsLayer(), base, below, frame).also { it.origin = origin }
    }

    fun release() {
        if (--readers == 0) {
            val layer = checkNotNull(recording).layer
            recording = null
            graphics.releaseGraphicsLayer(layer)
        }
    }

    fun donorDrawn() { frame.intValue++ }

    val capture = Modifier.onGloballyPositioned {
        origin = it.localToScreen(Offset.Zero)
        recording?.origin = origin
    }.drawWithContent {
        val plane = recording
        if (plane == null) drawContent() else drawRecordedBackdrop(plane)
    }

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Float) {
        val plane = recording
        if (plane == null) drawRect(base) else with(plane) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
internal fun PlannerPopupHost(enabled: Boolean = true, content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val plane = if (enabled && LocalPlannerGlassAllowed.current && appearance.glass && glassUnavailableReason(appearance) == null) {
        val graphics = LocalGraphicsContext.current
        val view = LocalView.current
        val base = MaterialTheme.colorScheme.surface
        val below = LocalCapsuleBackdrop.current
        remember(graphics, view, base, below) { PanelBackdrop(graphics, view, base, below) }
    } else null
    CompositionLocalProvider(LocalPanelPlane provides plane) {
        Box(Modifier.fillMaxSize().then(plane?.capture ?: Modifier)) { content() }
    }
}

@Composable
private fun PopupPanel(shape: RoundedCornerShape, modifier: Modifier = Modifier, materialBounds: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val a = LocalAppearance.current
    val plane = LocalPanelPlane.current.takeIf { LocalPlannerGlassAllowed.current && a.glass && glassUnavailableReason(a) == null }
    val popup = LocalView.current
    DisposableEffect(plane, popup) {
        if (plane == null) return@DisposableEffect onDispose {}
        plane.acquire()
        // An activity draw can change a descendant RenderNode without re-recording the host.
        val observer = plane.view.takeUnless { it.rootView === popup.rootView }?.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener(plane::donorDrawn)
        observer?.addOnDrawListener(listener)
        onDispose {
            if (observer?.isAlive == true) observer.removeOnDrawListener(listener)
            plane.release()
        }
    }
    CompositionLocalProvider(LocalCapsuleBackdrop provides plane) {
        StackLifeSurface(CapsuleScene.PANEL, modifier, MaterialTheme.colorScheme.surfaceContainerHigh, 4.dp, shape,
            materialBounds = materialBounds, content = content)
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
internal fun PlannerModalBottomSheet(onDismissRequest: () -> Unit, nested: Boolean = false,
    onNestedBack: (() -> Unit)? = null, canDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val motion = LocalMotionEnabled.current
    val dismissible = rememberUpdatedState(canDismiss && !nested)
    val requestDismiss by rememberUpdatedState(onDismissRequest)
    val config = LocalConfiguration.current
    val fullHeightOnly = config.orientation == Configuration.ORIENTATION_LANDSCAPE || config.screenHeightDp < 480
    var lastVisible by rememberSaveable { mutableStateOf<SheetValue?>(null) }
    val freshWindow = remember { lastVisible == null }
    // A restored portrait half anchor isn't legal in a short window. Keep the content
    // outside this key so changing window classes doesn't replace the editor or its list.
    val sheetState = key(fullHeightOnly) {
        rememberBottomSheetState(
            initialValue = when {
                fullHeightOnly && lastVisible != null -> SheetValue.Expanded
                lastVisible != null -> checkNotNull(lastVisible)
                motion -> SheetValue.Hidden
                fullHeightOnly -> SheetValue.Expanded
                else -> SheetValue.PartiallyExpanded
            },
            enabledValues = remember { if (fullHeightOnly) setOf(SheetValue.Hidden, SheetValue.Expanded) else SheetValue.entries.toSet() },
            confirmValueChange = remember { { target -> target != SheetValue.Hidden || dismissible.value } })
    }
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect { if (it != SheetValue.Hidden) lastVisible = it }
    }
    val close = {
        if (canDismiss) {
            if (nested) onNestedBack?.invoke()
            else if (!motion) requestDismiss()
            else scope.launch { sheetState.hide(); if (!sheetState.isVisible && dismissible.value) requestDismiss() }
        }
        Unit
    }
    ModalBottomSheet(onDismissRequest = { if (dismissible.value) requestDismiss() },
        sheetState = sheetState, shape = sheetShape, containerColor = Color.Transparent,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false, shouldDismissOnClickOutside = !nested && canDismiss),
        sheetGesturesEnabled = true, contentColor = MaterialTheme.colorScheme.onSurface, dragHandle = null, contentWindowInsets = { WindowInsets(0) }) {
        BackHandler(onBack = close)
        val inputWindow = remember(sheetState) { PlatformTextInputInterceptor { request, next ->
            // A dialog has its own insets dispatch. Expand before the IME covers the
            // half-height footer, not after a visibility notification from the other window.
            snapshotFlow { sheetState.currentValue }.first { it != SheetValue.Hidden }
            if (sheetState.currentValue != SheetValue.Expanded) sheetState.expand()
            next.startInputMethod(request)
        } }
        val glassViewport = remember(sheetState) { sheetViewport(sheetState, fullHeightOnly, glass = true) }
        val bodyViewport = remember(sheetState) { sheetViewport(sheetState, fullHeightOnly, glass = false) }
        PopupPanel(sheetShape, Modifier.fillMaxSize().testTag("planner-sheet-shell"), materialBounds = glassViewport) {
            Column(Modifier.fillMaxSize().then(bodyViewport).windowInsetsPadding(WindowInsets.safeDrawing)) {
                val expanded = sheetState.currentValue == SheetValue.Expanded
                val resize: () -> Unit = {
                    scope.launch { if (expanded) sheetState.partialExpand() else sheetState.expand() }
                }
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(firstFocus) { if (freshWindow) firstFocus.requestFocus() }
                SheetHandle(Modifier.align(Alignment.CenterHorizontally).focusRequester(firstFocus).focusProperties { canFocus = true }, expanded, fullHeightOnly,
                    canDismiss, resize, close)
                CompositionLocalProvider(LocalPanelBack provides close) {
                    InterceptPlatformTextInput(inputWindow) { content() }
                }
            }
        }
    }
}

// The shell is full-window for Material's anchors. During entry/exit translate its
// already-sized contents with it; don't grow a LazyColumn from zero or crush it
// against the bottom of the display. A user drag or half ↔ full resize still uses
// the real offset. Keep the measured start size even when a drag settles to Hidden.
@OptIn(ExperimentalMaterial3Api::class)
private fun sheetViewport(state: SheetState, fullOnly: Boolean, glass: Boolean): Modifier {
    var lastFull = -1
    var lastBody = 0
    return Modifier.layout { measurable, constraints ->
        val full = constraints.maxHeight
        val anchored = state.hasExpandedState
        val partial = if (anchored) state.hasPartiallyExpandedState else !fullOnly
        val half = full / 2
        val body = when {
            lastFull == full && state.targetValue == SheetValue.Hidden &&
                (state.isAnimationRunning || state.currentValue == SheetValue.Hidden) -> lastBody
            !anchored || state.currentValue == SheetValue.Hidden ->
                if (partial && state.currentValue != SheetValue.Expanded && state.targetValue != SheetValue.Expanded) half else full
            else -> (full - state.requireOffset().roundToInt()).coerceIn(0, full)
        }
        lastFull = full
        lastBody = body
        // The glass bottom/lens must stay below the physical display at the half anchor.
        val height = if (!glass) body else if (partial && body <= half) (half + 96.dp.roundToPx()).coerceAtMost(full) else full
        val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(child.width, full) { child.placeRelative(0, 0) }
    }
}

@Composable
private fun SheetHandle(modifier: Modifier, expanded: Boolean, fullHeightOnly: Boolean, canDismiss: Boolean,
    resize: () -> Unit, close: () -> Unit) {
    val description = stringResource(when {
        fullHeightOnly -> R.string.ui_close_panel
        expanded -> R.string.ui_sheet_collapse
        else -> R.string.ui_sheet_fullscreen
    })
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val ink = if (pressed || focused) colors.onSurface else colors.outline
    Box(modifier.size(56.dp, 48.dp).testTag("planner-sheet-handle")
        .clickable(interactionSource = interaction, indication = null, enabled = !fullHeightOnly || canDismiss,
            role = Role.Button, onClickLabel = description, onClick = if (fullHeightOnly) close else resize)
        .semantics {
            contentDescription = description
            if (!fullHeightOnly) {
                if (expanded) collapse(description) { resize(); true } else expand(description) { resize(); true }
            }
            if (canDismiss) dismiss { close(); true }
        }, contentAlignment = Alignment.Center) {
        StackLifeSurface(CapsuleScene.INSET, Modifier.size(36.dp, 5.dp).testTag("planner-sheet-grip"), ink, tint = ink.copy(alpha = .80f)) {}
    }
}
