package io.github.nakanomk.stacklife.gallery

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.LocalRootBottom
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.workspace.Hint
import io.github.nakanomk.stacklife.workspace.Page
import io.github.nakanomk.stacklife.workspace.SectionTitle

private const val appLicense = "StackLife GPL-3.0-or-later"
private const val currentBiliPai = "BiliPai 5893c2c80739e3759ac64ae5f232cf03a94da7f8"

internal val motionExamples: List<GalleryEntry> = listOf(
    GalleryEntry("motion-task", GalleryGroup.MOTION, "任务卡片 · 开启、返回与反拖取消",
        "workspace/WorkspaceScreen.kt",
        "workspace/WorkspaceScreen.kt → TaskCardPilot → workspace/Details.kt::TaskScreen。BiliPai c7f835b34d8323358be57be298f86f29fecb53c8：navigation3/{BiliPaiAndroidXNavDisplay,SettingsContainerTransition,SettingsContainerMotionPolicy}.kt、miuix-navigation。",
        "$appLicense；BiliPai GPL-3.0；miuix-navigation 与 AndroidX Navigation 3 Apache-2.0",
        "隔离试验和优化后的 .bench 使用同一份实时任务卡和单时钟。正式版尚未切换；下方链接为原队列组件。",
        "点一张完全可见的任务卡。在手势导航设备上从边缘拖至一半再反拖取消：应仍停在同一任务。再用返回按钮回到队列。acceptance/SceneMotionTest#firstTaskPushAndReversiblePredictivePopUseTheScrolledRow。") {
        MotionWorkbench(Destination.WORKSPACE)
    },
    GalleryEntry("motion-stack", GalleryGroup.MOTION, "工作台 · 恢复与压栈",
        "workspace/Frames.kt",
        "workspace/Frames.kt::FrameScreen、workspace/WorkspaceScreen.kt::WorkspaceScreen、WorkspaceViewModel.kt::pushQueue；Reorderable 3.1.0 (66de5751227c440bcdbd63acc94999231ed67487)。",
        "$appLicense；Reorderable Apache-2.0",
        "只操作 Gallery Planner。压栈后可补原因或撤回；恢复后可撤回，取空的栈帧可保留为历史。未启用 U3 跨页转场。",
        "打开一个栈帧，恢复一项后点底栏的撤回，再从原帧找到该项。切到队列，多选压栈后给新帧补原因。acceptance/WorkspaceUiTest#pushSelectBThenResumeAThroughTheWorkbench。") {
        MotionWorkbench(Destination.WORKSPACE, startOnStack = true)
    },
    GalleryEntry("motion-tray", GalleryGroup.MOTION, "今天 · 待排拾取、时间轴与事件行",
        "today/DayTray.kt",
        "today/TodayScreen.kt::TodayRoute、today/{DayTray,DayAxisDrag,DayAxis,EventDay,DayEventRows}.kt；today/TimeGridPlacement.kt 使用 FullCalendar Standard 6.1.22。",
        "$appLicense；FullCalendar Standard MIT",
        "操作生产待排托盘和时间轴。长按后原列表补位，浮窗只预览位置；松手安排才通过 Gallery Planner 写入时段。",
        "长按一个未排任务，拖到轴上后取消，核对待排列表不变；再拖入并松手，在事件模式打开对应行，核对同一任务和时间。acceptance/DayTimelineTest#dragFromTrayThenMoveSameBlockAndCancelInEditor。") {
        MotionWorkbench(Destination.TODAY)
    },
    GalleryEntry("motion-calendar", GalleryGroup.MOTION, "日期、周次与根导航",
        "today/CalendarPager.kt",
        "RootPages.kt::RootPages、FloatingDock.kt::DockOverlay、today/{CalendarPager,TodayScreen}.kt、courses/{CoursesRoute,CourseHome}.kt；O2：AndroidX Foundation PagerState.kt @ 4aa409d104231ea63ba917270cd1b2c3dd747353。",
        "$appLicense；AndroidX Compose Apache-2.0",
        "真实的今天和课表共享生产根分页器与悬浮导航。仅缩小允许的目的地，不打开课表网络导入。",
        "点日期箭头，再反向滑动日页；用底栏进入课表，切到第二周并打开该周课程，再返回今天核对日期。acceptance/CalendarArrowTest#arrowsSlideTheCalendarUnderAStationaryDockInBothDirections。") {
        MotionWorkbench(Destination.TODAY, calendarNavigation = true)
    },
    GalleryEntry("motion-assistant", GalleryGroup.MOTION, "离线助理 · 输入、消息与历史面板",
        "assistant/AssistantSheet.kt",
        "assistant/AssistantSheet.kt::{AssistantComposer,AssistantPanels}、assistant/{ChatMessages,ConversationHistorySheet}.kt、transitions/PaneMotion.kt；$currentBiliPai / AppCompactInputField.kt。",
        "$appLicense；BiliPai GPL-3.0",
        "使用隔离的空连接与本地历史。未连接的发送只打开真实离线消息面板；不会产生模型回复、伪造历史，也不能配置服务或导入课表。",
        "输入草稿并点发送，核对未连接提示；关闭后用历史按钮打开合成的本机用户消息。未连接的发送不会新增回复。面板验收：assistant/ConversationPaneMotionTest。") {
        OfflineAssistantExample()
    },
)

@Composable
private fun OfflineAssistantExample() {
    val gallery = LocalContext.current.applicationContext as GalleryApplication
    val model = viewModel { gallery.newAssistant() }
    val activity = LocalActivity.current
    var historyKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restricted by rememberSaveable { mutableStateOf(false) }
    val history = remember(historyKey) { historyKey?.let(::historyPage) }
    DisposableEffect(model, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                model.browsingHistory = false
                model.close()
            }
        }
    }
    CompositionLocalProvider(LocalAssistant provides model, LocalOpenChatHistory provides { historyKey = it.historyKey() }) {
        Page(AssistantPage.Queue) {
            Column(Modifier.fillMaxSize().padding(LocalRootBottom.current)
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(uiText("Offline queue assistant", "队列助理（离线）"))
                Hint(uiText("Type a draft and press Send to open the production message sheet without a connection. History contains only local records; an unsent draft is not a conversation.",
                    "输入草稿并点发送，可打开未配置连接时的消息面板。历史只显示本地记录，未发送的草稿不会成为对话。"))
            }
        }
        AssistantPanels(model, history, configuring = restricted, onCloseHistory = { historyKey = null },
            onSettings = { restricted = true }, onImport = { restricted = true })
        if (restricted) OfflineBoundary { restricted = false }
    }
}

@Composable
internal fun OfflineBoundary(close: () -> Unit) {
    PlannerAlertDialog(onDismissRequest = close,
        title = { Text(uiText("Offline Gallery", "离线 Gallery")) },
        text = { Text(uiText("This catalog has no model connection or school login. No request was sent.",
            "本目录不开放模型连接或学校登录，未发送网络请求。")) },
        confirmButton = { TextButton(onClick = close) { Text(uiText("Return", "返回")) } })
}
