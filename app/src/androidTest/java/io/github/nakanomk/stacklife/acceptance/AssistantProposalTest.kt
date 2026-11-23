package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.courses.displayLabel
import io.github.nakanomk.stacklife.planner.*
import java.io.File
import java.net.InetAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantProposalTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private val server = MockWebServer()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val connection = ConnectionStore(inst.targetContext, "proposal-${UUID.randomUUID()}")
    private val imageDirectory = "proposal-images-${UUID.randomUUID()}"
    private val images by lazy { AttachmentStore(inst.targetContext, { store.db.conversationHistory().imageIds().toSet() }, imageDirectory) }
    private lateinit var transport: ChatTransport
    private lateinit var assistant: AssistantModel
    private var call = 0

    @Before fun setup(): Unit = runBlocking {
        val local = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(local.certificate).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(local).build().sslSocketFactory())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        transport = OpenAiTransport(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
        connection.save("https://127.0.0.1:${server.port}/acceptance/v1", "本地验收模型", "only-this-fixture")
        store.apply(PlannerCommand.TaskAdd(TaskInput("眼前的工作", notes = "不可外发的测试备注")))
        attach()
    }

    @After fun teardown() {
        server.close()
        runBlocking { connection.clear() }
        File(inst.targetContext.noBackupFilesDir, imageDirectory).deleteRecursively()
    }
    private fun attach(file: File? = null, photos: (() -> List<android.net.Uri>)? = null) = ui.activityRule.scenario.onActivity { activity ->
        // Match MainActivity, not the generic instrumentation activity's default pan window.
        activity.enableEdgeToEdge()
        activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val owner = photos?.let(::photoPickerReturning) ?: file?.let(::pickerReturning) ?: activity
        activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            val controller = viewModel { AssistantModel(store.api, connection, transport, store.db.conversationHistory(), images) }
            assistant = controller
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, controller)
        } }
    }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val state = assistant.state.value
            throw AssertionError("Missing $matcher; page=${state.page?.javaClass?.simpleName}, work=${state.work}, " +
                "events=${state.lines.mapNotNull { it.event?.status }}, requests=${server.requestCount}", timeout)
        }
        return ui.onNode(matcher)
    }
    private fun tag(value: String) = await(hasTestTag(value))
    private fun checkReplyFootprint(prefix: String) {
        val field = tag("$prefix-input")
        val shell = tag("$prefix-composer")
        field.performClick()
        val one = shell.fetchSemanticsNode().size
        val id = field.fetchSemanticsNode().id
        field.performTextReplacement("文")
        assertEquals(one, shell.fetchSemanticsNode().size)
        field.performTextClearance()
        assertEquals(one, shell.fetchSemanticsNode().size)
        field.performTextReplacement("一\n二\n三\n四\n五")
        val five = shell.fetchSemanticsNode().size
        assertTrue(five.height > one.height)
        val long = List(20) { "一" }.joinToString("\n")
        field.performTextReplacement(long)
        field.assertTextContains(long)
        assertEquals(id, field.fetchSemanticsNode().id)
        assertEquals(five, shell.fetchSemanticsNode().size)
        field.performTextClearance()
    }
    private fun send(text: String) {
        tag("assistant-input").performTextReplacement(text)
        await(hasTestTag("assistant-send") and isEnabled()).performClick()
    }
    private fun reply(text: String) {
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(text)}},"finish_reason":"stop"}]}"""))
    }
    private fun toolCall(name: String, args: String) {
        val fn = buildJsonObject { put("name", name); put("arguments", args) }
        val tc = buildJsonObject { put("id", "call-${++call}"); put("type", "function"); put("function", fn) }
        val reply = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[$tc]}}]}"""
        server.enqueue(MockResponse(body = reply))
    }
    private fun graphList() = runBlocking { (store.api.observe(PlannerQuery.Graphs).first() as GraphList).graphs }
    private fun capture(name: String, surface: SemanticsNodeInteraction? = null) {
        if (InstrumentationRegistry.getArguments().getString("captureM5") != "yes") return
        ui.waitForIdle()
        if (surface != null && android.os.Build.VERSION.SDK_INT >= 29) {
            val view = (surface.fetchSemanticsNode().root as ViewRootForTest).view
            val committed = java.util.concurrent.CountDownLatch(1)
            ui.runOnUiThread { view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; view.invalidate() }
            assertTrue("Dialog frame not submitted", committed.await(4, TimeUnit.SECONDS))
            inst.uiAutomation.waitForIdle(200, 5_000)
        }
        val bitmap = inst.uiAutomation.takeScreenshot() ?: throw AssertionError("device screenshot unavailable")
        try { File(inst.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun fromTodayOneReviewedBatchMakesGraphFrameRestoredTaskAndSlot() = runBlocking {
        val before = store.state()
        val prompt = "查资料、写提纲、交报告，生成栈帧，只恢复查资料并安排 16:00。"
        val input = tag("assistant-input")
        tag("assistant-send").assertIsNotEnabled()
        input.performClick().performTextInput(prompt)
        ui.onNodeWithTag("assistant-sheet").assertDoesNotExist()
        assertEquals(0, server.requestCount)
        toolCall("planner_query", """{"kind":"current"}"""); toolCall("planner_propose", composite)
        input.performImeAction()
        ui.runOnUiThread { assistant.send() }
        val apply = tag("assistant-apply"); val pending = requireNotNull(assistant.state.value.pending)
        val eventId = requireNotNull(assistant.state.value.eventId)
        val history = store.db.conversationHistory()
        val thread = history.observeConversations().first().single().id
        val proposed = history.observeMessages(thread).first().single { it.id == eventId }
        assertEquals(ChatEvent(ProposalStatus.PROPOSED, pending.changes), proposed.event)
        assertEquals(listOf(HistoryRole.USER, HistoryRole.NOTICE), assistant.state.value.lines.map { it.role })
        tag("assistant-notice").assertContentDescriptionEquals("警告与说明")
        ui.onNodeWithTag("assistant-notice-details").assertDoesNotExist()
        assertEquals(before, store.state()); assertTrue(graphList().isEmpty())
        val first = server.takeRequest(3, TimeUnit.SECONDS)!!; val second = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/acceptance/v1/chat/completions", first.target)
        val firstMessages = Json.parseToJsonElement(first.body!!.utf8()).jsonObject.getValue("messages").jsonArray
        assertEquals(1, firstMessages.count { it.jsonObject["content"]?.jsonPrimitive?.content == prompt })
        val outgoing = Json.parseToJsonElement(second.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        val data = outgoing.last().jsonObject
        assertEquals("tool", data["role"]!!.jsonPrimitive.content); assertTrue(data.toString().contains("眼前的工作"))
        assertFalse(outgoing.toString().contains("不可外发的测试备注")); assertFalse(outgoing.toString().contains("only-this-fixture"))
        val handle = tag("planner-sheet-handle")
        val expand = androidx.compose.ui.semantics.SemanticsActions.Expand
        if (handle.fetchSemanticsNode().config.contains(expand)) handle.performSemanticsAction(expand)
        apply.performScrollTo().assertIsDisplayed()
        capture("m5-proposal.png", apply)
        apply.performClick(); ui.runOnUiThread { assistant.apply(pending.proposalId) }
        val receipt = await(hasText("已应用这些更改。")).performScrollTo().assertIsDisplayed()
        capture("m5-receipt.png", receipt)
        val applied = history.observeMessages(thread).first().single { it.event != null }
        assertEquals(eventId, applied.id)
        assertEquals(proposed.event!!.copy(status = ProposalStatus.APPLIED), applied.event)
        val eventJson = store.db.openHelper.readableDatabase.query("SELECT eventJson FROM conversation_messages WHERE id = ?", arrayOf(eventId)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        assertEquals(setOf("status", "changes"), Json.parseToJsonElement(eventJson).jsonObject.keys)
        assertFalse(pending.proposalId in eventJson || "only-this-fixture" in eventJson || "call-" in eventJson)
        assertEquals(2, server.requestCount)
        val state = store.state(); val frame = state.frames.single()
        assertEquals(before.revision + 1, state.revision)
        assertEquals(listOf("查资料", "眼前的工作"), state.queue.map { it.title })
        assertEquals(listOf("查资料", "写提纲", "交报告"), frame.items.map { it.title }); assertEquals(2, frame.remaining)
        assertEquals(frame.items.first().taskId, state.queue.first().id)
        assertEquals(localSlot.toInstant().toEpochMilli(), (state.queue.first().schedule!!.pattern as SchedulePattern.Once).interval.startMillis)
        val g = store.api.observe(PlannerQuery.Graph(graphList().single().id)).first() as GraphDetail
        assertEquals(3, g.nodes.size); assertEquals(2, g.edges.size)
        store.reopen(); assertEquals(state, store.state())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun resizingTheDockWhileWritingKeepsTheUnsentMessage() {
        tag("assistant-input")
        val height = mutableStateOf(840.dp)
        ui.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(680.dp, 860.dp))) {
                    Box(Modifier.fillMaxWidth().height(height.value)) {
                        StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, assistant)
                    }
                }
            }
        }
        val prompt = "先讨论，尚未发送"
        tag("assistant-input").performTextReplacement(prompt)
        for (window in listOf(410.dp, 840.dp)) {
            ui.runOnIdle { height.value = window }
            tag("assistant-input").assertTextContains(prompt)
        }
        reply("收到草稿")
        tag("assistant-send").performClick()
        await(hasText("收到草稿"))
        assertEquals(prompt, assistant.state.value.lines.first().text)
        assertEquals(1, server.requestCount)
    }

    @Test fun darkComposerUsesOnlyTheOpticalRimInGlassAndKeepsTheSolidInputOutline() {
        val dark = Appearance(theme = ThemeMode.DARK, palette = PaletteMode.SEED)
        val optical = tag("navigation-capsule").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "折射胶囊"
        for (scene in listOf("page", "chat")) {
            if (scene == "chat") {
                reply("收到")
                send("查看今天")
                await(hasText("收到"))
            }
            for (glass in listOf(false, true)) {
                runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(dark.copy(assistantGlass = glass)))) }
                val material = if (glass && optical) "折射胶囊" else "实色胶囊"
                val shell = await(hasStateDescription(material) and hasAnyDescendant(hasTestTag("assistant-input")))
                fun checkRim() {
                    val pixels = shell.captureToImage().toPixelMap()
                    val contrast = buildList {
                        for (column in 1..3) for (top in listOf(true, false)) {
                            val x = pixels.width * column / 4
                            fun luminance(inset: Int) = pixels[x, if (top) inset else pixels.height - 1 - inset].luminance()
                            val fill = luminance(pixels.height / 8)
                            val edge = (0..pixels.height / 20).maxOf { luminance(it) }
                            add((edge + .05f) / (fill + .05f))
                        }
                    }
                    if (glass && optical) {
                        assertTrue("$scene: a hard outline covers the quiet side of the lens: $contrast", contrast.min() < 3f)
                    } else {
                        assertTrue("$scene: missing dark solid-field outline: $contrast", contrast.min() >= 3f)
                    }
                }
                checkRim()
                capture("assistant-dark-$scene-${if (glass) "glass" else "solid"}.png")
                tag("assistant-input").performClick().assertIsFocused()
                // Position and focus settle in Compose before Android has presented the IME.
                // Don't sample its pixels as if they were the composer border.
                ui.waitUntil(5_000) {
                    val node = shell.fetchSemanticsNode()
                    val view = (node.root as ViewRootForTest).view
                    ui.runOnIdle {
                        val frame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
                        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                            node.positionOnScreen.y + node.size.height <= frame.bottom + 1
                    }
                }
                capture("assistant-dark-$scene-${if (glass) "glass" else "solid"}-focused.png")
                checkRim()
                val host = (shell.fetchSemanticsNode().root as ViewRootForTest).view
                ui.runOnIdle {
                    host.context.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(host.windowToken, 0)
                }
            }
        }
    }

    @Test fun changingRootTabsKeepsFocusAndSendsOnceForTheOriginalDate() {
        val prompt = "安排还是刚才那一天"
        val input = tag("assistant-input")
        input.performClick().performTextInput(prompt)
        val originLabel = input.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        // Hide the IME, not the field's focus; some compact docks omit navigation while the IME shows.
        ui.activityRule.scenario.onActivity { activity ->
            requireNotNull(activity.getSystemService(InputMethodManager::class.java))
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        await(hasContentDescription("后一天")).performClick()
        input.assertIsFocused().assertTextContains(prompt)
        val before = input.fetchSemanticsNode()
        tag("nav:graphs").performClick()
        input.assertIsFocused().assertTextContains(prompt)
        val after = input.fetchSemanticsNode()
        assertEquals("Changing root pages must retain the focused editor", before.id, after.id)
        assertEquals("The persistent field must not jump", before.boundsInRoot, after.boundsInRoot)
        input.assertContentDescriptionEquals(originLabel)
        assertEquals(0, server.requestCount)
        capture("assistant-bound-draft.png")
        reply("收到")
        input.performImeAction()
        val origin = assistant.state.value.page as AssistantPage.Day
        assertTrue(origin.date.toString() in originLabel && origin.zone.id in originLabel)
        val answer = await(hasText("收到"))
        val outbound = await(hasText(prompt))
        assertTrue(outbound.fetchSemanticsNode().boundsInRoot.left > answer.fetchSemanticsNode().boundsInRoot.left)
        assertEquals(1, server.requestCount)
        capture("assistant-chat.png")
        checkReplyFootprint("assistant")
        val body = server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8()
        assertTrue("selectedDate" in body && origin.date.toString() in body)
    }

    @Test fun noticeIsCollapsedByDefaultAndSeverityRemainsReadableWithoutColor() {
        reply("这是只读回复"); send("请读取队列"); await(hasText("这是只读回复"))
        val notice = tag("assistant-notice")
        notice.assertContentDescriptionEquals("提示与说明")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        ui.onNodeWithTag("assistant-notice-details").assertDoesNotExist()
        notice.performClick()
        tag("assistant-notice-details").assertIsDisplayed()
        notice.performClick()
        ui.onNodeWithTag("assistant-notice-details").assertDoesNotExist()
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse.Builder().code(401).body("private-server-body").build())
        send("再查一次")
        await(hasText("401", substring = true))
        notice.assertContentDescriptionEquals("错误与说明").performClick()
        tag("assistant-notice-details").assertIsDisplayed()
        capture("assistant-error-details.png")
        ui.onNodeWithText("private-server-body", substring = true).assertDoesNotExist()
        val stored = runBlocking {
            val history = store.db.conversationHistory()
            history.observeMessages(history.observeConversations().first().single().id).first()
        }
        assertEquals(HistoryRole.NOTICE, stored.last().role)
        assertEquals("请求未完成，本轮未更改日程。", stored.last().text)
        assertFalse(stored.any { "private-server-body" in it.text })
    }

    @Test fun configuringDoesNotSendOrArchiveTheDraftUntilTheNextExplicitSend() = runBlocking {
        connection.clear()
        val prompt = "先留在本机"
        val input = tag("assistant-input")
        input.performClick().performTextInput(prompt)
        input.performKeyInput { pressKey(Key.Enter) }
        input.assertTextContains("$prompt\n")
        assertEquals(0, server.requestCount)
        input.performImeAction()
        await(hasText("配置模型服务")).assertIsDisplayed().performClick()
        val custom = await(hasText("自定义"))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-input").fetchSemanticsNodes().isEmpty() }
        custom.performScrollTo().performClick()
        tag("provider-url").performScrollTo().performTextReplacement("https://127.0.0.1:${server.port}/acceptance/v1")
        tag("provider-model").performScrollTo().performTextReplacement("本地验收模型")
        tag("provider-key").performScrollTo().performTextReplacement("only-this-fixture")
        tag("provider-save").performScrollTo().performClick()
        tag("assistant-input").assertTextContains("$prompt\n")
        assertNull(assistant.state.value.page)
        assertTrue(store.db.conversationHistory().observeConversations().first().isEmpty())
        assertEquals(0, server.requestCount)
        reply("已收到")
        tag("assistant-send").performClick()
        await(hasText("已收到")); assertEquals(1, server.requestCount)
        val request = Json.parseToJsonElement(requireNotNull(server.takeRequest(3, TimeUnit.SECONDS)).body!!.utf8()).jsonObject
        assertEquals("$prompt\n", request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content)
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        tag("assistant-input").assertTextEquals("")
        assertEquals(1, server.requestCount)
        assertEquals(listOf("眼前的工作"), store.state().queue.map { it.title })
    }

    @Test fun replacingCredentialsAtTheSameAddressNeverForwardsPreviousMessagesToTheNewAccount() = runBlocking {
        reply("旧账户的内容"); send("只给旧账户"); await(hasText("旧账户的内容"))
        server.takeRequest(3, TimeUnit.SECONDS)!!
        connection.save("https://127.0.0.1:${server.port}/acceptance/v1", "本地验收模型", "replacement-fixture")
        send("不转发这句")
        await(hasText("服务配置已变更，本条消息未发送。请重新输入。"))
        tag("assistant-notice").assertContentDescriptionEquals("警告与说明")
        val state = assistant.state.value
        assertTrue(state.lines.isEmpty())
        assertTrue(assistant.draft(requireNotNull(state.composerId)).field.text.isEmpty())
        assertNull(state.pending); assertEquals(1, server.requestCount)
        reply("新答复"); send("给新账户"); await(hasText("新答复"))
        val sent = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer replacement-fixture", sent.headers["Authorization"])
        val body = sent.body!!.utf8()
        assertTrue("给新账户" in body); assertFalse("不转发这句" in body || "旧账户" in body)
    }

    @Test fun cancelAndStaleConfirmationNeverApplyAnUnreviewedBatch() = runBlocking {
        val before = store.state()
        toolCall("planner_propose", composite); send("生成报告计划")
        await(hasText("放弃提案")).performClick(); assertEquals(before, store.state()); assertTrue(graphList().isEmpty())
        toolCall("planner_propose", composite); send("重新生成")
        tag("assistant-apply"); ui.activityRule.scenario.recreate(); attach(); tag("assistant-apply")
        store.apply(PlannerCommand.TaskAdd(TaskInput("用户手动添加"))); val changed = store.state()
        tag("assistant-apply").performClick(); await(hasText("重新预览")); assertEquals(changed, store.state()); assertTrue(graphList().isEmpty())
        await(hasText("重新预览")).performClick(); tag("assistant-apply"); assertEquals(changed, store.state()); assertEquals(2, server.requestCount)
        await(hasText("放弃提案")).performClick(); assertTrue(graphList().isEmpty())
        val history = store.db.conversationHistory()
        val thread = history.observeConversations().first().single().id
        val statuses = kotlinx.coroutines.withTimeout(5_000) { history.observeMessages(thread)
            .first { lines -> lines.count { it.event?.status == ProposalStatus.DISCARDED } == 2 } }.mapNotNull { it.event }
        assertEquals(listOf(ProposalStatus.DISCARDED, ProposalStatus.STALE, ProposalStatus.DISCARDED), statuses.map { it.status })
        assertTrue(statuses[1].reason?.isNotBlank() == true)
        assertEquals(2, server.requestCount)
    }

    @Test fun expiredCapabilityWithoutAReceiptShowsFailedAndNeverClaimsApplied() = runBlocking {
        val before = store.state()
        toolCall("planner_propose", """{"commands":[{"kind":"task_add","title":"不应写入"}]}""")
        send("生成本地提案")
        val apply = tag("assistant-apply")
        val pending = requireNotNull(assistant.state.value.pending)
        store.api.discard(pending.proposalId)
        apply.performClick()
        await(hasText("更改未获确认"))
        val history = store.db.conversationHistory()
        val event = history.observeMessages(history.observeConversations().first().single().id).first().single { it.event != null }.event!!
        assertEquals(ProposalStatus.FAILED, event.status)
        assertTrue(event.reason?.isNotEmpty() == true)
        assertEquals(before, store.state()); assertEquals(1, server.requestCount)
        ui.onNodeWithTag("assistant-apply").assertDoesNotExist()
    }

    @Test fun overLimitAndFailedPickerResultsRetainTheDraftUntilExplicitRemoval() {
        var selection = (1..6).map { android.net.Uri.parse("content://stacklife-fixture-missing/item/$it") }
        attach(photos = { selection })
        tag("assistant-attach").performClick()
        await(hasText("本次选择未添加", substring = true))
        tag("assistant-send").assertIsNotEnabled()
        selection = selection.take(1)
        tag("assistant-attach").performClick()
        val failed = await(hasText("图片 1 准备失败"))
        tag("assistant-input").performTextReplacement("还在编辑")
        tag("assistant-send").assertIsNotEnabled()
        failed.performClick(); await(hasText("重试")).performClick()
        await(hasText("图片 1 准备失败"))
        await(hasContentDescription("移除图片 1")).performClick()
        tag("assistant-send").assertIsEnabled()
        assertEquals(0, server.requestCount)
        assertTrue(runBlocking { store.db.conversationHistory().observeConversations().first().isEmpty() })
    }

    private fun expandTranscriptForCapture() {
        val handle = tag("planner-sheet-handle")
        val expand = androidx.compose.ui.semantics.SemanticsActions.Expand
        if (handle.fetchSemanticsNode().config.contains(expand)) handle.performSemanticsAction(expand)
        ui.waitForIdle()
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun undeclaredImageInputDoesNotTransferOwnershipOrSilentlySendOnlyText() {
        val history = store.db.conversationHistory()
        withChatPhoto(inst.targetContext, 180) { uri ->
            attach(photos = { listOf(uri) })
            tag("assistant-attach").performClick()
            tag("assistant-input").performTextReplacement("不能省略这张图")
            await(hasTestTag("assistant-send") and isEnabled()).performClick()
        }
        await(hasText("未启用图片输入", substring = true))
        val draft = assistant.draft(requireNotNull(assistant.state.value.composerId))
        val image = (draft.images.single().status as ImagePreparation.Ready).image
        assertTrue(images.file(image.id).isFile)
        assertTrue(runBlocking { history.observeConversations().first().isEmpty() })
        await(hasContentDescription("移除图片 1")).performClick()
        ui.waitUntil(5_000) { !images.file(image.id).exists() }
        tag("assistant-input").assertTextContains("不能省略这张图")
        assertEquals(0, server.requestCount)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun explicitImageOnlySendHistoryZoomAndReattachHaveIndependentFileOwnership() {
        val context = inst.targetContext
        val history = store.db.conversationHistory()
        runBlocking { connection.save("https://127.0.0.1:${server.port}/acceptance/v1", "本地验收模型", null, supportsImages = true) }
        withChatPhoto(context, 180) { first -> withChatPhoto(context, 240) { second ->
            var selection = listOf(first, second)
            attach(photos = { selection })
            tag("assistant-attach").performClick()
            await(hasTestTag("assistant-send") and isEnabled())
            selection = (1..4).map { android.net.Uri.parse("content://unused/$it") }
            tag("assistant-attach").performClick()
            await(hasText("本次选择未添加", substring = true))
            assertEquals(0, server.requestCount)
            assertTrue(runBlocking { history.imageIds().isEmpty() })
            reply("### 看到了两张图片\n\n第一张是**蓝色三角形**，第二张是**绿色圆形**。\n\n- 两张图片都在这条消息中\n- 未更改日程")
            tag("assistant-send").performClick(); await(hasText("看到了两张图片"))
        } }
        val sent = Json.parseToJsonElement(server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        val parts = sent.last().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image_url", "image_url"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        val thread = runBlocking { history.observeConversations().first().single() }
        assertTrue(thread.title.isNotBlank())
        val user = runBlocking { history.observeMessages(thread.id).first().first() }
        assertEquals("", user.text); assertEquals(listOf(180, 240), user.images.map { it.width })
        assertTrue(user.images.all { images.file(it.id).isFile })
        expandTranscriptForCapture()
        tag("assistant-scroll").performScrollToIndex(0)
        await(hasContentDescription("图片 1，180 × 120")).assertIsDisplayed()
        await(hasContentDescription("图片 2，240 × 160")).assertIsDisplayed()
        await(hasText("蓝色三角形", substring = true)).assertIsDisplayed()
        capture("u3-chat-images-markdown.png")
        await(hasText("关闭")).performClick()
        tag("assistant-history").performClick(); tag("assistant-history-thread-${thread.id}").performClick()
        val image = user.images.first()
        val transcript = tag("assistant-history-messages")
        expandTranscriptForCapture()
        transcript.performScrollToIndex(0)
        await(hasContentDescription("图片 1，180 × 120")).assertIsDisplayed()
        await(hasContentDescription("图片 2，240 × 160")).assertIsDisplayed()
        await(hasText("蓝色三角形", substring = true)).assertIsDisplayed()
        capture("u3-history-images-markdown.png")
        transcript.performScrollToNode(hasTestTag("chat-image-${image.id}")); tag("chat-image-${image.id}").performClick()
        await(hasText("放大")).performClick(); await(hasText("200%"))
        tag("chat-reattach").performClick()
        await(hasTestTag("history-reply-send") and isEnabled())
        assertEquals(1, server.requestCount)
        val replyDraft = assistant.draft("history:${thread.id}")
        val duplicate = (replyDraft.images.single().status as ImagePreparation.Ready).image
        assertNotEquals(image.id, duplicate.id); assertTrue(images.file(duplicate.id).isFile)
        await(hasContentDescription("移除图片 1")).performClick()
        ui.waitUntil(5_000) { !images.file(duplicate.id).exists() }
        assertTrue(images.file(image.id).isFile)
        tag("history-reply-input").performTextReplacement("只发文字")
        reply("没有再次发图"); tag("history-reply-send").performClick(); await(hasText("没有再次发图"))
        val resumed = server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8()
        assertFalse("image_url" in resumed || "base64" in resumed)
        assertTrue("只发文字" in resumed && "看到了两张图片" in resumed)
        await(hasText("关闭")).performClick()
        tag("assistant-history").performClick(); tag("assistant-history-thread-${thread.id}").performClick()
        tag("assistant-history-messages").performScrollToNode(hasTestTag("chat-image-${image.id}"))
        tag("chat-image-${image.id}").performClick(); tag("chat-reattach").performClick()
        tag("history-reply-input").performTextReplacement("只重发第一张")
        reply("收到第一张"); await(hasTestTag("history-reply-send") and isEnabled()).performClick(); await(hasText("收到第一张"))
        val mixed = Json.parseToJsonElement(server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        assertTrue(mixed.dropLast(1).all { it.jsonObject["content"] is JsonPrimitive })
        val attached = mixed.last().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "image_url"), attached.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(parts.first(), attached.last())
        val orderedImages = runBlocking { history.observeMessages(thread.id).first().flatMap { it.images } }
        assertEquals(listOf(180, 240, 180), orderedImages.map { it.width })
        assertEquals(3, orderedImages.map { it.id }.distinct().size)
        await(hasText("关闭")).performClick()
        tag("assistant-history").performClick(); tag("assistant-history-thread-${thread.id}").performClick()
        tag("assistant-history-delete").performClick(); tag("assistant-history-confirm-delete").performClick()
        tag("assistant-history-empty")
        ui.waitUntil(5_000) { orderedImages.none { images.file(it.id).exists() } }
        assertTrue(runBlocking { history.imageIds().isEmpty() })
        assertEquals(3, server.requestCount)
    }

    @Test fun deniesUnauthorizedReadsMalformedWritesAndAuthenticationFailuresWithoutMutation() = runBlocking {
        val baseline = store.state()
        toolCall("planner_query", """{"kind":"frames"}"""); send("读取未授权的栈")
        await(hasText("超出当前页面", substring = true)); assertEquals(1, server.requestCount)
        toolCall("planner_propose", """{"commands":[{"kind":"graph_create","title":"不能留下"},{"kind":"commit"}]}"""); send("错误工具参数")
        ui.waitUntil(8_000) { assistant.state.value.work == AssistantWork.IDLE && assistant.state.value.message != null }
        assertNull(assistant.state.value.pending)
        server.enqueue(MockResponse.Builder().code(401).body("private-server-body").build()); send("检查服务")
        await(hasText("401", substring = true)); assertFalse(assistant.state.value.message.orEmpty().contains("private-server-body"))
        assertEquals(baseline, store.state()); assertTrue(graphList().isEmpty()); assertEquals(3, server.requestCount)
    }

    @Test fun fourReadLimitStopsTheTurnAndImporterRequiresAPhoneAction() = runBlocking {
        repeat(5) { toolCall("planner_query", """{"kind":"current"}""") }; send("尝试反复读取")
        await(hasText("四次查询上限", substring = true)); assertEquals(5, server.requestCount)
        toolCall("planner_propose", """{"action":"open_course_import"}"""); send("请帮我导入课表")
        val button = await(hasText("打开本地课表导入器")); ui.onNodeWithTag("importMethodWeb").assertDoesNotExist()
        button.performClick(); tag("importMethodWeb"); tag("assistant-input").assertContentDescriptionContains("课表 · 助理消息")
        assertEquals(1, store.state().revision); assertEquals(6, server.requestCount)
    }

    @Test fun editingOnlyDurationPreservesUndisclosedNotesAndClosedRequestsCannotWrite() = runBlocking {
        val task = store.state().queue.single()
        toolCall("planner_query", """{"kind":"current"}""")
        toolCall("planner_propose", """{"commands":[{"kind":"task_edit","taskId":"${task.id}","minutes":45}]}""")
        send("只把预计时长改成 45 分钟"); tag("assistant-apply").performClick()
        await(hasText("已应用这些更改。")); assertEquals(task.copy(minutes = 45), store.state().queue.single())
        val committed = store.state()
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("""{"choices":[{"message":{"role":"assistant","content":"过时答复"},"finish_reason":"stop"}]}""").build())
        send("取消这一条"); ui.waitUntil(5_000) { server.requestCount == 3 }
        await(hasText("关闭")).performClick()
        reply("新对话"); send("重新开始"); await(hasText("新对话"))
        assertEquals(listOf("重新开始", "新对话"), assistant.state.value.lines.map { it.text })
        assertNull(assistant.state.value.pending); assertEquals(committed, store.state())
    }

    @Test fun reviewedCourseDraftOnlyExposesItsIdAndNeedsOneConfirmation() = runBlocking {
        val file = File.createTempFile("m5-course-", ".json", inst.targetContext.cacheDir)
        try {
            file.writeText("""[{"ZC":1,"KS":"2026-09-07","MONDAY":[{"KCMC":"本地课程","JSMC":"仅本机地点","JC":"5-6"}]}]""")
            attach(file)
            tag("nav:courses").performClick(); tag("course-import").performClick()
            tag("importMethodFile").performClick(); tag("importCourseFile").performClick()
            tag("importTerm:new").performClick(); tag("importTermName").performTextReplacement("秋季课表"); ui.chooseNumber("termWeeks", 2)
            ui.onNodeWithTag("assistant-input").assertDoesNotExist()
            tag("reviewCourseRows").performScrollTo().performClick()
            tag("previewCourseImportTop").performClick()
            tag("confirmCourseImportTop")
            toolCall("planner_query", """{"kind":"current"}"""); send("导入刚刚核对的课表")
            ui.waitUntil(10_000) { assistant.state.value.page is AssistantPage.CourseDraft }
            val draft = assistant.state.value.page as AssistantPage.CourseDraft
            toolCall("planner_propose", """{"commands":[{"kind":"course_import","draftId":"${draft.draftId}"}]}""")
            tag("assistant-apply")
            assertTrue((store.api.observe(PlannerQuery.Terms).first() as Terms).terms.isEmpty())
            repeat(2) {
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!.body!!.utf8()
                assertTrue(draft.draftId in sent); assertFalse("仅本机地点" in sent || "KCMC" in sent || "本地课程" in sent)
            }
            tag("assistant-apply").performClick(); await(hasText("已应用这些更改。")); await(hasText("关闭")).performClick()
            val savedTerm = (store.api.observe(PlannerQuery.Terms).first() as Terms).terms.single()
            assertEquals(LocalDate.of(2026, 9, 7), savedTerm.firstMonday)
            await(hasTestTag("course-week") and hasText(savedTerm.displayLabel())).assertIsDisplayed()
            val week = store.api.observe(PlannerQuery.Week(savedTerm.id, 1)).first() as CourseWeek
            assertEquals("仅本机地点", week.classes.single().meeting.room); assertEquals(2, store.state().revision)
        } finally { file.delete() }
    }

    @Test fun historyOpensWithoutSendingAndCannotReplayAProposal() = runBlocking {
        val before = store.state()
        val prompt = "历史里的第一条消息"
        reply("这段答复保存在手机上"); send(prompt); await(hasText("这段答复保存在手机上"))
        toolCall("planner_propose", """{"commands":[{"kind":"task_add","title":"独立的历史任务"}]}""")
        send("加入一项任务")
        tag("assistant-apply").performClick(); await(hasText("已应用这些更改。"))
        await(hasText("关闭")).performClick()
        val state = store.state()
        assertEquals(before.revision + 1, state.revision)
        val id = store.db.conversationHistory().observeConversations().first().single().id
        connection.clear()
        tag("assistant-input").performTextReplacement("未发送，不应进历史")
        tag("assistant-history").performClick(); tag("assistant-history-list").assertIsDisplayed()
        capture("assistant-history-list.png")
        tag("assistant-history-thread-$id").performClick()
        val transcript = tag("assistant-history-messages").assertIsDisplayed()
        transcript.performScrollToNode(hasText("已应用这些更改。"))
        await(hasText("已应用这些更改。")).assertIsDisplayed()
        transcript.performScrollToNode(hasText(prompt))
        await(hasText("这段答复保存在手机上")).assertIsDisplayed()
        ui.onNodeWithTag("assistant-apply").assertDoesNotExist()
        assertFalse(store.db.conversationHistory().observeMessages(id).first().any { it.text == "未发送，不应进历史" })
        capture("assistant-history-detail.png")
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        tag("assistant-history-thread-$id").assertIsDisplayed()
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        tag("assistant-input").assertTextContains("未发送，不应进历史")
        tag("assistant-history").performClick(); tag("assistant-history-thread-$id").performClick()
        tag("assistant-history-delete").performClick(); tag("assistant-history-confirm-delete").performClick()
        tag("assistant-history-empty").assertIsDisplayed()
        assertEquals(2, server.requestCount); assertEquals(state, store.state())
    }

    @Test fun historicalFollowUpSendsOnlyRecentTextAndAppendsToTheOriginalThread() = runBlocking {
        val history = store.db.conversationHistory()
        val originalPage = AssistantPage.Day(LocalDate.of(2026, 10, 17), ZoneId.of("Asia/Shanghai"))
        val id = history.sendUser(null, originalPage.scopeName(), "最早的旧消息", originalPage.historyKey(), emptyList()).conversationId
        repeat(8) {
            history.append(id, HistoryRole.USER, "问题 $it")
            history.append(id, HistoryRole.ASSISTANT, "答复 $it")
        }
        history.append(id, HistoryRole.NOTICE, "不可执行的旧提案回执")
        val other = history.sendUser(null, "当前队列", "另一段不应发送的聊天", null, emptyList()).conversationId
        tag("nav:workspace").performClick()
        tag("assistant-history").performClick(); tag("assistant-history-thread-$id").performClick()
        checkReplyFootprint("history-reply")
        tag("history-reply-input").performTextReplacement("接着最后一条")
        val baseline = store.state()
        capture("history-continue.png")
        assertEquals(0, server.requestCount)
        assertEquals(18, history.observeMessages(id).first().size)
        reply("接上了")
        tag("history-reply-send").performClick(); await(hasText("接上了"))
        val body = Json.parseToJsonElement(server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
        val text = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("content").jsonPrimitive.content }
        assertTrue("2026-10-17" in text[1] && "Asia/Shanghai" in text[1])
        assertEquals((2..7).flatMap { listOf("问题 $it", "答复 $it") } + "接着最后一条", text.drop(2))
        assertEquals(listOf(id, other), history.observeConversations().first().map { it.id })
        assertEquals(listOf("接着最后一条", "接上了"), history.observeMessages(id).first().takeLast(2).map { it.text })
        assertEquals(baseline, store.state())
    }

    @Test fun changingDisclosureOnlyAffectsAnExplicitNewSendAndDoesNotForwardOldChat() {
        reply("先不读取"); send("上一段对话"); await(hasText("先不读取"))
        server.takeRequest(3, TimeUnit.SECONDS)!!
        tag("assistant-notice").performClick()
        await(hasText("调整数据范围")).performClick()
        val notes = hasText(ui.activity.getString(io.github.nakanomk.stacklife.R.string.assistant_share_notes))
        await(notes).performScrollTo().performClick()
        assertTrue(assistant.state.value.grant.notes); assertEquals(1, server.requestCount)
        tag("assistant-notice").performClick()
        toolCall("planner_query", """{"kind":"current"}"""); reply("读取完成")
        send("这次读备注"); await(hasText("读取完成"))
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8()
        assertFalse("上一段对话" in request || "先不读取" in request)
        assertTrue("不可外发的测试备注" in server.takeRequest(3, TimeUnit.SECONDS)!!.body!!.utf8())
    }

    private val localSlot = LocalDate.now().atTime(16, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime()
    private val composite = """{"commands":[
        {"kind":"graph_create","title":"报告计划","ref":"g"},
        {"kind":"graph_patch","graphId":"@g","edits":[{"kind":"add_node","title":"查资料","ref":"a"},{"kind":"add_node","title":"写提纲","ref":"b"},{"kind":"add_node","title":"交报告","ref":"c"},{"kind":"add_edge","from":"@a","to":"@b"},{"kind":"add_edge","from":"@b","to":"@c"}]},
        {"kind":"graph_frame","graphId":"@g","goalId":"@c","ref":"f","taskRefs":{"@a":"ta"}},
        {"kind":"frame_restore","frameId":"@f","mode":"selected","taskIds":["@ta"]},
        {"kind":"set_schedule","scheduleId":"@ta","pattern":{"kind":"once","start":"$localSlot","durationMinutes":30}}]}"""
}
