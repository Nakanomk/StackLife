package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first

/** The picker, arrows, device clock and restored navigation state can also select a page.
 * Publish a touch selection only once it settles; a cancelled swipe never changes scope. */
@Composable
internal fun CalendarPager(page: Int, count: Int, onSelect: (Int) -> Unit, modifier: Modifier,
    content: @Composable (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = page) { count }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(page, count, pager) {
        if (pager.settledPage != page) pager.scrollToPage(page)
        val settled = snapshotFlow { pager.settledPage }.first { it != page }
        select(settled)
    }
    HorizontalPager(state = pager, modifier = modifier, key = { it }, pageContent = { content(it) })
}
