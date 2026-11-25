// Adapted from BiliPai AppHapticSettings and AppScrollBoundaryHaptics (c7f835b, GPL-3.0).
// See assets/licenses/GestureHaptics-Notices.txt for the source and local differences.
package io.github.nakanomk.stacklife.appearance

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import io.github.nakanomk.stacklife.planner.HapticLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

@Composable
private fun rememberScenePulse(level: HapticLevel, tick: HapticFeedbackType): () -> Unit {
    // StackLifeTheme already gates this delegate on the device setting and the master switch.
    val feedback = LocalHapticFeedback.current
    return remember(level, tick, feedback) {
        {
            when (level) {
                HapticLevel.OFF -> Unit
                HapticLevel.LIGHT -> feedback.performHapticFeedback(tick)
                HapticLevel.STANDARD -> feedback.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }
}

/** Call only from the input owner when it accepts a different selection, never its observer. */
@Composable
internal fun rememberNavigationPulse(): () -> Unit =
    rememberScenePulse(LocalAppearance.current.navigationHaptics, HapticFeedbackType.SegmentTick)

/** Observe the actual vertical scrolling child; don't consume motion or replace platform overscroll. */
@Composable
internal fun Modifier.scrollBoundaryHaptics(state: ScrollableState): Modifier {
    val level = LocalAppearance.current.edgeHaptics
    if (level == HapticLevel.OFF) return this
    val pulse by rememberUpdatedState(rememberScenePulse(level, HapticFeedbackType.GestureThresholdActivate))
    val connection = remember(state) {
        object : NestedScrollConnection {
            private var lastForward: Boolean? = null
            private var userScrolled = false
            private var userFling: Job? = null

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // A scrolling descendant doesn't activate an idle ancestor. Direct scrollTo/scrollBy
                // (including edge auto-scroll during tray pickup) don't establish an input session.
                if (available.y != 0f && source == NestedScrollSource.UserInput) {
                    userScrolled = state.isScrollInProgress
                    userFling = null
                }
                if ((lastForward == true && state.canScrollForward) || (lastForward == false && state.canScrollBackward)) {
                    lastForward = null
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!state.isScrollInProgress || (source != NestedScrollSource.UserInput && userFling?.isActive != true)) return Offset.Zero
                val delta = -(consumed.y + available.y)
                if (delta == 0f) return Offset.Zero
                val forward = delta > 0f
                val canContinue = if (forward) state.canScrollForward else state.canScrollBackward
                val canMoveAway = if (forward) state.canScrollBackward else state.canScrollForward
                if (canContinue) {
                    if (consumed.y != 0f) lastForward = null
                } else if (canMoveAway && lastForward != forward) {
                    lastForward = forward
                    pulse()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // An interrupted fling can omit postFling. Its cancelled job must never
                // admit a later bring-into-view animation as if a finger had scrolled.
                userFling = if (userScrolled && available.y != 0f) currentCoroutineContext().job else null
                userScrolled = false
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (userFling === currentCoroutineContext().job) userFling = null
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection)
}
