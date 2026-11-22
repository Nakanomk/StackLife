package io.github.nakanomk.stacklife.countdown

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.*
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.deviceDay
import io.github.nakanomk.stacklife.transitions.routeOrigin
import io.github.nakanomk.stacklife.workspace.*

/** TimeTrace HomeScreen's 600dp breakpoint and card spacing; order belongs to the local planner transaction. */
@Composable
internal fun CountdownRoute(model: WorkspaceViewModel, onOpen: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val state by remember(model) { model.read<CountdownsView>(PlannerQuery.Countdowns) }.collectAsStateWithLifecycle(ReadState.Loading)
    val today = deviceDay().date
    var arranging by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val receipt = rememberSubmission(model, onError = { error = it })
    ReadPanel(state) { view ->
        val pinnedCount = remember(view.items) { view.items.indexOfFirst { !it.pinned }.let { if (it < 0) view.items.size else it } }
        RootPane(header = { RootToolbar(onSettings, leading = {
            if (pinnedCount > 1 || view.items.size - pinnedCount > 1 || arranging) ToolbarIcon(
                if (arranging) R.drawable.ic_close else R.drawable.ic_drag_handle,
                stringResource(if (arranging) R.string.ui_done else R.string.countdown_reorder), enabled = !receipt.busy) {
                arranging = !arranging; error = null
            }
        }) {
            TextButton(onClick = onAdd, enabled = !receipt.busy, modifier = Modifier.testTag("countdown-add").routeOrigin("edit-countdown:new")) {
                Text(stringResource(R.string.countdown_new))
            }
        } }) { top, bottom ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                LazyVerticalGrid(GridCells.Fixed(if (maxWidth < 600.dp) 1 else 2), Modifier.fillMaxSize().testTag("countdown-list"),
                    contentPadding = dockListPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (view.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle(stringResource(R.string.countdown_empty)); Hint(stringResource(R.string.countdown_empty_hint))
                        }
                    }
                    items(view.items, key = { it.id }) { card ->
                        Column {
                            CountdownCard(card, today, Modifier.routeOrigin("countdown:${card.id}", RoundedCornerShape(24.dp))
                                .testTag("open-countdown:${card.id}"), onClick = { onOpen(card.id) })
                            if (arranging) {
                                val groupStart = if (card.pinned) 0 else pinnedCount
                                val count = if (card.pinned) pinnedCount else view.items.size - pinnedCount
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    for (delta in listOf(-1, 1)) {
                                        val target = card.position + delta
                                        IconButton(enabled = target in 0 until count && !receipt.busy, modifier = Modifier.testTag("countdown-move:${card.id}:$delta"), onClick = {
                                            val ids = view.items.subList(groupStart, groupStart + count).mapTo(ArrayList(count)) { it.id }
                                            java.util.Collections.swap(ids, card.position, target)
                                            error = null
                                            receipt.submit { model.startReviewed(listOf(PlannerCommand.CountdownOrder(card.pinned, ids, view.revision)), view.revision) }
                                        }) { Icon(painterResource(if (delta < 0) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                                            stringResource(if (delta < 0) R.string.ui_move_tab_up else R.string.ui_move_tab_down, card.title)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CountdownDetail(model: WorkspaceViewModel, id: String, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val state by remember(model) { model.read<CountdownsView>(PlannerQuery.Countdowns) }.collectAsStateWithLifecycle(ReadState.Loading)
    var selected by rememberSaveable(id) { mutableStateOf(id) }
    val today = deviceDay().date
    ReadPanel(state) { view ->
        val list = view.items
        val index = remember(list, selected) { list.indexOfFirst { it.id == selected } }
        if (index < 0) { CountdownMissing(onBack); return@ReadPanel }
        val pager = rememberPagerState(initialPage = index) { list.size }
        LaunchedEffect(list, pager) {
            // A pin or reorder changes indices. Restore the ID before listening to swipes in the new list.
            pager.scrollToPage(index)
            snapshotFlow { pager.settledPage }.collect { page -> list.getOrNull(page)?.let { selected = it.id } }
        }
        EditorPage(stringResource(R.string.countdown_details), onBack, AssistantPage.Countdown(selected), headerAction = {
            TextButton(enabled = !pager.isScrollInProgress, onClick = { onEdit(selected) },
                modifier = Modifier.testTag("countdown-edit").routeOrigin("edit-countdown:$selected")) { Text(stringResource(R.string.countdown_edit_action)) }
        }) { top, bottom ->
            VerticalPager(state = pager, key = { list[it].id }, modifier = Modifier.fillMaxSize().padding(top = top, bottom = bottom)
                .testTag("countdown-detail-pager")) { page ->
                val card = list[page]
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    CountdownFace(card, today, Modifier.align(Alignment.Center).testTag("countdown-detail:${card.id}"), large = true)
                }
            }
        }
    }
}

@Composable
internal fun CountdownMissing(back: () -> Unit) {
    EditorPage(stringResource(R.string.countdown_details), back) { top, bottom ->
        Text(stringResource(R.string.countdown_not_found), Modifier.padding(dockListPadding(top, bottom)))
    }
}
