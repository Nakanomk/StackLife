package io.github.nakanomk.stacklife.acceptance

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals

/** Check the shell's measured height and its position, not just the handle's label. */
internal fun SemanticsNodeInteractionsProvider.exerciseSheetAnchors(surface: String, startsFull: Boolean = false) {
    val dialog = onNode(isDialog(), useUnmergedTree = true)
    val window = dialog.getUnclippedBoundsInRoot()
    val instance = dialog.fetchSemanticsNode().id
    val shell = onNodeWithTag("planner-sheet-shell", useUnmergedTree = true)
    val handle = onNodeWithTag("planner-sheet-handle")
    val inst = InstrumentationRegistry.getInstrumentation()
    val config = inst.targetContext.resources.configuration
    val compact = config.orientation == Configuration.ORIENTATION_LANDSCAPE || config.screenHeightDp < 480
    fun checkAnchor(full: Boolean) {
        val rect = shell.getUnclippedBoundsInRoot()
        val height = window.bottom - window.top
        assertEquals("$surface shell height", height.value, (rect.bottom - rect.top).value, 1f)
        assertEquals("$surface shell position", (window.top + if (full) 0.dp else height / 2).value, rect.top.value, 1f)
        assertEquals(instance, dialog.fetchSemanticsNode().id)
    }
    checkAnchor(full = compact || startsFull)
    val travel = shell.fetchSemanticsNode().size.height * .4f
    if (!compact) {
        if (startsFull) {
            handle.performTouchInput { swipe(center, center + Offset(0f, travel), 350) }
            checkAnchor(full = false)
        }
        handle.performTouchInput { swipe(center, center - Offset(0f, travel), 300) }
        checkAnchor(full = true)
    }
    if (InstrumentationRegistry.getArguments().getString("captureSheets") == "yes") {
        val image = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { File(inst.targetContext.getExternalFilesDir(null), "u1-$surface-full.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { image.recycle() }
    }
    if (!compact && !startsFull) {
        handle.performTouchInput { swipe(center, center + Offset(0f, travel), 350) }
        checkAnchor(full = false)
    }
}
