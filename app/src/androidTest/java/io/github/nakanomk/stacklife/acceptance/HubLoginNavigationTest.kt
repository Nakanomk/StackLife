package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.courses.*
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

private const val provider = "https://identity.example/basicInformation/scheduleInformation/index"
private const val asset = "https://static.identity.example/verify.js"
private const val challenge = "https://cross-site.example.org/challenge"
private const val localRows = """<script>document.body.__vue__={weekTableData:[{ZC:1,MONDAY:[{KCMC:'合成课程',JC:'1'}]}]}</script>"""
private const val providerRoute = "/basicInformation/scheduleInformation/index?state=local"

private class LocalProvider : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    private val listener = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: SocketException) { if (server.isClosed) break else throw e }
            thread(isDaemon = true) {
                socket.use {
                    try {
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = reader.readLine()?.substringAfter(' ')?.substringBefore(' ') ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val (status, html) = when (path) {
                            "/redirect" -> "302 Found\r\nLocation: $base$providerRoute" to ""
                            providerRoute -> "200 OK" to "<title>Local HTTP</title><a href='$provider'>Continue</a>"
                            "/popup" -> "200 OK" to """<meta name="viewport" content="width=device-width"><title>Local popup</title>
                                <button style="position:fixed;inset:0;width:100%;height:100%" onclick="
                                window.opener.postMessage('local-return','https://hubs.hust.edu.cn');window.close()">Return</button>"""
                            else -> "404 Not Found" to ""
                        }
                        val bytes = html.toByteArray()
                        val stream = socket.getOutputStream()
                        stream.write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        stream.write(bytes)
                    } catch (_: SocketTimeoutException) { } catch (_: SocketException) { }
                }
            }
        }
    }
    override fun close() { server.close(); listener.join(2_000) }
}

internal fun WebView.serveTestPages(response: (WebResourceRequest) -> WebResourceResponse?) {
    val client = webViewClient
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = client.shouldOverrideUrlLoading(v, r)
        override fun onPageStarted(v: WebView, url: String?, icon: Bitmap?) = client.onPageStarted(v, url, icon)
        override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) = client.doUpdateVisitedHistory(v, url, reload)
        override fun onPageFinished(v: WebView, url: String?) = client.onPageFinished(v, url)
        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) = client.onReceivedError(v, r, e)
        override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) = client.onReceivedHttpError(v, r, e)
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest) =
            client.shouldInterceptRequest(v, r) ?: response(r)
    }
}

// All pages, scripts, cookies and timetable rows in these tests are local fixtures, not an authenticated HUB session.
@RunWith(AndroidJUnit4::class)
class HubLoginNavigationTest {
    @Test fun followsCleartextRedirectAndCrossSiteSignInButExtractsOnlyOnOfficialSchedule() {
        LocalProvider().use { server ->
            withBrowser("<script>location.replace('${server.base}/redirect')</script>", "<body>$localRows</body>") { scenario, session ->
                awaitPage(scenario, "cleartext provider redirect") {
                    session.view.url == "${server.base}$providerRoute" && session.view.title == "Local HTTP"
                }
                scenario.onActivity {
                    assertEquals(server.base.removePrefix("http://"), session.host)
                    assertFalse(session.canExtract)
                    session.view.evaluateJavascript("document.links[0].click()", null)
                }
                awaitPage(scenario, "cross-site script and sign-in cookie") {
                    session.host == "identity.example" && session.view.title == "Local verification complete"
                }
                assertThrows(SourceProblem::class.java) { runBlocking(Dispatchers.Main) { session.extract() } }
                scenario.onActivity { session.view.evaluateJavascript("document.links[0].click()", null) }
                awaitPage(scenario, "return to official timetable route") { session.canExtract }
                scenario.onActivity { assertEquals("hubs.hust.edu.cn", session.host) }
                val course = runBlocking(Dispatchers.Main) { session.extract() }.single().jsonObject
                    .getValue("MONDAY").jsonArray.single().jsonObject
                assertEquals("合成课程", course.getValue("KCMC").jsonPrimitive.content)
            }
        }
    }

    @Test fun userOpenedWindowOwnsExtractionStateAndReturnsToItsOpenerOnCloseOrBack() {
        LocalProvider().use { server ->
            val schedule = """<meta name="viewport" content="width=device-width"><body>$localRows<script>
                onmessage = e => { if (e.origin === '${server.base}' && e.data === 'local-return') document.title='Local return'; };
                </script><a href="${server.base}/popup" target="_blank" rel="opener" style="position:fixed;inset:0">Sign in</a></body>"""
            withBrowser("<script>location.replace('$HUB_URL')</script>", schedule) { scenario, session ->
                awaitPage(scenario, "fixture timetable") { session.canExtract && session.view.isShown && session.view.height > 0 }
                lateinit var opener: WebView
                scenario.onActivity { opener = session.view }
                fun openPopup() {
                    tapPage(scenario, session)
                    awaitPage(scenario, "target=_blank authentication page") {
                        session.view !== opener && session.view.isShown && session.view.title == "Local popup"
                    }
                    scenario.onActivity {
                        assertFalse(opener.isShown)
                        assertEquals(server.base.removePrefix("http://"), session.host)
                        assertFalse(session.canExtract)
                    }
                    assertThrows(SourceProblem::class.java) { runBlocking(Dispatchers.Main) { session.extract() } }
                }
                openPopup()
                tapPage(scenario, session)
                awaitPage(scenario, "window.close and opener message") {
                    session.view === opener && opener.isShown && opener.title == "Local return" && session.canExtract
                }
                openPopup()
                scenario.onActivity { assertTrue(session.back()) }
                awaitPage(scenario, "browser Back restores opener") { session.view === opener && opener.isShown && session.canExtract }
            }
        }
    }

    private fun withBrowser(login: String, schedule: String, block: (ActivityScenario<ComponentActivity>, HubSession) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var session: HubSession
            scenario.onActivity { activity ->
                session = HubSession(activity)
                session.view.serveTestPages { r ->
                    fun reply(type: String, text: String) = WebResourceResponse(type, "UTF-8", text.byteInputStream())
                    when (r.url.toString()) {
                        HUB_LOGIN_URL -> reply("text/html", login)
                        HUB_URL -> reply("text/html", schedule)
                        provider -> reply("text/html", "<script src='$asset'></script><iframe src='$challenge'></iframe><a href='$HUB_URL'>Return</a>")
                        asset -> reply("application/javascript", """onmessage = e => {
                            if (e.origin === 'https://cross-site.example.org' && e.data === 'local-cookie-ok') document.title='Local verification complete';
                        }""")
                        challenge -> reply("text/html", """<script>
                            document.cookie='local_challenge=ok; SameSite=None; Secure; Path=/';
                            if (document.cookie.split('; ').includes('local_challenge=ok')) parent.postMessage('local-cookie-ok','https://identity.example');
                        </script>""")
                        else -> if (r.url.host == "127.0.0.1") null else WebResourceResponse("text/plain", "UTF-8", 404,
                            "Not Found", emptyMap(), "".byteInputStream())
                    }
                }
                activity.setContentView(session.container)
                session.start()
            }
            try { block(scenario, session) } finally {
                val cleared = CountDownLatch(1)
                scenario.onActivity { session.close { cleared.countDown() } }
                assertTrue(cleared.await(8, TimeUnit.SECONDS))
            }
        }
    }

    private fun awaitPage(scenario: ActivityScenario<ComponentActivity>, description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var reached = false
            scenario.onActivity { reached = ready() }
            if (reached) return
            SystemClock.sleep(25)
        }
        fail("Did not reach $description")
    }

    private fun tapPage(scenario: ActivityScenario<ComponentActivity>, session: HubSession) {
        val point = IntArray(2)
        val rendered = CountDownLatch(1)
        scenario.onActivity {
            session.view.getLocationOnScreen(point)
            point[0] += session.view.width / 2
            point[1] += session.view.height / 2
            session.view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) = rendered.countDown()
            })
        }
        assertTrue("Page was not ready for touch input", rendered.await(5, TimeUnit.SECONDS))
        val input = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0].toFloat(), point[1].toFloat(), 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; input.sendPointerSync(event) } finally { event.recycle() }
        }
    }
}
