package io.github.nakanomk.stacklife.transitions

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import top.yukonga.miuix.kmp.nav.core.rememberNavEntryDispatcherOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneBackOwnershipTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun disablingTheOuterPaneLeavesNestedBackAndTheWindowOwnerAvailable() {
        val blocked = mutableStateOf(false)
        var exits = 0
        var outerPops = 0
        var innerPops = 0
        ui.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides true) {
                BackHandler { exits++ }
                var folder by remember { mutableStateOf(false) }
                val origins = remember { PaneOrigins() }
                PaneMotion(folder, origins, { if (it) 1 else 0 }, { if (it) "folder" else "root" }, Modifier.fillMaxSize(),
                    parent = false.takeIf { folder && !blocked.value }, onBack = { outerPops++; folder = false }) { inFolder ->
                    if (!inFolder) Button({ folder = true }, Modifier.paneOrigin(origins, "folder")) { Text("Open folder") }
                    else {
                        var editing by remember { mutableStateOf(false) }
                        val inner = remember { PaneOrigins() }
                        PaneMotion(editing, inner, { if (it) 1 else 0 }, { if (it) "editor" else "summary" }, Modifier.fillMaxSize(),
                            parent = false.takeIf { editing }, onBack = { innerPops++; editing = false }) { inEditor ->
                            if (!inEditor) Button({ editing = true }, Modifier.paneOrigin(inner, "editor")) { Text("Open editor") }
                            else {
                                var draft by remember { mutableStateOf("Draft") }
                                Column { Text(draft); Button({ draft = "Changed locally" }) { Text("Edit draft") } }
                            }
                        }
                    }
                }
            }
        }
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        try {
            ui.runOnIdle { input.backCompleted() }
            ui.runOnIdle { assertEquals(1, exits) }
            ui.onNodeWithText("Open folder").performClick()
            ui.onNodeWithText("Open editor").performClick()
            ui.onNodeWithText("Edit draft").performClick()
            ui.runOnIdle { blocked.value = true }
            fun event(progress: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)
            ui.runOnIdle { input.backStarted(event(0f)) }
            ui.runOnIdle { input.backProgressed(event(.65f)) }
            ui.runOnIdle { input.backProgressed(event(.2f)) }
            ui.runOnIdle { input.backCancelled() }
            ui.onNodeWithText("Changed locally").assertIsDisplayed()
            ui.runOnIdle { assertEquals(0, innerPops); assertEquals(0, outerPops); input.backCompleted() }
            ui.onNodeWithText("Open editor").assertIsDisplayed()
            ui.runOnIdle { assertEquals(1, innerPops); assertEquals(0, outerPops); input.backCompleted() }
            ui.runOnIdle { assertEquals(2, exits); blocked.value = false }
            ui.runOnIdle { input.backCompleted() }
            ui.onNodeWithText("Open folder").assertIsDisplayed()
            ui.runOnIdle { assertEquals(1, outerPops); assertEquals(1, innerPops); assertEquals(2, exits) }
        } finally { ui.runOnIdle { ui.activity.navigationEventDispatcher.removeInput(input) } }
    }

    @Test fun dialogWindowBackPopsOnlyTheNestedPaneThenDismissesTheUnblockedSheet() {
        val input = DirectNavigationEventInput()
        val activityDispatcher = ui.activity.navigationEventDispatcher
        val busy = mutableStateOf(false)
        var popped = 0
        var dismissed = 0
        ui.setContent {
            val coveredRoute = rememberNavEntryDispatcherOwner(false, checkNotNull(LocalNavigationEventDispatcherOwner.current))
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides coveredRoute, LocalMotionEnabled provides true) {
                var open by remember { mutableStateOf(true) }
                var detail by remember { mutableStateOf(false) }
                if (open) PlannerModalBottomSheet({ dismissed++; open = false }, nested = detail, canDismiss = !busy.value,
                    onNestedBack = { error("The dialog bypassed its active pane") }) {
                    val dispatcher = checkNotNull(LocalView.current.findViewTreeNavigationEventDispatcherOwner()).navigationEventDispatcher
                    DisposableEffect(dispatcher) {
                        assertNotSame(activityDispatcher, dispatcher)
                        dispatcher.addInput(input)
                        onDispose { dispatcher.removeInput(input) }
                    }
                    val origins = remember { PaneOrigins() }
                    PaneMotion(detail, origins, { if (it) 1 else 0 }, { if (it) "detail" else "list" }, Modifier.fillMaxSize(),
                        parent = false.takeIf { detail && !busy.value }, onBack = { popped++; detail = false }) { shown ->
                        if (shown) Text("Sheet detail") else Button({ detail = true }, Modifier.paneOrigin(origins, "detail")) { Text("Open row") }
                    }
                } else Text("Closed")
            }
        }
        ui.onNodeWithText("Open row").performClick()
        ui.onNodeWithText("Sheet detail").assertIsDisplayed()
        ui.runOnIdle { busy.value = true }
        ui.runOnIdle { input.backCompleted() }
        ui.onNodeWithText("Sheet detail").assertIsDisplayed()
        ui.runOnIdle { assertEquals(0, popped); assertEquals(0, dismissed); busy.value = false }
        ui.runOnIdle { input.backCompleted() }
        ui.onNodeWithText("Open row").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, popped); assertEquals(0, dismissed); input.backCompleted() }
        ui.onNodeWithText("Closed").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, popped); assertEquals(1, dismissed) }
    }
}
