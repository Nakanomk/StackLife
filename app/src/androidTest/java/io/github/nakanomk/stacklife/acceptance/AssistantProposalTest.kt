package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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

    @After fun teardown() { server.close(); runBlocking { connection.clear() } }
    private fun attach(file: File? = null) = ui.activityRule.scenario.onActivity { activity ->
        val owner = file?.let(::pickerReturning) ?: activity
        activity.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            val controller = viewModel { AssistantModel(store.api, connection, transport) }
            assistant = controller
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, controller)
        } }
    }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }
    private fun tag(value: String) = await(hasTestTag(value))
    private fun send(text: String) { tag("assistant-input").performTextReplacement(text); tag("assistant-send").performClick() }
    private fun startTurn(text: String) { send(text); tag("assistant-consent").performClick() }
    private fun toolCall(name: String, args: String) {
        val fn = buildJsonObject { put("name", name); put("arguments", args) }
        val tc = buildJsonObject { put("id", "call-${++call}"); put("type", "function"); put("function", fn) }
        val reply = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[$tc]}}]}"""
        server.enqueue(MockResponse(body = reply))
    }
    private fun graphList() = runBlocking { (store.api.observe(PlannerQuery.Graphs).first() as GraphList).graphs }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureM5") != "yes") return
        ui.waitForIdle()
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
        input.performImeAction()
        val consent = tag("assistant-consent")
        ui.onNodeWithTag("assistant-input").assertDoesNotExist()
        assertEquals(prompt, assistant.state.value.draft)
        assertEquals(0, server.requestCount)
        toolCall("planner_query", """{"kind":"current"}"""); toolCall("planner_propose", composite)
        consent.performClick(); ui.runOnUiThread { assistant.authorize() }
        val apply = tag("assistant-apply"); val pending = requireNotNull(assistant.state.value.pending)
        assertEquals(before, store.state()); assertTrue(graphList().isEmpty())
        val first = server.takeRequest(3, TimeUnit.SECONDS)!!; val second = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/acceptance/v1/chat/completions", first.target)
        val firstMessages = Json.parseToJsonElement(first.body!!.utf8()).jsonObject.getValue("messages").jsonArray
        assertEquals(1, firstMessages.count { it.jsonObject["content"]?.jsonPrimitive?.content == prompt })
        val outgoing = Json.parseToJsonElement(second.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        val data = outgoing.last().jsonObject
        assertEquals("tool", data["role"]!!.jsonPrimitive.content); assertTrue(data.toString().contains("眼前的工作"))
        assertFalse(outgoing.toString().contains("不可外发的测试备注")); assertFalse(outgoing.toString().contains("only-this-fixture"))
        capture("m5-proposal.png")
        apply.performClick(); ui.runOnUiThread { assistant.apply(pending.proposalId) }
        await(hasText("已应用这些更改。")); capture("m5-receipt.png")
        assertEquals(2, server.requestCount)
        val state = store.state(); val frame = state.frames.single()
        assertEquals(before.revision + 1, state.revision)
        assertEquals(listOf("查资料", "眼前的工作"), state.queue.map { it.title })
        assertEquals(listOf("查资料", "写提纲", "交报告"), frame.items.map { it.title }); assertEquals(2, frame.remaining)
        assertEquals(frame.items.first().taskId, state.queue.first().id)
        assertEquals(localSlot.toInstant().toEpochMilli(), state.queue.first().scheduledStartMillis)
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
        tag("assistant-send").performClick()
        tag("assistant-consent")
        assertEquals(prompt, assistant.state.value.draft)
        assertEquals(0, server.requestCount)
    }

    @Test fun configurationRoundTripReturnsOnlyTheLocalDraftAndBackCancelsConsent() = runBlocking {
        connection.clear()
        val prompt = "先留在本机"
        val input = tag("assistant-input")
        input.performClick().performTextInput(prompt)
        input.performKeyInput { pressKey(Key.Enter) }
        await(hasText("配置模型服务")).performClick()
        val custom = await(hasText("自定义"))
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("assistant-input").fetchSemanticsNodes().isEmpty() }
        custom.performScrollTo().performClick()
        tag("provider-url").performScrollTo().performTextReplacement("https://127.0.0.1:${server.port}/acceptance/v1")
        tag("provider-model").performScrollTo().performTextReplacement("本地验收模型")
        tag("provider-key").performScrollTo().performTextReplacement("only-this-fixture")
        tag("provider-save").performScrollTo().performClick()
        tag("assistant-input").assertTextContains(prompt)
        assertNull(assistant.state.value.page); assertFalse(assistant.state.value.allowed)
        assertEquals(0, server.requestCount)
        tag("assistant-send").performClick()
        tag("assistant-consent"); assertEquals(0, server.requestCount)
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        tag("assistant-input").assert(hasText(prompt).not())
        assertEquals(0, server.requestCount)
        assertEquals(listOf("眼前的工作"), store.state().queue.map { it.title })
    }

    @Test fun replacingCredentialsAtTheSameAddressInvalidatesConsentAndDiscardsMessage() = runBlocking {
        send("不发送到另一个账户"); val consent = tag("assistant-consent")
        connection.save("https://127.0.0.1:${server.port}/acceptance/v1", "本地验收模型", "replacement-fixture")
        consent.performClick()
        await(hasText("服务配置已变更，请重新授权。"))
        val state = assistant.state.value
        assertFalse(state.allowed); assertTrue(state.lines.isEmpty()); assertTrue(state.draft.isEmpty())
        assertNull(state.pending); assertEquals(0, server.requestCount)
    }

    @Test fun cancelAndStaleConfirmationNeverApplyAnUnreviewedBatch() = runBlocking {
        val before = store.state()
        toolCall("planner_propose", composite); startTurn("生成报告计划")
        await(hasText("放弃提案")).performClick(); assertEquals(before, store.state()); assertTrue(graphList().isEmpty())
        tag("assistant-consent").performClick(); toolCall("planner_propose", composite); send("重新生成")
        tag("assistant-apply"); ui.activityRule.scenario.recreate(); attach(); tag("assistant-apply")
        store.apply(PlannerCommand.TaskAdd(TaskInput("用户手动添加"))); val changed = store.state()
        tag("assistant-apply").performClick(); await(hasText("重新预览")); assertEquals(changed, store.state()); assertTrue(graphList().isEmpty())
        await(hasText("重新预览")).performClick(); tag("assistant-apply"); assertEquals(changed, store.state()); assertEquals(2, server.requestCount)
        await(hasText("放弃提案")).performClick(); assertTrue(graphList().isEmpty())
    }

    @Test fun deniesUnauthorizedReadsMalformedWritesAndAuthenticationFailuresWithoutMutation() = runBlocking {
        val baseline = store.state()
        toolCall("planner_query", """{"kind":"frames"}"""); startTurn("读取未授权的栈")
        await(hasText("超出当前页面", substring = true)); assertEquals(1, server.requestCount)
        toolCall("planner_propose", """{"commands":[{"kind":"graph_create","title":"不能留下"},{"kind":"commit"}]}"""); send("错误工具参数")
        ui.waitUntil(8_000) { assistant.state.value.work == AssistantWork.IDLE && assistant.state.value.message != null }
        assertNull(assistant.state.value.pending)
        server.enqueue(MockResponse.Builder().code(401).body("private-server-body").build()); send("检查服务")
        await(hasText("401", substring = true)); assertFalse(assistant.state.value.message.orEmpty().contains("private-server-body"))
        assertEquals(baseline, store.state()); assertTrue(graphList().isEmpty()); assertEquals(3, server.requestCount)
    }

    @Test fun fourReadLimitStopsTheTurnAndImporterRequiresAPhoneAction() = runBlocking {
        repeat(5) { toolCall("planner_query", """{"kind":"current"}""") }; startTurn("尝试反复读取")
        await(hasText("四次查询上限", substring = true)); assertEquals(5, server.requestCount)
        toolCall("planner_propose", """{"action":"open_course_import"}"""); send("请帮我导入课表")
        val button = await(hasText("打开本地课表导入器")); ui.onNodeWithTag("importCourseHub").assertDoesNotExist()
        button.performClick(); tag("importCourseHub"); tag("assistant-input").assertContentDescriptionContains("课表 · 助理消息")
        assertEquals(1, store.state().revision); assertEquals(6, server.requestCount)
    }

    @Test fun editingOnlyDurationPreservesUndisclosedNotesAndClosedRequestsCannotWrite() = runBlocking {
        val task = store.state().queue.single()
        toolCall("planner_query", """{"kind":"current"}""")
        toolCall("planner_propose", """{"commands":[{"kind":"task_edit","taskId":"${task.id}","minutes":45}]}""")
        startTurn("只把预计时长改成 45 分钟"); tag("assistant-apply").performClick()
        await(hasText("已应用这些更改。")); assertEquals(task.copy(minutes = 45), store.state().queue.single())
        val committed = store.state()
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("""{"choices":[{"message":{"role":"assistant","content":"过时答复"},"finish_reason":"stop"}]}""").build())
        send("取消这一条"); ui.waitUntil(5_000) { server.requestCount == 3 }
        await(hasText("关闭")).performClick(); send("重新开始"); tag("assistant-consent")
        assertTrue(assistant.state.value.lines.isEmpty()); assertFalse(assistant.state.value.allowed)
        assertNull(assistant.state.value.pending); assertEquals(committed, store.state())
    }

    @Test fun reviewedCourseDraftOnlyExposesItsIdAndNeedsOneConfirmation() = runBlocking {
        val file = File.createTempFile("m5-course-", ".json", inst.targetContext.cacheDir)
        try {
            file.writeText("""[{"ZC":1,"KS":"2026-09-07","MONDAY":[{"KCMC":"本地课程","JSMC":"仅本机地点","JC":"5-6"}]}]""")
            attach(file)
            tag("nav:courses").performClick(); tag("course-import").performClick(); tag("importCourseFile").performClick()
            tag("termId").performScrollTo().performTextInput("秋季"); tag("termWeeks").performScrollTo().performTextInput("2")
            ui.onNodeWithTag("assistant-input").assertDoesNotExist()
            tag("reviewCourseRows").performScrollTo().performClick()
            tag("courseIssues").performScrollToNode(hasTestTag("previewCourseImport")); tag("previewCourseImport").performClick()
            send("导入刚刚核对的课表"); val consent = tag("assistant-consent")
            val draft = assistant.state.value.page as AssistantPage.CourseDraft
            toolCall("planner_query", """{"kind":"current"}""")
            toolCall("planner_propose", """{"commands":[{"kind":"course_import","draftId":"${draft.draftId}"}]}""")
            consent.performClick(); tag("assistant-apply")
            assertTrue((store.api.observe(PlannerQuery.Terms).first() as Terms).terms.isEmpty())
            repeat(2) {
                val sent = server.takeRequest(2, TimeUnit.SECONDS)!!.body!!.utf8()
                assertTrue(draft.draftId in sent); assertFalse("仅本机地点" in sent || "KCMC" in sent || "本地课程" in sent)
            }
            tag("assistant-apply").performClick(); await(hasText("已应用这些更改。")); await(hasText("关闭")).performClick()
            await(hasTestTag("course-term") and hasText("秋季")).assertIsDisplayed()
            val week = store.api.observe(PlannerQuery.Week("秋季", 1)).first() as CourseWeek
            assertEquals("仅本机地点", week.classes.single().meeting.room); assertEquals(2, store.state().revision)
        } finally { file.delete() }
    }

    private val localSlot = LocalDate.now().atTime(16, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime()
    private val composite = """{"commands":[
        {"kind":"graph_create","title":"报告计划","ref":"g"},
        {"kind":"graph_patch","graphId":"@g","edits":[{"kind":"add_node","title":"查资料","ref":"a"},{"kind":"add_node","title":"写提纲","ref":"b"},{"kind":"add_node","title":"交报告","ref":"c"},{"kind":"add_edge","from":"@a","to":"@b"},{"kind":"add_edge","from":"@b","to":"@c"}]},
        {"kind":"graph_frame","graphId":"@g","goalId":"@c","ref":"f","taskRefs":{"@a":"ta"}},
        {"kind":"frame_restore","frameId":"@f","mode":"selected","taskIds":["@ta"]},
        {"kind":"slot_set","taskId":"@ta","start":"$localSlot","durationMinutes":30}]}"""
}
