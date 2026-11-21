package io.github.nakanomk.stacklife.appearance

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
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val LocalPanelReaders = compositionLocalOf<MutableIntState?> { null }
private val LocalPanelPlane = compositionLocalOf<PlannerBackdrop?> { null }
private val LocalPanelHostView = compositionLocalOf<View?> { null }
private val menuShape = RoundedCornerShape(20.dp)
private val dialogShape = RoundedCornerShape(28.dp)
private val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

@Composable
internal fun PlannerPopupHost(enabled: Boolean = true, content: @Composable () -> Unit) {
    val readers = if (enabled) remember { mutableIntStateOf(0) } else null
    val plane = rememberPlannerBackdrop(enabled = readers != null && readers.intValue > 0, acrossWindows = true)
    CompositionLocalProvider(LocalPanelReaders provides readers, LocalPanelPlane provides plane, LocalPanelHostView provides LocalView.current) {
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
internal fun PlannerModalBottomSheet(onDismissRequest: () -> Unit, sheetState: SheetState, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val close = { scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismissRequest() }; Unit }
    ModalBottomSheet(onDismissRequest, sheetState = sheetState, shape = sheetShape, containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface, dragHandle = null, contentWindowInsets = { WindowInsets(0) }) {
        PopupPanel(sheetShape, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(BottomSheetDefaults.modalWindowInsets)) {
                Box(Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).clickable(onClickLabel = "关闭面板", onClick = close)
                    .semantics { dismiss { close(); true } }, contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                content()
            }
        }
    }
}
