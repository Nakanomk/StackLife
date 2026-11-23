package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import java.time.LocalDate

internal fun starterPlan(today: LocalDate): List<PlannerCommand> {
    val frame = uiText("Practice · Camera bag", "练习 · 摄影包")
    val battery = uiText("Charge the battery", "给电池充电")
    val graph = uiText("Practice · First photo", "练习 · 第一张照片")
    val route = uiText("Pick a route", "选路线")
    val light = uiText("Check the light", "看光线")
    val photo = uiText("Take a photo", "拍张照片")
    val countdown = uiText("Practice · Photo walk", "练习 · 摄影散步日")
    return listOf(
        PlannerCommand.TaskAdd(TaskInput(
            title = uiText("Practice 1 · Choose a photo subject", "练习 1 · 给摄影散步选个主题"),
            notes = uiText(
                "1. In **Workspace → Queue**, tap a task card to read its full **Notes**. Tap **Edit note**, add a subject such as ‘reflections’, then **Save**. You’ll see your words here.\n" +
                    "2. Back in the queue, drag this card’s handle down one place. The first task is now **Up next**. To move the card back, tap its handle and choose **Move up one**.\n" +
                    "3. To try a time slot, open **Today**, expand **Unscheduled**, tap this task, choose a start time and tap **Save time slot**. Skip this step to leave it unscheduled.\n" +
                    "4. To reuse this card, open it and choose **Edit task** to change its title or duration. When finished, tap **Complete task**; to discard the example instead, tap **Remove from queue**.\n\n" +
                    "To start your own task, tap **Add task** (+) in the queue, enter a title, then tap **Add to queue**. If a page is hidden, open it under **Settings → Feature shortcuts**.",
                "1. 在**工作台 → 队列**点任务卡片，就能看到完整的**笔记**。点**编辑笔记**，写下想拍的主题，比如“倒影”，再点**保存**；这里就会出现刚写的字。\n" +
                    "2. 回到队列，拖动这张卡片的手柄，把它下移一位。排在第一位的任务会标为**当前优先**。想移回来，轻点手柄，选**上移一位**。\n" +
                    "3. 想给任务留出时间，可到**今天**展开**待排队列**，点这项任务，选好开始时间，再点**保存时段**。跳过这步就不会排程。\n" +
                    "4. 想继续用这张卡片，可在详情点**编辑任务**改标题或时长。做完点**完成任务**；不想保留示例，就点**移出队列**。\n\n" +
                    "想加自己的任务，在队列点右下角的加号（**新增任务**），填好标题后点**添加到队列**。底栏没有的页面可从**设置 → 功能入口**打开。",
            ),
            minutes = 5,
        )),
        PlannerCommand.TaskAdd(TaskInput(
            title = uiText("Practice 2 · Unpack a saved frame", "练习 2 · 从栈帧取出摄影装备"),
            notes = uiText({
                "1. Open **Workspace → Stack → $frame**. Both packing tasks are in this frame, separate from your queue.\n" +
                    "2. Beside **$battery**, tap **Edit task**, change the duration and tap **Save changes**. Compare the new duration with **When saved** beneath it.\n" +
                    "3. Tap **Select tasks**, tick just **$battery**, then **Restore selected**. The edited original moves to your queue; your other queue items stay there. The frame keeps its snapshot and has one task left to restore.\n" +
                    "4. To clear the example, restore the second task the same way. Use **Remove from queue** on the two restored cards. You can now use **Delete frame history** in the empty frame.\n\n" +
                    "Next time you’re interrupted, use **Push to stack** in the queue to save the whole queue, or **Select → Push selected** to set aside only the tasks you tick."
            }, {
                "1. 打开**工作台 → 栈 → $frame**。这里的两项装包任务都在帧里，没有占用队列。\n" +
                    "2. 点“$battery”旁的**编辑任务**，改个时长，再点**保存修改**。对照新时长和下方的**保存时的快照**，看看哪一个变了。\n" +
                    "3. 点**选择恢复**，只勾“$battery”，再点**恢复所选**。改过时长的原任务进了队列，原有队列项仍在；帧里保留快照，还有一项可恢复。\n" +
                    "4. 要清理示例，用同样的方法恢复第二项，在两张恢复出来的卡片里分别点**移出队列**。帧取空后，可在帧详情点**删除栈帧历史**。\n\n" +
                    "下次被打断时，在队列点**压栈**可以保存整个队列；只想放下几件事，就点**多选**、勾选任务，再点**压入所选**。"
            }),
            minutes = 5,
        )),
        PlannerCommand.TaskAdd(TaskInput(
            title = uiText("Practice 3 · Plan the first photo", "练习 3 · 安排拍照前的两件事"),
            notes = uiText({
                "1. Open **Graphs → $graph**. Tap **Fit graph** to see both arrows: **$route** and **$light** each lead to **$photo**. Neither needs the other.\n" +
                    "2. Tap the **$photo** node, then **Generate task order**. Under **Execution order**, the two preparations come first, in that order, and the photo comes last.\n" +
                    "3. **Append new events in this order: 3** adds three new tasks at the end of your queue. **Save as frame** keeps the same three in a new frame and leaves your queue alone. Or go back without choosing either.\n" +
                    "4. Keep the graph as a template for another walk, or use **Delete graph** in its list menu. Deleting the template leaves any tasks or frames you already generated."
            }, {
                "1. 打开**流程图 → $graph**，点**全图**看两条箭头：“$route”和“$light”各自指向“$photo”，两项准备工作互不依赖。\n" +
                    "2. 点“$photo”节点，再点**生成拓扑排序**。在**执行顺序**里，前两项依次是“$route”和“$light”，最后才是“$photo”。\n" +
                    "3. **将 3 个新事件按此顺序加入队列**会在队尾追加三个新任务。**存为栈帧**把这三项存进新帧，当前队列不变。只想看顺序，直接返回即可。\n" +
                    "4. 下次散步时可以再用这张图，也可在流程图列表的菜单里点**删除流程图**。删图不会带走已经生成的任务或栈帧。"
            }),
            minutes = 5,
        )),
        PlannerCommand.TaskAdd(TaskInput(
            title = uiText("Practice 4 · Set the walk date", "练习 4 · 换个摄影散步日"),
            notes = uiText({
                "1. Open **Countdown → $countdown**. On the day you add the example, the card shows 7 days until the walk. It counts calendar dates, not 24-hour periods.\n" +
                    "2. Tap **Edit → Date** and choose today. The preview shows 0 days. Go back without saving to keep the original date.\n" +
                    "3. To use the card for your own walk, set **Name** and **Date**, then tap **Save**. This changes the countdown without scheduling a task or reminder.\n" +
                    "4. To discard it, open **Edit**, tap **Delete countdown**, then confirm."
            }, {
                "1. 打开**倒数日 → $countdown**。添加示例当天，卡片显示离散步还有 7 天。这里数的是相差几个日历日，不是经过了几个 24 小时。\n" +
                    "2. 点**编辑 → 日期**，选今天，预览就变成 0 天。直接返回、不保存，会保留原日期。\n" +
                    "3. 想留作自己的散步计划，就改好**名称**和**日期**，再点**保存**。改倒数日不会安排任务，也不会设置提醒。\n" +
                    "4. 不需要这张卡片时，进**编辑**点**删除倒数日**并确认。"
            }),
            minutes = 3,
        )),
        PlannerCommand.FrameCreate(frame, listOf(
            TaskInput(battery, uiText("For the practice walk, check the charge before packing the camera.", "摄影散步练习：装包前，看一眼相机还剩多少电。"), 10),
            TaskInput(uiText("Pack a memory card", "带上存储卡"), uiText("For the practice walk, check that the card has room for a few photos.", "摄影散步练习：确认卡里还有拍几张照片的空间。"), 2),
        )),
        PlannerCommand.GraphCreate(graph, ref = "starterGraph"),
        PlannerCommand.GraphPatch("@starterGraph", listOf(
            // Match the canvas's 16 dp origin and 210 dp layer spacing; leave room for two-line titles at large font sizes.
            GraphEdit.AddNode(route, uiText("Choose a nearby spot you can reach on foot.", "选一个能步行到达的地点。"), 5, x = 16f, y = 16f, ref = "starterRoute"),
            GraphEdit.AddNode(light, uiText("Look outside and decide whether to use sun or shade.", "看看窗外，决定在阳光下还是阴影里拍。"), 2, x = 16f, y = 160f, ref = "starterLight"),
            GraphEdit.AddNode(photo, uiText("At your chosen spot, take one photo in the light you picked.", "到了选好的地点，用刚才选的光线拍一张照片。"), 5, x = 226f, y = 88f, ref = "starterPhoto"),
            GraphEdit.AddEdge("@starterRoute", "@starterPhoto"),
            GraphEdit.AddEdge("@starterLight", "@starterPhoto"),
        )),
        PlannerCommand.CountdownAdd(countdown, today.plusDays(7).toEpochDay()),
    )
}
