package io.github.nakanomk.stacklife.acceptance

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.courses.HUB_URL
import io.github.nakanomk.stacklife.courses.HubSession
import io.github.nakanomk.stacklife.courses.SourceProblem
import io.github.nakanomk.stacklife.courses.decodeHubReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local fixture only: Chromium executes the shipped script with network loads disabled. This is not HUB login evidence. */
@RunWith(AndroidJUnit4::class)
class HubProjectionTest {
    @Test fun openingAndLeavingClearPersistentSessionDataEvenWithoutNetwork() {
        val fixture = "https://stacklife-wipe-fixture.invalid/"
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var session: HubSession
            scenario.onActivity { activity ->
                session = HubSession(activity).also { it.view.settings.blockNetworkLoads = true }
            }
            val cookies = CookieManager.getInstance()
            fun hasMarker() = cookies.getCookie(fixture)?.contains("test_login_marker=1") == true
            fun mark() {
                val stored = CountDownLatch(1)
                scenario.onActivity {
                    cookies.setCookie(fixture, "test_login_marker=1; Max-Age=3600; Secure; HttpOnly; Path=/") { stored.countDown() }
                }
                assertTrue(stored.await(5, TimeUnit.SECONDS))
                assertTrue(hasMarker())
            }
            try {
                mark()
                scenario.onActivity { session.start() }
                runBlocking { withTimeout(8_000) { while (hasMarker()) delay(25) } }
                mark()
            } finally {
                val cleared = CountDownLatch(1)
                scenario.onActivity { session.close { cleared.countDown() } }
                assertTrue(cleared.await(8, TimeUnit.SECONDS))
            }
            assertFalse(hasMarker())
        }
    }

    @Test fun projectionContainsOnlyTimetableFieldsAndRejectsChangedSemesterAndForeignOrigin() {
        val script = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("importers/hust-hub.js").bufferedReader().use { it.readText() }
        val source = """<html><body><div class='el-table'></div><script>
            const raw=[{ZC:'1',KS:'2026-09-07',extra:'omit',MONDAY:[{KCMC:'A',JC:'5–6',JSMC:'B',extra:'omit'}]}];
            document.querySelector('.el-table').__vue__={${'$'}parent:{${'$'}data:{weekTableData:raw}}};
        </script></body></html>"""
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity -> view = WebView(activity).apply {
                settings.blockNetworkLoads = true; settings.javaScriptEnabled = true; activity.setContentView(this)
            } }
            fun load(origin: String) {
                val done = CountDownLatch(1)
                scenario.onActivity { view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String?) { if (url == origin) done.countDown() }
                }; view.loadDataWithBaseURL(origin, source, "text/html", "UTF-8", null) }
                assertTrue("Local test page did not finish loading", done.await(10, TimeUnit.SECONDS))
            }
            fun eval(js: String): String {
                val done = CountDownLatch(1); var answer = ""
                scenario.onActivity { view.evaluateJavascript(js) { answer = it; done.countDown() } }
                assertTrue(done.await(8, TimeUnit.SECONDS)); return answer
            }
            try {
                load(HUB_URL)
                val rows = decodeHubReply(eval("$script('a',false)")); val week = rows.single().jsonObject
                assertEquals(setOf("ZC", "KS", "MONDAY"), week.keys)
                assertEquals(setOf("KCMC", "JC", "JSMC"), week.getValue("MONDAY").jsonArray.single().jsonObject.keys)
                assertEquals("true", eval("$script('a',true)"))
                decodeHubReply(eval("$script('b',false)")); eval("raw[0].MONDAY[0].JSMC='C'")
                assertEquals("false", eval("$script('b',true)"))
                load("https://example.invalid/basicInformation/scheduleInformation/index")
                val denied = eval("$script('c',false)")
                assertThrows(SourceProblem::class.java) { decodeHubReply(denied) }
            } finally { scenario.onActivity { it.setContentView(android.widget.FrameLayout(it)); view.destroy() } }
        }
    }
}
