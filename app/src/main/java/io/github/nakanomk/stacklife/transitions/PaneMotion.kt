package io.github.nakanomk.stacklife.transitions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.nav.core.ProvideNavEntryBackScope
import top.yukonga.miuix.kmp.nav.core.rememberNavEntryDispatcherOwner

// Same fixed BiliPai NavMotionLayout and container renderer as the root, inside the actual sheet.
internal class PaneOrigins : ContainerOrigins()
private val LocalPane = staticCompositionLocalOf<String?> { null }

internal fun Modifier.paneOrigin(origins: PaneOrigins, key: String): Modifier = composed {
    val owner = LocalPane.current ?: return@composed this
    val density = LocalDensity.current
    val site = OriginSite(owner, key, currentCompositeKeyHashCode)
    val anchor = remember(site, density) { ContainerAnchor(site, RoundedCornerShape(16.dp), density) }
    containerSource(origins, anchor)
}

private class PaneSlot<T : Any>(val key: String) {
    var value by mutableStateOf<T?>(null)
}

/** Keep only business route identities in saved state. Never serialize a draft, private file,
 * session, or launcher. On recreation the caller supplies the real page and its return parent;
 * deeper, fully covered identities receive their business values before they can be revealed. */
private class PanePath<T : Any>(var keys: List<String> = emptyList()) {
    val slots = HashMap<String, PaneSlot<T>>()
    fun slot(key: String) = slots.getOrPut(key) { PaneSlot(key) }

    fun select(page: T, id: String, parent: T?, parentId: String?, depth: (T) -> Int, origins: PaneOrigins) {
        val lastId = keys.lastOrNull()
        val previous = slots[lastId]?.value
        val same = lastId == id
        val predecessor = keys.getOrNull(keys.lastIndex - 1)
        if (!same || parentId != null && parentId != predecessor) {
            val at = keys.indexOf(id)
            val from = if (parentId == null) -1 else keys.indexOf(parentId)
            val push = !same && previous != null && at < 0 && depth(page) > depth(previous)
            val next = when {
                parentId != null && from >= 0 -> keys.take(from + 1) + id
                parentId != null -> listOf(parentId, id)
                at >= 0 -> keys.take(at + 1)
                push -> keys + id // e.g. the school browser, which handles its own Back.
                else -> listOf(id)
            }
            if (push) origins.capture(id, lastId)
            keys = next
            slots.keys.retainAll(next)
        }
        slot(id).value = page
        if (parentId != null && parent != null) slot(parentId).value = parent
    }
}

@Composable
internal fun <T : Any> PaneMotion(
    page: T, origins: PaneOrigins, depth: (T) -> Int, originKey: (T) -> String,
    modifier: Modifier = Modifier, parent: T? = null, onBack: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val front = LocalFrontEntry.current
    val id = originKey(page)
    val parentId = parent?.let(originKey)
    val canPop = front && parentId != null && onBack != null
    // A Kotlin local ::back can compare equal across pages even though it captured a
    // different return parent. Keep the invocation target by identity, not by equals.
    val backTarget = remember { mutableStateOf<(() -> Unit)?>(null, referentialEqualityPolicy()) }
    backTarget.value = onBack.takeIf { canPop }
    val commitBack: () -> Unit = remember { { backTarget.value?.invoke(); Unit } }
    if (!LocalMotionEnabled.current) {
        BackHandler(enabled = canPop, onBack = commitBack)
        CompositionLocalProvider(LocalPane provides id) {
            Box(modifier, propagateMinConstraints = true) { content(page) }
        }
        return
    }
    val path = rememberSaveable(origins, saver = remember {
        Saver<PanePath<T>, ArrayList<String>>(save = { ArrayList(it.keys) }, restore = { PanePath(it) })
    }) { PanePath() }
    path.select(page, id, parent, parentId, depth, origins)
    val keys = path.keys
    // Assistant settings can close the dialog while its business-level origins survive. A new
    // dialog is not the original launcher, even when the restored row has the same key and size.
    DisposableEffect(origins) { onDispose {
        origins.host = null
        origins.links.clear()
    } }

    val upstream = LocalNavigationEventDispatcherOwner.current
    val panelDispatcher = upstream?.let { rememberNavEntryDispatcherOwner(canPop, it) }
    if (upstream == null) BackHandler(enabled = canPop, onBack = commitBack)
    // These are panes of one business route, not new owners of its editor/ViewModel. Keep the
    // motion library's internal stores scoped to this display (especially for reopened dialogs).
    val businessOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val displayOwner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(displayOwner) { onDispose { displayOwner.viewModelStore.clear() } }

    val renderer = rememberUpdatedState<@Composable (PaneSlot<T>) -> Unit> { slot ->
        val shown = slot.value
        if (shown != null) {
            val progress = rememberContainerProgress()
            val selected = front && slot.key == id
            val interactive = selected && !progress.moving && !progress.covered
            // A resting sheet is transparent to the real dialog material, never to a retained
            // transcript or form. Keep its parent placed for Back; paint it only when revealed.
            val exposed = remember(progress) { derivedStateOf { progress.scope.relativeDepth < 1f } }
            val paint = remember(exposed) { Modifier.drawWithContent { if (exposed.value) drawContent() } }
            val input = if (interactive) Modifier else Modifier.clearAndSetSemantics {}
                .focusProperties { onEnter = { cancelFocusChange() } }.focusGroup().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                }
            val link = origins.links[slot.key]
            DisposableEffect(slot, link) { onDispose {
                if (slot.key !in path.keys && link != null && origins.links[slot.key] === link) origins.links.remove(slot.key)
            } }
            // Disable this display's own pop independently of the nested page's handlers. The
            // actual WebView's Back, a busy form, and a nested display still see their own owner.
            val childDispatcher = upstream?.let { rememberNavEntryDispatcherOwner(selected && !progress.covered, it) }
            ProvideNavEntryBackScope(childDispatcher) {
                CompositionLocalProvider(LocalPane provides slot.key, LocalFrontEntry provides selected,
                    LocalViewModelStoreOwner provides businessOwner) {
                    RoutePage(origins, link, link?.let(origins::source), progress, paint.testTag("pane:${slot.key}").then(input)) {
                        content(shown)
                    }
                }
            }
        }
    }
    val entries = remember(keys) { HashMap<String, @Composable (Any) -> Unit>(keys.size) }
    for (owner in keys) entries[owner] = key(owner) {
        val slot = path.slot(owner)
        remember(slot) {
            val draw: @Composable (Any) -> Unit = { renderer.value(slot) }
            draw
        }
    }
    val entryContent = remember(entries) { { key: Any -> entries.getValue(key as String) } }
    val window = LocalWindowInfo.current.containerSize
    ProvideNavEntryBackScope(panelDispatcher) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides displayOwner) {
            ContainerMotionLayout(keys, keys, commitBack, modifier.onGloballyPositioned {
                origins.host = it
                origins.viewportSize = it.size
                origins.size = window
            }, entryContent)
        }
    }
}
