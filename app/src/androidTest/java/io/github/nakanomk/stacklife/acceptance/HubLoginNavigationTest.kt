package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.courses.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubLoginNavigationTest {
    @Test fun redirectsThroughExternalProviderAndItsAssetsButExtractsOnlyAfterReturnToHub() {
        // All three pages and the provider's script are local responses. No credentials or network needed.
        val provider = "https://identity.example/basicInformation/scheduleInformation/index"
        val asset = "https://static.identity.example/verify.js"
        val atProvider = CountDownLatch(1)
        val returned = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var session: HubSession
            scenario.onActivity { activity ->
                session = HubSession(activity)
                val view = session.view
                val policy = view.webViewClient
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = policy.shouldOverrideUrlLoading(v, r)
                    override fun onPageStarted(v: WebView, url: String?, icon: Bitmap?) = policy.onPageStarted(v, url, icon)
                    override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) = policy.doUpdateVisitedHistory(v, url, reload)
                    override fun onPageFinished(v: WebView, url: String?) {
                        policy.onPageFinished(v, url)
                        if (url == provider) atProvider.countDown()
                        if (url == HUB_URL) returned.countDown()
                    }
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                        policy.shouldInterceptRequest(v, r)?.let { return it }
                        fun reply(type: String, text: String) = WebResourceResponse(type, "UTF-8", text.byteInputStream())
                        return when (r.url.toString()) {
                            HUB_LOGIN_URL -> reply("text/html", "<script>location.replace('$provider')</script>")
                            provider -> reply("text/html", "<script src='$asset'></script><a href='$HUB_URL'>返回总课表</a>")
                            asset -> reply("application/javascript", "document.title='验证资源已加载'")
                            HUB_URL -> reply("text/html", "<body><script>document.body.__vue__={weekTableData:[{ZC:1,MONDAY:[{KCMC:'合成课程',JC:'1'}]}]}</script></body>")
                            else -> WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                        }
                    }
                }
                activity.setContentView(view)
                session.start()
            }
            try {
                assertTrue("Login did not reach the external provider", atProvider.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals("identity.example", session.host)
                    assertEquals("验证资源已加载", session.view.title)
                    assertFalse(session.canExtract)
                }
                assertThrows(SourceProblem::class.java) { runBlocking(Dispatchers.Main) { session.extract() } }
                scenario.onActivity { session.view.evaluateJavascript("document.links[0].click()", null) }
                assertTrue("Provider did not return to HUB", returned.await(10, TimeUnit.SECONDS))
                scenario.onActivity { assertEquals("hubs.hust.edu.cn", session.host); assertTrue(session.canExtract) }
                val rows = runBlocking(Dispatchers.Main) { session.extract() }
                val course = rows.single().jsonObject.getValue("MONDAY").jsonArray.single().jsonObject
                assertEquals("合成课程", course.getValue("KCMC").jsonPrimitive.content)
            } finally {
                val cleared = CountDownLatch(1)
                scenario.onActivity { session.close { cleared.countDown() } }
                assertTrue(cleared.await(8, TimeUnit.SECONDS))
            }
        }
    }
}
