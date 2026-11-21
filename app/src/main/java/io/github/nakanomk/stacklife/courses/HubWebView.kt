package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.i18n.uiText
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.widget.FrameLayout
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
            try { queue.first().invoke() } finally {
                queue.removeFirst()
                if (queue.isNotEmpty()) start()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class HubSession(private val context: Context) {
    private class Page(val web: WebView) {
        var host by mutableStateOf<String?>(null)
        var extractable by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var appLink by mutableStateOf<Uri?>(null)
        var generation = 0L
        var failed = false

        fun location(url: String?) {
            val uri = url?.let(Uri::parse)
            host = uri?.host?.let { if (uri.port == -1) it else "$it:${uri.port}" }
        }

        fun fail(reason: String) {
            generation++
            failed = true
            extractable = false
            error = reason
        }
    }

    val container = FrameLayout(context)
    private val windows = mutableListOf<Page>()
    private var current by mutableStateOf(newPage())
    val view: WebView get() = current.web
    val host get() = current.host
    val canExtract get() = !closed && current.extractable
    val hasAppLink get() = !closed && current.appLink != null
    var message: String?
        get() = current.error
        set(value) { current.error = value }
    var started by mutableStateOf(false)
        private set
    var closed by mutableStateOf(false)
        private set

    init {
        WebView.setWebContentsDebuggingEnabled(false)
        windows.add(current)
        container.addView(view)
    }

    private fun newPage(): Page {
        val web = WebView(context)
        val page = Page(web)
        web.isSaveEnabled = false
        web.isSaveFromParentEnabled = false
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowContentAccess = false
            allowFileAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(opener: WebView, dialog: Boolean, userGesture: Boolean, result: Message): Boolean {
                if (closed || page !== current || !userGesture) return false
                val transport = result.obj as? WebView.WebViewTransport ?: return false
                val child = newPage()
                page.generation++
                opener.visibility = View.GONE
                windows.add(child)
                current = child
                container.addView(child.web)
                child.web.requestFocus()
                transport.webView = child.web
                result.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) = closePopup(page)
            override fun onConsoleMessage(message: ConsoleMessage) = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (closed) return true
                when (r.url.scheme?.lowercase()) {
                    "http", "https" -> {
                        if (r.isForMainFrame) { page.generation++; page.extractable = false }
                        return false
                    }
                    "about", "blob", "data", "javascript" -> return false
                }
                if (r.isForMainFrame && page === current) {
                    page.appLink = externalAppUri(r.url)
                    if (page.appLink == null) page.error = uiText("Cannot open this link.", "无法打开此链接")
                    else if (r.hasGesture()) openAppLink()
                }
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                page.generation++
                page.failed = false
                page.extractable = false
                page.error = null
                page.appLink = null
                page.location(url)
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                if (closed) return
                page.generation++
                page.location(url)
                page.extractable = !page.failed && v.progress == 100 && isScheduleRoute(url)
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (closed || url != v.url) return
                page.location(url)
                page.extractable = !page.failed && isScheduleRoute(url)
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, error: WebResourceError) {
                if (r.isForMainFrame) page.fail(when (error.errorCode) {
                    ERROR_HOST_LOOKUP, ERROR_CONNECT -> uiText("Cannot connect to the website.", "无法连接网站")
                    ERROR_TIMEOUT -> uiText("Connection timed out.", "连接超时")
                    ERROR_FAILED_SSL_HANDSHAKE -> uiText("Secure connection failed.", "安全连接失败")
                    else -> uiText("Page failed to load.", "网页加载失败")
                })
            }

            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, error: WebResourceResponse) {
                if (r.isForMainFrame) page.fail(uiText({ "The page returned HTTP ${error.statusCode}." }, { "网页返回 HTTP ${error.statusCode}" }))
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed && page in windows) {
                    current.fail(uiText("The web page stopped. Open it again.", "网页进程已停止，请重新打开"))
                    endSession(rendererGone = true)
                    HubSiteData.wipe {}
                }
                return true
            }
        }
        return page
    }

    private fun externalAppUri(link: Uri): Uri? {
        val uri = if (link.scheme.equals("intent", true)) {
            try { Intent.parseUri(link.toString(), Intent.URI_INTENT_SCHEME).data }
            catch (_: java.net.URISyntaxException) { null }
        } else link
        return when (uri?.scheme?.lowercase()) {
            null, "file", "content", "android.resource", "android-app", "intent", "javascript",
            "data", "blob", "filesystem", "about", "chrome", "chrome-native" -> null
            else -> uri
        }
    }

    fun openAppLink() {
        val link = current.appLink ?: return
        current.appLink = null
        val intent = Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE)
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { message = uiText("No app is installed to open this link.", "未安装可打开此链接的应用") }
        catch (_: SecurityException) { message = uiText("The system did not allow this link to open.", "系统未允许打开此链接") }
    }

    private fun closePopup(page: Page) {
        if (closed || windows.first() === page || !windows.remove(page)) return
        container.removeView(page.web)
        page.web.destroy()
        if (current === page) {
            current = windows.last()
            view.visibility = View.VISIBLE
            view.requestFocus()
        }
    }

    fun back(): Boolean {
        if (closed) return false
        if (view.canGoBack()) {
            current.generation++
            current.extractable = false
            view.goBack()
        } else if (windows.size > 1) closePopup(current)
        else return false
        return true
    }

    private fun clearStoredData() {
        view.clearCache(true)
        val db = WebViewDatabase.getInstance(context)
        db.clearHttpAuthUsernamePassword()
        @Suppress("DEPRECATION")
        db.clearFormData()
    }

    fun start() {
        clearStoredData()
        HubSiteData.wipe {
            if (!closed) {
                started = true
                view.loadUrl(HUB_LOGIN_URL)
            }
        }
    }

    fun openSchedule() {
        if (!started || closed) return
        current.generation++
        current.extractable = false
        view.loadUrl(HUB_URL)
    }

    private fun endSession(rendererGone: Boolean) {
        closed = true
        if (!rendererGone) clearStoredData()
        for (page in windows.asReversed()) {
            container.removeView(page.web)
            if (!rendererGone) {
                page.web.stopLoading()
                page.web.clearHistory()
                page.web.clearFormData()
            }
            page.web.destroy()
        }
        windows.clear()
    }

    fun close(after: () -> Unit) {
        if (!closed) endSession(rendererGone = false)
        HubSiteData.wipe(after)
    }
    suspend fun extract(): JsonArray {
        val page = current
        val epoch = page.generation
        val url = if (closed) null else page.web.url
        fun checkPage() {
            if (!canExtract || page !== current || epoch != page.generation || page.web.url != url || !isScheduleRoute(url))
                throw SourceProblem(uiText("The page or term changed. Extract again from the timetable.", "页面或学期已改变，请在总课表重新提取"))
        }
        checkPage()
        val script = withContext(Dispatchers.IO) { context.assets.open("importers/hust-hub.js").bufferedReader().use { it.readText() } }
        val token = UUID.randomUUID().toString()
        suspend fun evaluate(verify: Boolean): String? = suspendCancellableCoroutine { reply ->
            page.web.evaluateJavascript("$script('$token',$verify)") { result -> if (reply.isActive) reply.resume(result) }
        }
        return withTimeout(12000) {
            checkPage()
            val reply = evaluate(false)
            val rows = withContext(Dispatchers.Default) { decodeHubReply(reply) }
            checkPage()
            if (evaluate(true) != "true") throw SourceProblem(uiText("The page or term changed. Wait for it to load, then extract again.", "页面或学期已改变，请等待加载完成后重新提取"))
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
    fun back() {
        if (!leaving && session?.back() != true) leave(onBack)
    }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !leaving, onClick = ::back) { Text(stringResource(R.string.courses_back)) }
            Text(session?.host.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.StartEllipsis)
            TextButton(enabled = session?.started == true && !session.closed && !leaving,
                onClick = { session?.openSchedule() }) { Text(stringResource(R.string.courses_hub_timetable)) }
            Button(enabled = session?.canExtract == true && !extracting && !leaving, onClick = {
                if (session == null || extracting || leaving) return@Button
                extracting = true
                scope.launch {
                    try {
                        val data = session.extract()
                        if (!leaving) leave { onRows(data) }
                    } catch (_: TimeoutCancellationException) { session.message = uiText("Extraction timed out. Try again later.", "提取超时，请稍后重试") }
                    catch (e: SourceProblem) { session.message = e.message.orEmpty() }
                    catch (_: IOException) { session.message = uiText("Extraction failed.", "提取失败") }
                    finally { extracting = false }
                }
            }, modifier = Modifier.testTag("hubExtract")) { Text(if (extracting) stringResource(R.string.courses_extracting) else stringResource(R.string.courses_extract)) }
        }
        if (session?.hasAppLink == true) {
            TextButton(onClick = session::openAppLink) { Text(stringResource(R.string.courses_open_auth)) }
        }
        val problem = if (session == null) stringResource(R.string.courses_webview_unavailable) else session.message
        if (problem != null) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(problem, Modifier.weight(1f))
                TextButton(enabled = !leaving, onClick = { leave(onFile) }, modifier = Modifier.testTag("hubUseFile")) { Text(stringResource(R.string.courses_use_file)) }
            }
        }
        if (session != null && !session.closed) {
            AndroidView(factory = { session.container }, modifier = Modifier.fillMaxSize())
        }
    }
}
