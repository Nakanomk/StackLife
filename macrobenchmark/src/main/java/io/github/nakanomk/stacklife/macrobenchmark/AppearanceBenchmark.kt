package io.github.nakanomk.stacklife.macrobenchmark

import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppearanceBenchmark(private val glass: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "glass={0}") fun modes() = listOf(false, true)
        private const val TARGET = "io.github.nakanomk.stacklife.bench"
        private const val EDIT_SAMPLE = "0900 0930\n1015 1100\n1300 1345\n1500 1530"
        private val imeWindow = By.Window.type(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        private val firstTask: AccessibilityNodeInfo.() -> Boolean = { text?.contains("基准任务 01") == true }
    }
    @get:Rule val rule = MacrobenchmarkRule()
    private lateinit var targetResources: Resources
    private fun appString(name: String) = targetResources.getString(targetResources.getIdentifier(name, "string", TARGET))
    private val automation get() = InstrumentationRegistry.getInstrumentation().getUiAutomation(Configurator.getInstance().uiAutomationFlags)
    private val oldIdleTimeout = Configurator.getInstance().waitForIdleTimeout
    @Before fun limitIdleWait() { Configurator.getInstance().setWaitForIdleTimeout(250) }
    @After fun restoreIdleWait() { Configurator.getInstance().setWaitForIdleTimeout(oldIdleTimeout) }

    // UiDevice.findObject scans every display/window root; UiObject2 construction also asks
    // the window manager for each hit's window. Only the foreground .bench tree is relevant.
    private fun activeRoot() = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == TARGET }
    private fun AccessibilityNodeInfo.find(match: AccessibilityNodeInfo.() -> Boolean): AccessibilityNodeInfo? {
        if (isVisibleToUser && match()) return this
        for (index in 0 until childCount) getChild(index)?.find(match)?.let { return it }
        return null
    }
    private fun UiDevice.await(label: String, match: AccessibilityNodeInfo.() -> Boolean): AccessibilityNodeInfo =
        checkNotNull(wait({ activeRoot()?.find(match) }, 8_000)) { "Missing $label in the foreground $TARGET window" }
    private fun UiDevice.tag(id: String, match: AccessibilityNodeInfo.() -> Boolean = { true }) =
        await(id) { viewIdResourceName == id && match() }
    private fun UiDevice.awaitGone(label: String, timeout: Long, match: AccessibilityNodeInfo.() -> Boolean) {
        check(wait({ activeRoot()?.let { it.find(match) == null } == true }, timeout)) { "$label still visible or $TARGET not active" }
    }
    private val AccessibilityNodeInfo.bounds get() = Rect().also(::getBoundsInScreen)
    private fun UiDevice.tap(node: AccessibilityNodeInfo) = node.bounds.let {
        check(!it.isEmpty && click(it.centerX(), it.centerY())) { "Could not tap ${node.viewIdResourceName} at $it" }
    }
    private fun AccessibilityNodeInfo.replaceText(value: String) {
        check(performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Text action rejected by $viewIdResourceName" }
    }
    private fun seedFixture(): Locale {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val receipt = resolver.call("$TARGET.fixture", "seed", glass.toString(), null)?.getString("receipt")
        val locale = checkNotNull(Regex("""30:5:$glass:([A-Za-z0-9-]+)""").matchEntire(receipt.orEmpty())) {
            "Invalid .bench fixture receipt (expected 30/5/$glass): $receipt"
        }.groupValues[1]
        return Locale.forLanguageTag(locale)
    }
    private fun UiDevice.awaitIme(visible: Boolean) {
        check(wait({ device -> device.hasWindow(imeWindow) == visible }, 8_000)) { "Expected IME window visible=$visible" }
    }

    private fun UiDevice.openKeyboard() {
        tap(tag("assistant-input"))
        tag("assistant-input") { isFocused }
        awaitIme(true)
        awaitGone("root navigation", 8_000) { viewIdResourceName == "navigation-capsule" }
    }

    private fun UiDevice.closeKeyboard() {
        pressBack()
        awaitIme(false)
        tag("navigation-capsule")
        tag("queue")
    }

    private fun measure(assistant: Boolean = false, restart: Boolean = false, reset: UiDevice.() -> Unit = {},
        prepare: UiDevice.() -> Unit = {}, action: UiDevice.() -> Unit) {
        var seeded = false
        rule.measureRepeated(packageName = TARGET, metrics = listOf(FrameTimingMetric()), compilationMode = CompilationMode.Full(), iterations = 5,
            setupBlock = {
                val launch = !seeded || restart
                if (launch) killProcess()
                if (!seeded) {
                    val locale = seedFixture()
                    val target = InstrumentationRegistry.getInstrumentation().context.createPackageContext(TARGET, 0)
                    targetResources = target.createConfigurationContext(Configuration().apply {
                        setLocale(locale)
                    }).resources
                    seeded = true
                }
                if (launch) {
                    startActivityAndWait(Intent().setClassName(TARGET, "io.github.nakanomk.stacklife.MainActivity"))
                } else {
                    device.reset()
                    // A measured edit leaves the IME up; reset on the same activity outside the trace.
                    if (device.hasWindow(imeWindow)) device.closeKeyboard()
                }
                device.tag("queue"); device.await("first task", firstTask)
                val material = device.tag("navigation-capsule").stateDescription
                val expected = appString(if (glass) "ui_glass_capsule" else "ui_solid_capsule")
                check(material?.toString() == expected) { "材质档不匹配：$material；不采集冒充玻璃的实色样本。" }
                if (assistant) {
                    val input = device.tag("assistant-input")
                    check(generateSequence(input) { it.parent }.any { it.stateDescription?.toString() == expected }) {
                        "Assistant composer is not using the requested material: $expected"
                    }
                }
                device.prepare()
            }, measureBlock = { action(device) })
    }

    @Test fun scroll() = measure(restart = true) {
        val viewport = tag("queue").bounds
        val x = viewport.left + viewport.width() / 3
        val high = viewport.top + viewport.height() / 5
        val low = viewport.top + viewport.height() * 2 / 3
        repeat(3) { swipe(x, low, x, high, 32) }
        awaitGone("first task", 3_000, firstTask)
        repeat(4) { swipe(x, high, x, low, 32) }
        await("first task", firstTask)
    }
    private fun UiDevice.openCard() {
        tap(await("first task", firstTask))
        val title = appString("ui_task_details")
        await(title) { text?.toString() == title }
        automation.waitForIdle(500, 5_000)
    }
    private fun UiDevice.returnCard() {
        pressBack()
        tag("queue"); await("first task", firstTask)
        automation.waitForIdle(500, 5_000)
    }
    @Test fun cardOpen() = measure(reset = { returnCard() }) { openCard() }
    @Test fun cardReturn() = measure(prepare = { openCard() }) { returnCard() }

    private fun UiDevice.openSettings() {
        val name = appString("ui_settings")
        val icon = await(name) { contentDescription?.toString() == name }
        tap(generateSequence(icon) { it.parent }.first { it.isClickable && it.isEnabled })
        tag("route:settings"); tag("routine-wake")
        automation.waitForIdle(500, 5_000)
    }
    private fun UiDevice.leaveSettings() {
        pressBack()
        tag("queue"); await("first task", firstTask)
        awaitGone("settings", 5_000) { viewIdResourceName == "route:settings" }
        automation.waitForIdle(500, 5_000)
    }
    @Test fun settingsOpen() = measure(reset = { leaveSettings() }) { openSettings() }
    @Test fun settingsBack() = measure(prepare = { openSettings() }) { leaveSettings() }

    private fun UiDevice.selectPage(key: String, contentTag: String) {
        tap(tag("nav:$key") { isClickable && isEnabled && !isSelected })
        tag(contentTag)
        automation.waitForIdle(500, 5_000)
        tag("nav:$key") { isSelected }
    }

    @Test fun workspaceToToday() = measure(reset = { selectPage("workspace", "queue") }) {
        selectPage("today", "day-date")
    }
    @Test fun todayToCourses() = measure(reset = { selectPage("workspace", "queue") },
        prepare = { selectPage("today", "day-date") }) {
        selectPage("courses", "course-week")
    }
    @Test fun coursesToWorkspace() = measure(prepare = { selectPage("courses", "course-week") }) {
        selectPage("workspace", "queue")
    }

    @Test fun assistantKeyboard() = measure(assistant = true) {
        openKeyboard()
        closeKeyboard()
    }

    @Test fun assistantEdit() {
        var oneLineHeight = 0
        val keyboard = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        measure(assistant = true, prepare = {
            tag("assistant-input").replaceText("")
            tag("assistant-send") { !isEnabled }
            openKeyboard()
            val input = tag("assistant-input") { isFocused }
            var last = input.bounds
            var since = SystemClock.uptimeMillis()
            check(wait({
                check(input.refresh()) { "Focused editor disappeared" }
                val now = SystemClock.uptimeMillis()
                val current = input.bounds
                if (current != last) { last = current; since = now }
                input.isFocused && now - since >= 350
            }, 5_000)) { "Keyboard did not settle before editing" }
            oneLineHeight = last.height()
        }) {
            val injector = automation
            // Real down/up edits at a fixed cadence; digits and plain Enter do not require a particular IME layout.
            for (ch in EDIT_SAMPLE) {
                for (event in checkNotNull(keyboard.getEvents(charArrayOf(ch)))) {
                    check(injector.injectInputEvent(event, true))
                }
                SystemClock.sleep(50)
            }
            val input = tag("assistant-input") { isFocused && text?.toString() == EDIT_SAMPLE }
            check(input.bounds.height() > oneLineHeight) { "Editor did not grow across lines" }
            tag("assistant-send") { isEnabled }
            awaitIme(true)
        }
    }

    private fun UiDevice.preparePanel() {
        tag("assistant-input").replaceText("09:00 09:30")
        tag("assistant-send") { isEnabled }
        awaitIme(false)
    }
    private fun UiDevice.openPanel() {
        val offline = appString("assistant_not_connected")
        tap(tag("assistant-send") { isEnabled })
        await(offline) { text?.toString() == offline }
        automation.waitForIdle(500, 5_000)
    }
    private fun UiDevice.closePanel() {
        val offline = appString("assistant_not_connected")
        pressBack()
        awaitGone(offline, 6_000) { text?.toString() == offline }
        tag("assistant-input"); tag("assistant-send") { !isEnabled }
        tag("navigation-capsule"); tag("queue")
        automation.waitForIdle(500, 5_000)
    }

    @Test fun assistantPanelOpen() = measure(assistant = true, reset = { closePanel() }, prepare = { preparePanel() }) {
        openPanel()
    }
    @Test fun assistantPanelClose() = measure(assistant = true, prepare = { preparePanel(); openPanel() }) {
        closePanel()
    }
}
