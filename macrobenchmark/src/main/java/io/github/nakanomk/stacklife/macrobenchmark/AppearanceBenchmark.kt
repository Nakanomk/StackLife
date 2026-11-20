package io.github.nakanomk.stacklife.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppearanceBenchmark(private val glass: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "glass={0}") fun modes() = listOf(false, true)
        private const val TARGET = "io.github.nakanomk.stacklife.bench"
    }
    @get:Rule val rule = MacrobenchmarkRule()

    private fun UiDevice.await(selector: BySelector) = requireNotNull(wait(Until.findObject(selector), 8_000)) { "界面没有出现：$selector" }
    private fun measure(action: UiDevice.() -> Unit) {
        var seeded = false
        rule.measureRepeated(packageName = TARGET, metrics = listOf(FrameTimingMetric()), compilationMode = CompilationMode.Full(), iterations = 5,
            setupBlock = {
                if (!seeded) {
                    device.executeShellCommand("am start -W -n $TARGET/io.github.nakanomk.stacklife.benchmark.FixtureActivity --ez glass $glass")
                    device.await(By.text("fixture:30:5:$glass")); device.pressBack()
                    seeded = true
                }
                killProcess(); pressHome(); startActivityAndWait()
                device.await(By.res("queue")); device.await(By.textContains("基准任务 01"))
                val material = device.await(By.res("navigation-capsule")).accessibilityNodeInfo.stateDescription
                check(material == if (glass) "折射胶囊" else "实色胶囊") { "材质档不匹配：$material；不采集冒充玻璃的实色样本。" }
            }, measureBlock = { action(device) })
    }

    @Test fun scroll() = measure {
        val viewport = await(By.res("queue")).visibleBounds
        val x = viewport.left + viewport.width() / 3
        val high = viewport.top + viewport.height() / 5
        val low = viewport.top + viewport.height() * 2 / 3
        val first = By.textContains("基准任务 01")
        repeat(3) { swipe(x, low, x, high, 32) }
        assertTrue("滑动后队首仍在原位", wait(Until.gone(first), 3_000))
        repeat(4) { swipe(x, high, x, low, 32) }
        await(first)
    }
    @Test fun cardRoundTrip() = measure {
        await(By.textContains("基准任务 01")).click(); await(By.text("任务详情")); pressBack(); await(By.res("queue"))
    }
    @Test fun assistantPanel() = measure {
        await(By.res("ask-planner")).click(); await(By.text("关闭")).click()
        check(wait(Until.gone(By.text("关闭")), 6_000)); await(By.res("queue"))
    }
}
