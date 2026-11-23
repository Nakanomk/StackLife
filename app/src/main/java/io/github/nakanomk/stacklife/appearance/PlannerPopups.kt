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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val LocalPanelReaders = compositionLocalOf<MutableIntState?> { null }
private val LocalPanelPlane = compositionLocalOf<PlannerBackdrop?> { null }
private val LocalPanelHostView = compositionLocalOf<View?> { null }
private val menuShape = RoundedCornerShape(20.dp)
private val dialogShape = RoundedCornerShape(28.dp)
private val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

internal val LocalPanelBack = compositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun PlannerPopupHost(enabled: Boolean = true, content: @Composable () -> Unit) {
    val readers = if (enabled) remember { mutableIntStateOf(0) } else null
    val plane = rememberPlannerBackdrop(enabled = readers != null && readers.intValue > 0, acrossWindows = true)
    CompositionLocalProvider(LocalPanelReaders provides readers, LocalPanelPlane provides plane, LocalPanelHostView provides LocalView.current) {
        Box(Modifier.fillMaxSize().plannerBackdrop(plane)) { content() }
    }
}

@Composable
private fun PopupPanel(shape: RoundedCornerShape, modifier: Modifier = Modifier, materialBounds: Modifier = Modifier,
    content: @Composable () -> Unit) {
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
        // Material keeps full-height drag anchors. Only the glass backing needs a smaller
        // render target during hidden ↔ half travel. Keep its bottom edge off screen and
        // its size fixed until crossing the half anchor, not a new GPU buffer each frame.
        val glassViewport = remember(sheetState) {
            Modifier.layout { measurable, constraints ->
                val full = constraints.maxHeight
                val half = full / 2
                val height = if (sheetState.hasPartiallyExpandedState && sheetState.requireOffset() >= half)
                    (half + 96.dp.roundToPx()).coerceAtMost(full) else full
                val glass = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(glass.width, full) { glass.place(0, 0) }
            }
        }
        PopupPanel(sheetShape, Modifier.fillMaxSize().testTag("planner-sheet-shell"), materialBounds = glassViewport) {
            // Material positions the full-height shell at its drag offset. Only the
            // visible part may measure the list/footer; otherwise the IME or the
            // partial anchor puts bottom actions below the physical screen.
            Column(Modifier.fillMaxSize().layout { measurable, constraints ->
                val offset = if (sheetState.hasExpandedState) sheetState.requireOffset().roundToInt() else 0
                val visible = constraints.maxHeight - offset.coerceIn(0, constraints.maxHeight)
                val child = measurable.measure(constraints.copy(minHeight = visible, maxHeight = visible))
                layout(child.width, constraints.maxHeight) { child.placeRelative(0, 0) }
            }.windowInsetsPadding(WindowInsets.safeDrawing)) {
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
