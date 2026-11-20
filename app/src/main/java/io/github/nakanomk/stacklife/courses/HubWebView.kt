package io.github.nakanomk.stacklife.courses

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray

/** Only the importer uses WebView. Serialize wipes across closing, rotation and the next login. Main thread. */
private object HubSiteData {
    private val queue = ArrayDeque<() -> Unit>()
    fun wipe(after: () -> Unit) {
        queue.addLast(after)
        if (queue.size == 1) start()
    }
    private fun start() {
        WebStorage.getInstance().deleteAllData()
        GeolocationPermissions.getInstance().clearAll()
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            // Keep our entry queued during the callback, which can itself enqueue a closing wipe.
            try { queue.first().invoke() } finally {
                queue.removeFirst()
                if (queue.isNotEmpty()) start()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class HubSession(context: Context) {
    val view = WebView(context)
    var host by mutableStateOf<String?>(null)
        private set
    var canExtract by mutableStateOf(false)
        private set
    var message by mutableStateOf("正在清理上次登录…")
    var closed by mutableStateOf(false)
        private set
    private var generation = 0L
    private var unhealthy = false
    private var rendererGone = false

    init {
        // Modern WebView enables remote inspection automatically in a debuggable APK. This view handles school login.
        WebView.setWebContentsDebuggingEnabled(false)
        view.apply {
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = false
                allowFileAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                @Suppress("DEPRECATION")
                saveFormData = false
                @Suppress("DEPRECATION")
                savePassword = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (closed) return true
                    if (loginAuthority(r.url.toString()) != null) {
                        if (r.isForMainFrame) { generation++; canExtract = false }
                        return false
                    }
                    // The live HUB returns Location: http://hubs.hust.edu.cn/login when signed out.
                    // Cancel that request; start a new GET to the fixed HTTPS login, never replay a body or URL parameters.
                    if (r.isForMainFrame && r.isRedirect && r.method == "GET" && r.url.toString() == "http://hubs.hust.edu.cn/login") {
                        canExtract = false
                        generation++
                        v.loadUrl(HUB_LOGIN_URL)
                        return true
                    }
                    if (r.isForMainFrame) failPage("已阻止非 HTTPS 或地址无效的跳转；可返回总课表或改用文件")
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (closed) return
                    generation++
                    canExtract = false
                    host = loginAuthority(url)
                    unhealthy = host == null
                    if (unhealthy) {
                        stopLoading()
                        message = "无法打开此地址，仅支持 HTTPS 网页"
                    } else message = "正在加载网页…"
                }
                override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                    if (closed) return
                    generation++
                    host = loginAuthority(url)
                    canExtract = !unhealthy && v.progress == 100 && isScheduleRoute(url)
                }
                override fun onPageFinished(v: WebView, url: String?) {
                    if (closed || unhealthy || url != v.url) return
                    host = loginAuthority(url) ?: return
                    canExtract = isScheduleRoute(url)
                    message = if (canExtract) "选定学期并等表格加载完成，再提取。" else "登录可能跳转到第三方。输入信息前请核对上方网站；仅 HUB 总课表可提取。"
                }
                override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                    h.cancel()
                    failPage("网站证书验证失败，已拒绝连接")
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, error: WebResourceError) {
                    if (r.isForMainFrame) failPage("未能加载网页，请检查网络或改用文件")
                }
                override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, error: WebResourceResponse) {
                    if (r.isForMainFrame) failPage("网页返回 HTTP ${error.statusCode}，请改用文件或稍后重试")
                }
                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone = true
                    failPage("网页进程已停止，请重新开始或改用文件")
                    close {}
                    return true
                }
            }
        }
    }

    private fun failPage(reason: String) {
        generation++
        unhealthy = true
        canExtract = false
        message = reason
    }
    private fun clearView() {
        view.clearCache(true)
        view.clearHistory()
        view.clearFormData()
        val db = WebViewDatabase.getInstance(view.context)
        db.clearHttpAuthUsernamePassword()
        @Suppress("DEPRECATION")
        db.clearFormData()
    }
    fun start() {
        clearView()
        // Sessions start clean. Enter through the published HTTPS sign-in rather than a downgraded redirect.
        HubSiteData.wipe { if (!closed) view.loadUrl(HUB_LOGIN_URL) }
    }
    fun openSchedule() {
        if (!closed) view.loadUrl(HUB_URL)
    }
    fun close(after: () -> Unit) {
        if (!closed) {
            closed = true
            generation++
            if (!rendererGone) {
                view.stopLoading()
                view.settings.javaScriptEnabled = false
                view.settings.domStorageEnabled = false
                clearView()
            }
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        HubSiteData.wipe(after)
    }
    suspend fun extract(): JsonArray {
        val epoch = generation
        val url = if (closed) null else view.url
        fun checkPage() {
            if (closed || !canExtract || epoch != generation || view.url != url || !isScheduleRoute(url))
                throw SourceProblem("页面或学期已改变，请在总课表重新提取")
        }
        checkPage()
        val script = withContext(Dispatchers.IO) { view.context.assets.open("importers/hust-hub.js").bufferedReader().use { it.readText() } }
        val token = UUID.randomUUID().toString()
        suspend fun evaluate(verify: Boolean): String? = suspendCancellableCoroutine { reply ->
            view.evaluateJavascript("$script('$token',$verify)") { result -> if (reply.isActive) reply.resume(result) }
        }
        return withTimeout(12000) {
            checkPage()
            val reply = evaluate(false)
            val rows = withContext(Dispatchers.Default) { decodeHubReply(reply) }
            checkPage()
            if (evaluate(true) != "true") throw SourceProblem("页面或学期已改变，请等待加载完成后重新提取")
            checkPage()
            rows
        }
    }
}

@Composable
internal fun HubWebView(onRows: (JsonArray) -> Unit, onBack: () -> Unit, onFile: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(context) { try { HubSession(context) } catch (_: RuntimeException) { null } }
    var attached by remember { mutableStateOf(true) }
    var leaving by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    DisposableEffect(session) {
        attached = true
        session?.start()
        onDispose { attached = false; if (session != null && !session.closed) session.close {} }
    }
    fun leave(after: () -> Unit) {
        if (leaving) return
        leaving = true
        if (session == null) after() else session.close { if (attached) after() }
    }
    BackHandler { leave(onBack) }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("当前网站：${session?.host ?: "未连接"}", style = MaterialTheme.typography.titleMedium)
        Text(if (leaving) "正在清理本次登录…" else session?.message ?: "设备 WebView 不可用，请改用原始文件")
        if (session != null && !session.closed) AndroidView(factory = { session.view }, modifier = Modifier.fillMaxWidth().weight(1f))
        else Spacer(Modifier.weight(1f))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = session?.canExtract == true && !extracting && !leaving && !session.closed, onClick = {
                if (session == null || extracting || leaving) return@Button
                extracting = true
                scope.launch {
                    try {
                        val data = session.extract()
                        if (!leaving) leave { onRows(data) }
                    } catch (_: TimeoutCancellationException) { session.message = "提取超时，请等待总课表加载或改用文件" }
                    catch (e: SourceProblem) { session.message = e.message.orEmpty() }
                    catch (_: IOException) { session.message = "提取不可用，请改用文件" }
                    finally { extracting = false }
                }
            }, modifier = Modifier.testTag("hubExtract")) { Text(if (extracting) "提取中" else "提取课表") }
            OutlinedButton(enabled = !leaving, onClick = { leave(onFile) }, modifier = Modifier.testTag("hubUseFile")) { Text("改用文件") }
            if (session?.host != null && !session.canExtract) TextButton(enabled = !leaving && !session.closed,
                onClick = session::openSchedule) { Text("打开总课表") }
            TextButton(enabled = !leaving, onClick = { leave(onBack) }) { Text("退出登录页") }
        }
    }
}
