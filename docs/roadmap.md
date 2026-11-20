# StackLife 长线产品与验收路线图

StackLife 是用 Kotlin + Jetpack Compose 开发的、手机优先的原生 Android 日程工具。**队列**回答「我现在要做什么」，**栈帧**记录「我为什么停下、怎样接回去」。**DAG** 决定为选定目标先做什么，**时间轴**决定哪天几点做，**课表**标出已经占用的时段。一项工作不因换视图而变成几份任务。

**当前交付：v0.9.0 · 手动录课与日程交互。** 空课表可直接添加课程；已有学期从「课表选项 → 添加课程」进入。今天可长按已排任务旁的竖条，拖下端点改变结束时间。顶栏箭头横移日／周；底部输入和导航不参与横移。玻璃使用 BiliPai 同源的 Miuix 渲染器及其原版折射着色器，不支持时仍使用同形 MD3。普通包不含联盟 SDK。

## 起点与产品决定

- 规划时仓库仅有 `.git/`，无可迁移的 App 或项目级 Agent 文件。架构从 [Android 官方建议](https://developer.android.com/topic/architecture/recommendations) 起步，不复制大型示例的模块拓扑。
- 本文的 `../WadwaitaUp/…` 均相对 **StackLife 项目根目录**。`browser.py:37–62,103–169` 有真正的 HUB/Vue 提取路径；`models.py:33–62` 有夏/冬两套 12 节表。这是桌面来源，不是可装到 Android 的插件。
- 规划时本机有 Android Studio、`adb`、JDK 17/21，系统默认 Java 27。`~/Android/Sdk/platforms` 仅有 `android-36.1`，37.0 的 x86_64 镜像目录仅见 `.installer`，`~/.android/avd` 为空。不能当成可启动设备；规划阶段未装 SDK、未启设备、未运行应用。
- 恢复默认按帧内原序插队首，可改队尾或整帧切换，不得覆盖眼前未完成的工作。「顶级」指**选定的目标事件**：取前置及目标本身的合法顺序，不做截止时间倒排。
- 日程留在设备。模型连接可选官方预设或自定义 OpenAI 兼容地址，由用户提供自己的 API Key。「接口」指 UI 和模型共用的本地业务接口；不监听端口、不对外开放。模型可查询和提案，不能确认写入。
- 华科网页导入参照 [WakeUp 的教务导入流程](https://www.wakeup.fun/doc/import_from_eas.html)：手机登录、进入课表、点提取、核对。只借交互，不复制源码。网页和本人导出的 JSON 共用 Kotlin 解析器和确认事务；不采集密码、Cookie，不把校方页面给模型。

## 交付顺序

|阶段|依赖|可验收的用户结果|
|---|---|---|
|M0 约束落盘|无|根 `AGENTS.md` 提供执行入口，`docs/roadmap.md` 保存产品契约、依据、里程碑与验收表。只留两份长期文档。|
|M1 可以放心中断|M0|可安装的原生应用：真实队列、带原因的持久栈帧、整帧/部分恢复及三种策略。从第一屏使用 Expressive 主题和 fumo 图标；只显示已可用的目的地。|
|M2 安排今天|M1|从同屏托盘把队列任务拖到当天时间轴，时间选择器可做等价操作。|
|M3 课表占位|M2|华科用户本人登录或选文件，预览、导入、看周课表；课程占用同一条日程轴。|
|M4 从目标拆解工作|M1；与 M2 独立|手绘编辑 DAG，预览所选目标的前置顺序，一次生成新栈帧，不扰动队列。|
|M5 用对话改真实日程|M1–M4|各业务页可打开轻量对话，BYOK、受限查询、一次确认整批写入；不配网络也能手动使用。|
|M6 自定义与系统动效|M1–M4；助理入口材质验收需 M5|完整外观设置、可开关的局部折射、联盟 SharedView Kit 适配与真机流畅性验收。基础无障碍和应用内转场从 M1 就做。|

## 原生底座与本地事务

从 M1 随切片增加，不先造一套空框架。只建一个生产 `:app`，包名 `io.github.nakanomk.stacklife`。主 Activity + 按功能分包的 Compose 屏幕；只有联盟系统路径另需一个不导出的详情 Activity。应用级容器做构造器注入；ViewModel 暴露 `StateFlow`，页面用 `collectAsStateWithLifecycle()`。不建服务端、微服务、动态插件框架或每按钮一个 use case。

|构建面|规划锁定与依据|
|---|---|
|平台与界面|`compileSdk 37.2`、`targetSdk 37`、`minSdk 26`（涵盖联盟 Kit 下限）。[Compose 稳定发布](https://developer.android.com/blog/posts/jetpack-compose-november-2026-release)对应 BOM `2026.11.01` / Compose `1.13.0` / Material3 `1.5.0`。|
|工具链|[AGP 9.5.0 / Gradle wrapper 9.6.0 / JDK 17](https://developer.android.com/build/releases/agp-9-5-0-release-notes)，用 [AGP 内建 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)；Compose 编译插件和序列化插件统一 Kotlin `2.4.10`，KSP2 `2.3.14`。|
|导航与存储|[Navigation 3 `1.2.2`](https://developer.android.com/jetpack/androidx/releases/navigation3)、[Room `2.8.5`](https://developer.android.com/jetpack/androidx/releases/room)。版本集中在 `gradle/libs.versions.toml`，不用 `+`、snapshot 或 beta BOM。|

本地入口在 `app/src/main/java/io/github/nakanomk/stacklife/planner/PlannerApi.kt`。`discard` 只撤销未消费的内存提案，不写业务表：

```kotlin
interface PlannerApi {
    fun observe(query: PlannerQuery): Flow<QueryResult>
    suspend fun preview(commands: List<PlannerCommand>): PreviewResult
    suspend fun commit(proposalId: String): CommitResult
    fun discard(proposalId: String)
    fun stageCourseImport(term: TermSpec, meetings: List<CourseMeeting>): String
    fun discardCourseImport(draftId: String)
}
```

- `PlannerQuery`、`PlannerCommand` 为封闭类型，随里程碑加入真实操作。Compose 订阅 Flow，对话只取一次。UI、导入器、LLM 不直接拿 DAO。明确的手动点击可在内部连续预览/提交，无额外确认框；模型**没有 `commit` 工具**。需选文件、登录、授权等动作返回 `UserActionRequired`，不可报成已完成。
- `preview` 在同一读快照中按顺序校验整批命令，分配批内新增实体的 ID，算出明确增删改和 `baseRevision`。已声明的批内唯一别名可供后续命令引用；未声明、重复或歧义则拒绝。不可变提案及差异只留本机内存，不写业务表。
- M3 两个 `stage/discardCourseImport` 草稿方法只接收本人已核对的课次，在设备内存中临时登记；原始 JSON、网页登录数据不进 `PlannerApi`。预览取得独立差异后即释放输入草稿，未确认不改 Room。
- `commit` 只消费本机生成的对应提案。Room 写事务复核全局业务修订号，仅落下已预览的变更，修订号加一。过期返回 `STALE_PREVIEW`，须重新预览；不再询问模型决定写什么。重复点不能生第二份，取消/进程死亡丢弃未确认提案，中途失败整批回滚。校验只写一处。
- 非敏感外观与恢复偏好放在同库一行，保证与日程混合写入时仍能原子提交；密钥不进 Room。应用私有空间持久化，日程、登录数据与密钥不进系统云备份。Room 升级写迁移，不销毁重建。自行实现的是本项目的归属/快照、命令语义和交互连接；画布、主题、布局、解析依据与材质均有下列参考。

## M1 工作台

**一份任务，一个当前归属，一份可回看的现场。** `Task` 有稳定 ID、标题、备注、预计分钟、归属、位置；归属只有 `QUEUE / FRAME / DONE / REMOVED` 之一，仅 `QUEUE` 有队列序号，仅 `FRAME` 有当前帧 ID。`Frame` 记原因、保存时间、来源；`FrameItem` 记帧 ID、原位置、任务 ID、当时的标题/备注/时长以及当时已有的排程起止。历史项只读，不随任务编辑回写。删除用移出标记，不级联删帧历史；只有任务仍归该帧时才可恢复。

工作台分「队列 / 栈」。队列可新增、编辑、完成、移出、拖排/菜单移动；显示未完成数及预计总时长。队首是当前优先项，不强制计时；显示排程也不按时间重排队列。标题非空，预计时长为正，未填默认 30 分钟。手柄借 [Reorderable 3.1.0](https://github.com/Calvin-LL/Reorderable) 的稳定 key、拖动、边缘滚动，结束才提交新次序。栈按保存时间倒序，最新可恢复帧优先；原因作标题，显示保存时刻、原始项目和剩余数。取空的帧仍可看历史，也可在栈内直接新建一帧事件清单。

- **压栈：**默认选整个未完成队列，显式多选只压所选。原因必填且非空。按队列原序在同一事务建帧、写快照、移动归属，保留其余项相对顺序；空集合、空原因、选项失效都不建帧。不复制任务 ID；再次压入形成另一帧。
- **恢复：**手动「全部恢复」一次执行；「选择恢复」按帧内源序而非勾选顺序。已取走/完成/移出的项不复活、不重复。操作与预览均说明实际策略。空帧即使连点也不触发切换。

|本机偏好|全部恢复帧 `[A,B]` 到当前 `[X]`|选择恢复|
|---|---|---|
|`PREPEND`（默认）|`[A,B,X]`|所选按原序插队首。|
|`APPEND`|`[X,A,B]`|所选按原序加队尾。|
|`SWAP`（整帧切换）|`[A,B]`；先把 X 存为原因「切换栈帧前自动保存」的新帧。|始终插队首；只选 B 得 `[B,X]`，不另存 X。|

只有「全部恢复」且策略为 `SWAP` 且当前队列非空，才自动保存眼前队列，与恢复同一事务；失败/取消不改队列、两帧或次序。**占位独立于工作归属**：压栈保留预约，轴上注明「已压栈 · 仍占用时段」；恢复不复制、不改时，旧快照不覆盖用户后来的安排。

## M2 今天

完整导航为「今天 / 工作台 / 流程图 / 课表」，设置从顶栏进；只展示已交付目的地。今天默认设备所在日期，顶部切换日期并显示当前时刻线。底部可收起的**非模态**待排队列托盘与单日轴同时可见；待排指无时段的在队任务。参考 [Structured](https://structured.app/visual-planning) 的任务/日历同轴和 [Planner Hours](https://github.com/baslawson/Planner#features) 的移动、调整时长；不移植 GPL 应用代码。无须跨目的地拖动或引入月历框架。

- Compose 可滚动刻度接[原生拖动手势](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling)。长按拖动，边缘滚动；指针换算为含滚动偏移的轴内容坐标，按 5 分钟就近吸附。预览半透明落点、起止与冲突。松手才落库；轴外、取消、目标失效不改原数据。「安排时间」打开同一日期/时间/时长编辑器，无需拖动也可完成全部安排。
- `TimeBlock(taskId UNIQUE, startInstant, endInstant)` 引用原任务。长按标题拖动整段；长按任务旁竖条，松开后直接拖下端点，只改结束时间，起点不动。交互参考 [Google 日历的底部圆形时长手柄](https://support.google.com/calendar/answer/72143?co=GENIE.Platform%3DAndroid)和 [FullCalendar 的触摸选择](https://fullcalendar.io/docs/touch)。课程、完成项和没有真实结束点的续段不提供手柄。取消只释放时段；移出任务释放占位，完成任务保留历史块。
- 用当地午夜到次日午夜换算一天，不假定恒为 24 小时。跨日按日裁剪显示，底层仍一条；课程按学期换算到同一时间基准。冲突按半开区间 `[start,end)`，移动排除自身。撞已有预约或固定课程须指出具体冲突并保留原状，不推走别项。课程导入撞上既有块则预览、标记双项，不抹掉用户安排。

已实现 `PlannerQuery.Day(date, zone)`：一次事务读出同修订号的待排队列与当日相交的原始时段。`SlotSet(taskId, start, durationMinutes, expectedRevision)` 新增或移动，`SlotClear` 只释放占位；拖动读到的旧修订不可覆写新日程。两条界面路径共享 `preview/commit`。编辑器标明时区，拒绝不存在的当地时刻；秋季同一时刻出现两次时可选 UTC 偏移。Room v2 只新增占位表和索引，没有新增运行依赖。

## M3 华科课表

编译期登记 `HustCourseImporter`（插件 ID `hust-hub`）。`CourseImporter.parse(rows: JsonArray, term: TermSpec): ImportDraft` 是纯 Kotlin，无网络、DAO 或密码。只支持两条已知输入，两路共用解析/预览：

1. 「从 HUB 网页提取课表」在手机内打开[校方登录页](https://hubs.hust.edu.cn/login)。本人完成认证后点「总课表」，选学期，点「提取」进入学期确认，无需电脑或中间文件。`app/src/main/assets/importers/hust-hub.js` 的 Vue 父链查找依据 `../WadwaitaUp/browser.py:37–62`。
2. Android 系统文件选择器选本人导出的原始周数组 JSON；格式依据 `../WadwaitaUp/tools/hust_converter.py:6–16,84–100`。不架中转服务，不承诺兼容未核实格式。

**独立浏览。** 「用 Chrome 浏览 HUB」通过 [AndroidX Browser 1.10.0](https://developer.android.com/jetpack/androidx/releases/browser#1.10.0)（Apache-2.0）打开真正的 [Chrome Custom Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-get-started)，使用 Chrome 自己的登录态、证书策略与导航。只打开固定的登录入口，不传正在使用的认证 URL。Chrome 不向宿主开放校方 DOM 或 Cookie；这个入口用于浏览，不是提取器。

**浏览与提取分开。** 手机提取器用 [Android System WebView](https://developer.android.com/reference/android/webkit/WebView)。HTTP/HTTPS 页面及跨域验证交给 Chromium，不设校方域名白名单，不改写重定向。HTTP 是明文，App 不会把它变成安全连接。第三方登录 Cookie 仅限该会话；保留系统证书验证，不注册原生 JS bridge。只有当前显示的页面是 [HUB 总课表](https://hubs.hust.edu.cn/basicInformation/scheduleInformation/index) 且用户点提取，才运行 APK 内脚本；只取周次、日期、星期、课名、节次、教室。导航或换学期会使未完成的提取失效。

退出提取器清理其 WebView 站点数据，不清 Chrome 数据，也不代替校方登出。给用户安装的包用 `./gradlew :app:assemblePreview` 构建，路径为 `app/build/outputs/apk/preview/app-preview.apk`，不可调试、与上一版同签名。`debug` 包只用于开发。

**从原始行到实际上课日期。** `ZC` 为周序，周集合去重排序。按周保留课名 `KCMC`、星期、节次 `JC`、教室 `JSMC`；仅完全相同条目去重，同课换教室不能并掉。`JC` 完整匹配单节或数字区间，支持 `-`、`–`、`—`；倒序、越界、「实践」均保留原文待修正，不默认为第一节。`KS` 只是第一周日期候选。用户确认学期标识、首周周一、总周数和夏/冬作息，可指定中途切换作息的生效日，不猜当年校历。按 `Asia/Shanghai` 实际上课日期套用 `../WadwaitaUp/models.py:33–62` 的两套 12 节预设；来源没有的教师等字段留空。

**写入边界。** 展示差异、有效/待修正数、同名不同地点和冲突；有问题的条目须修正或明确排除。无课或无有效数据不得覆盖已有课表。确认后仅替换所选学期 `hust-hub` 来源的课程集合；相同内容重复导入无新增，不动用户任务、其他来源或学期。**不移植** `../WadwaitaUp/window.py:3155–3188` 的 `_apply_imported_courses` 按课名跳过/覆盖；同名不是身份。移除某学期课表也须预览，不删除工作台内容。

**离线录课。** 入口和字段分组参考 [WakeUp 官方录课说明](https://wakeup.fun/doc/add_course.html)。选择已有学期或填写新校历，填写课名、可选地点、上课周、星期和起止节次。预览列出实际课次，保存后同步显示在周表与当天时间轴；点手动课格可编辑或移除这一组课次。来源 `manual` 独立于 `hust-hub`，同名同地点也不相互覆盖。助理 `manual_course_add` 须先获得课程详情授权，跨周／学期还需相应范围；本机核对提案后才能写入。

单次导入预览先计数，再生成冲突明细。超过 256 对受影响的重叠时，提示检查源文件，不截断课次后提交；课名和教室各限 256 个 UTF-16 单元，超长进入可修正条目。日/周视图中一门课超过 8 个冲突对象时显示确切总数，避免把大量课程两两展开。

**阅读与失败路径。** 周视图按 [WakeUp](https://www.wakeup.fun/images/mobile3.png) 的「七日列 × 实际节次」排列；课块使用同组明亮柔和的底色，课名、地点和冲突标记统一白字。按学期内的课程名分色，同课跨周/换教室不变。冲突并列，点课看教室、节次、实际起止及冲突；点日期或「课表选项 → 课程列表」提供等价入口。横向切相邻周，纵向滚节次，周次胶囊可直接跳周。切学期、导入和移除集中在课表选项。无课表时胶囊直接提供导入。当天实例仍是时间轴的锁定块。登录、加载或校园网失败不覆盖原课表，文件通过不代表在线登录通过。

**许可。** 移植部分随附 `../WadwaitaUp/LICENSE` 完整 MIT 文本及 `Copyright (c) 2024–2026 Nakanomk`；不携带个人样本。

## M4 流程图

节点是计划模板，不是已有队列任务。存图、节点（标题、备注、预计时长、创建顺序、画布位置）和有向边（前置 → 后继）。画布可新建、改名、拖动、连线、删边/节点、平移缩放；手机另有点选两节点连线和可朗读依赖列表。删节点一起删关联边，不反向改写已生成任务与帧。

- 画布使用 [KotlinFlow 1.0.1 / MIT](https://repo.maven.apache.org/maven2/io/github/joaonart/kotlinflow/1.0.1/) 绘制有向边并处理平移、双指缩放与全图适配；不引示例皮肤、不当数据库。该版本内建节点拖动把取消和松手交给同一回调，`onConnect` 不派发；节点移动、箭头拖到后继的命中及取消由应用适配。库用物理像素，节点位置按 dp 保存。领域层拒绝自环、重复边、缺失/跨图端点、会成环的边；说明具体冲突，不留半条边或部分生成物。
- 选一个目标，取其**传递前驱和自身**的诱导子图，稳定拓扑排序；并列就绪节点按创建顺序，再按 ID，不用坐标或画布排版决定优先级。用 [JGraphT Core](https://jgrapht.org/) `org.jgrapht:jgrapht-core:1.5.3`（上游双许可，按 EPL-2.0 保留许可与来源）：`DirectedAcyclicGraph.addEdge/getAncestors`、包含目标的子图、带显式比较器的 [`TopologicalOrderIterator`](https://jgrapht.org/javadoc/org.jgrapht.core/org/jgrapht/traverse/TopologicalOrderIterator.html)。拖节点只保存布局。
- 「生成拓扑排序」打开同一张操作单：使用已选节点，或自动选择唯一终点；多个终点可手选。选目标即展示依次要做的事，不写库。点「加入队列」一次按序追加新任务，不动现有队列和帧；「存为栈帧」一次生成有序快照，不动当前队列。帧原因默认「为目标〈标题〉生成」，可修改。两者均校验用户看到的修订，数据变了才要求重新核对；重复点击不重复写入。
- 画布右下角「一键整理」按最长前置链从左到右分层，同层保持原序，未连线节点另排。沿每条边前置都在后继左侧；按节点实际高度留白。一次只提交坐标并适配全图，不动依赖、目标生成顺序、队列或历史帧。重复整理同一布局不增加修订。

## M5 对话

每个业务页底部直接输入并用纸飞机发送。四个根页共用一个输入框：切目的地、自然日或教学周不会销毁正在编辑的草稿；非空草稿保留最初的发送范围，换页时有范围标记。键入或切页不联网，点发送才打开本机授权页；用户允许后才发送。左右气泡区分本人/助理，圆形 `!` 按钮收起蓝色提示、黄色警告、红色错误说明；服务地址、授权和待应用差异无需展开即可核对。原始课表、HUB 登录与密钥页无助理。

**连接。** 用 OkHttp `5.5.1` + `kotlinx.serialization`。用户提供含 API 前缀的 HTTPS base URL、模型、Key；在 base URL 后追加 `chat/completions`。使用 [OpenAI 兼容 function tools](https://platform.openai.com/docs/guides/function-calling) 和 `tool_calls`，不从散文猜命令；模型必须支持这套协议，只支持 Responses 的不冒充兼容。用 [Android Keystore](https://developer.android.com/privacy-and-security/keystore) 中不可导出的 AES-GCM 密钥加密 BYOK，每次写用新 IV，密文放私有 `noBackupFilesDir`。换服务域需重新授权/填 key，不跨域转发鉴权。无账号、无托管中转、无后台自动操作。

运行时 HTTP 为 [OkHttp 5.5.1](https://github.com/lysine-dev/okhttp/tree/parent-5.5.1) 和 [Okio 3.18.1](https://github.com/lysine-dev/okio/tree/parent-3.18.1)，两者发布标签的 Apache-2.0 完整许可一并放在 APK 的 `assets/licenses/OkHttp-Okio-Apache-2.0.txt`。`MockWebServer` 和测试 TLS 证书仅在测试包。

**仅两种模型工具。** `planner_query` 限定类型、范围、字段，可查当前队列、帧/清单、图/目标次序、某天占位、某学期/周课表及普通设置；只读本轮获准范围。`planner_propose` 传一次有序命令数组。下表 `kind` 也是 UI 命令的序列化名。不提供 raw SQL、任意 URL/文件、脚本执行、任意 UI 点击或确认按钮；隐私、连接配置、认证、系统选文件都要显式用户操作。

|领域|写命令与关键输入|
|---|---|
|队列 / 栈|`task_add`（每项一条，可成批）、`task_edit`、`task_finish`、`task_remove`、`queue_order`（当前完整 ID 排列）；`frame_create(reason, tasks)`、`frame_push(reason, taskIds)`、`frame_restore(frameId, mode, taskIds)`。`mode=all/selected`；是否切换队列由本机偏好决定。|
|DAG|`graph_create`、`graph_patch`（按 ID 增改删节点、位置、边）、`graph_remove`、`graph_frame(graphId, goalId, reason)`。新实体可声明批内唯一 `ref`；`graph_frame` 还可声明新帧 `ref` 和 `taskRefs`（图节点 → 新任务别名），让「生成 → 选取 → 排程」不猜数据库 ID。|
|日程 / 课表|`slot_set(taskId, start, durationMinutes)` 新排、移动或调整结束，开始须带 offset；`slot_clear(taskId)`。`manual_course_add` 按学期、周集合与节次提议手动课程；`course_import(draftId)` 必须使用用户核对的本地草稿；`term_remove(termId)` 仅移除 HUB 来源。|
|外观 / 偏好|`prefs_set`、`prefs_reset` 只接受已公开的外观/交互字段与合法值；重设只动所选组。配置凭据或扩大披露范围不能走模型授权捷径。|

**数据披露。** 首次联网及扩大范围前展示要发往哪个域、哪些字段。默认只有当前范围的任务标题、顺序、时段和必要 ID；课表默认只给忙碌时段。备注、压栈原因、课程名/地点须用户明确纳入才出设备。整库、原始课表、Cookie 永不发送；BYOK 仅作绑定服务的鉴权头，不进提示词/工具结果。课程名、备注和模型输出都是不可信文本，不是权限指令。对话限会话内，日志不落原文；关闭/取消终止在途请求。

**谁能提交。** 纯查询直接答。每轮所有写操作合为**一张差异卡**，列项目名、顺序、旧 → 新时间、实际生成的节点/边/帧或受影响设置。模型没有 `commit` 工具；只有用户点一次「应用这些更改」，手机才消费对应本地提案，收到事务回执才说「已应用」。校验失败、重名歧义、空结果、协议不兼容、401、限流、超时、取消均给具体反馈，不写业务项。每条消息最多四次只读往返，写提案返回即停；不运行自主后台 Agent。

**使用。** 「设置 → 模型服务配置」可选 [DeepSeek](https://api-docs.deepseek.com/guides/tool_calls/)、[Claude](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)、[GPT](https://developers.openai.com/api/docs/models/gpt-5.4-mini) 或自定义。预设填入官方 API 地址和可编辑的模型，用户填写 API Key，不是登录聊天软件账号。选择和保存均不联网，切换服务不携带未保存的密钥。每次最多接受一个工具调用；跨页查询须扩权。课程来源页只能问已保存数据；仅核对完成的差异页可在发送时生成本地草稿 ID。失效提案须重新预览并确认。

## 设计与外观

基线是 Android 标准 Material 3。字形走平台 sans-serif 与中文回退，不分发苹方或按品牌模拟字形。页名使用 `titleLarge`（22/28sp），普通列表标题 `bodyLarge`（16/24sp），次要信息 `bodyMedium`（14/20sp），导航与时间刻度使用标签角色（12/16sp）。是否强调由信息角色决定，不把普通任务当成展示大标题。使用标准 motion scheme；减少动效仍然生效。

- 四个根页面在同一横向分页容器中，导航与助理位于容器外。默认浮动导航，支持改为贴底；键盘出现时收起主导航。根页不重复页名，顶栏中间胶囊放日期、队列/栈、流程图创建或教学周；独立动作用圆形按钮。右下加号属于页面。手动操作不依赖模型。
- 设置首页按外观、日程助理和工作台恢复策略分组。「色彩」「布局」「材质与动态」各有独立目的地，只显示、保存或恢复本页字段。色彩支持随系统/浅/深、Android 12+ 动态色或种子（默认 `#435D92`）；使用 [MaterialKolor 6.0.0](https://github.com/jordond/MaterialKolor/releases/tag/6.0.0) 生成 `SPEC_2025` 颜色角色。
- 支持标准/紧凑留白、主导航次序/启动页、任务时长/备注、贴底/浮动、材质总开关和导航/助理分项、减弱动效、减少透明度、增强对比及系统触感。默认舒适、今天、动态色、玻璃开启。旧版自定义配置中省略的 `glass=false` 保持实色；未配置的 `{}` 采用新默认。所有新写入显式保留关闭选择。三种恢复策略不受任何外观重置影响。
- 首发图标为原创 fumo 颜文字 **`ᗜˬᗜ`**：两只对称方眼和低位短弧嘴，用独立矢量笔画，不依赖字库、商业布偶照片，不强画天子肖像。按 [Android Adaptive Icon](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) 做 108×108 dp 分层，识别区在中心 66×66 dp，彩色前景/背景及 monochrome 层共用轮廓，不预烘启动器圆角/阴影。空队列小标沿用同一张脸，不用颜文字代替操作标签。

设置草稿只存在于编辑页；退出不写库，保存使用 `PlannerApi` 的同一预览/提交事务。持久化契约是 `planner/Appearance.kt`，Room 4→5 为原设置行加入缺省 `{}` 的外观字段，不迁移队列、快照、时段或模型凭据。主题/平台能力、局部绘制和设置编辑分别在 `StackLifeTheme.kt`、`appearance/`；业务页只读取对应偏好。以后替换导航及页面布局不需要更改排程或恢复规则。

**交互参考与借用边界：**

|来源|具体借用点|
|---|---|
|[Now in Android 字级](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/theme/Type.kt) / [顶栏](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/component/TopAppBar.kt) / [标签](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/component/Tabs.kt)|只借控件角色：单行应用栏和图标动作、下划线标签、单选整行。字体采用 [BiliPai 的标准 MD3 档](https://github.com/jay3-yy/BiliPai/blob/796a876eee5dabd7e364550422c9a9ecbc1cc06d/design-system/src/main/java/com/android/purebilibili/core/theme/Type.kt)，不取已废弃的 iOS 字阶。|
|[PiliPlus 私信编辑器](https://github.com/bggRGjQaUbCoE/PiliPlus/blob/217fb54f51f6764176319bbdedab2a7522d75401/lib/pages/whisper_detail/view.dart#L267-L375) / [设置列表](https://github.com/bggRGjQaUbCoE/PiliPlus/blob/217fb54f51f6764176319bbdedab2a7522d75401/lib/pages/whisper_settings/widgets/item.dart) / [Tasks.org 预计时长行](https://github.com/tasks/tasks/blob/main/app/src/main/java/org/tasks/compose/edit/TimerRow.kt)|底部直接编辑、尾部纸飞机；设置行显示当前值；任务时长按需编辑，不常驻第三个大输入框。三者只借交互，不复制源码。|
|[BiliPai 底栏材质](https://github.com/jay3-yy/BiliPai/blob/796a876eee5dabd7e364550422c9a9ecbc1cc06d/app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarGlassMaterialPolicy.kt) / [调校](https://github.com/jay3-yy/BiliPai/blob/796a876eee5dabd7e364550422c9a9ecbc1cc06d/app/src/main/java/com/android/purebilibili/feature/home/components/LiquidGlassTuning.kt)|一个真实内容采样源与胶囊同级，主题底色着色、无外壳色散、边缘折射。StackLife 不移植视频界面的发光、按压膨胀、shader 或资源。|
|[BiliPai 外观](https://github.com/jay3-yy/BiliPai/wiki/Guide-Appearance) / [设置实现](https://github.com/jay3-yy/BiliPai/blob/main/app/src/main/java/com/android/purebilibili/feature/settings/screen/AnimationSettingsScreen.kt) / [材质实现](https://github.com/jay3-yy/BiliPai/blob/main/app/src/main/java/com/android/purebilibili/core/ui/popup/AppGlassSurfaceMaterial.kt) / [LiquidGlassSettingsStore](https://github.com/jay3-yy/BiliPai/blob/main/app/src/main/java/com/android/purebilibili/core/store/home/LiquidGlassSettingsStore.kt)|总开关 + 场景开关 + 能力门控，实色与透明分离、偏好与渲染分离。Kotlin/Compose、GPLv3；不复制代码、shader、资源、存储实现，不照抄 beta BOM/snapshot 依赖。|
|[BiliPai 悬浮分段](https://github.com/jay3-yy/BiliPai/blob/796a876eee5dabd7e364550422c9a9ecbc1cc06d/app/src/main/java/com/android/purebilibili/feature/home/components/HomeTopTabFloatingDock.kt#L64-L143) / [底搜栏对齐](https://github.com/jay3-yy/BiliPai/blob/796a876eee5dabd7e364550422c9a9ecbc1cc06d/app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarSearchContentLayout.kt) / [分类设置](https://github.com/jay3-yy/BiliPai/blob/5893c2c80739e3759ac64ae5f232cf03a94da7f8/app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt#L1186-L1221)|工作台悬浮分段、选中指示器、底部搜索与导航对齐、设置分类独立进出。|
|Apple [Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/) / [工具栏](https://developer.apple.com/design/human-interface-guidelines/toolbars) / Android [HorizontalPager](https://developer.android.com/develop/ui/compose/layouts/pager)|工具与内容分层、渐淡磨砂滚动边缘；页间位移由真实分页位置驱动。实色与光学档不替换输入或按钮。|
|[WakeUp 周表实物](https://www.wakeup.fun/images/mobile3.png) / [周视图实现](https://github.com/YZune/WakeUpSchedule/blob/5d439287a22a64b36d14c08c81bd9fc7bb9a53db/app/src/main/java/com/suda/yzune/wakeupschedule/view/fragment/ScheduleFragment.java#L224-L266) / [Etar 日手势](https://github.com/Etar-Group/Etar-Calendar/blob/6fb46d1606622aecc719b7e5dc15a48528768c94/app/src/main/java/com/android/calendar/DayView.java#L4184-L4202)|七日列、跨节课程色块、点击详情；横向翻页和纵向滚动分开。不搬源码、配色或截图；课程局部发光是本项目要求。|
|[Telegram 消息左右对齐](https://github.com/TelegramMessenger/Telegram-Android/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L19055-L19067) / [按对话保存草稿](https://github.com/TelegramMessenger/Telegram-Android/blob/21f7d109da6ab3506c9e9577f98c6a38771ab335/TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java#L7916-L7922) / [GitHub Alerts](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax#alerts)|左右短气泡、固定输入栏、草稿不改投；提示分级且有可读文字。无云端草稿，不复制代码或图标。|
|[GitHub Actions 手动执行](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow#running-a-workflow)|同一处选择输入和执行一次；这里只借交互，拓扑和本地持久化沿用 JGraphT 与 PlannerApi。|
|[Breezy Weather](https://github.com/breezy-weather/breezy-weather) / [Auxio](https://github.com/OxygenCobalt/Auxio)|今天页的概要/细节排版；当前任务与队列之间的入口（Auxio 是原生 Views，不是 Compose）。|
|[Metrolist](https://github.com/MetrolistGroup/Metrolist) / [Mihon](https://github.com/mihonapp/mihon)|助理从收起到展开的层次；队列手柄及移动到顶/底。|
|Flutter [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) / [LocalSend](https://github.com/localsend/localsend)|中文标签分组、多选排序；单一目的操作。只借信息结构，不搬旧视觉、播放器架构或插画。|

**时间轴与底栏。** [Structured 日视图](https://help.structured.app/en/articles/380546)在同一竖轴左侧标时刻、右侧读事件；StackLife 保留比例空白供五分钟拖放。参考 [Etar 的当前时刻线](https://github.com/Etar-Group/Etar-Calendar/blob/6fb46d1606622aecc719b7e5dc15a48528768c94/app/src/main/java/com/android/calendar/DayView.java#L2425-L2440)，分钟线从轴起画，不用标签覆盖整点；刻度列按系统字号实测文本宽度。[Etar 的 FAB](https://github.com/Etar-Group/Etar-Calendar/blob/6fb46d1606622aecc719b7e5dc15a48528768c94/app/src/main/res/layout/all_in_one_material.xml) 对应今天页轴右下、托盘上方的加号；工作台加号在任务列表右下，末项可滚到按钮上方。导航和工具栏矢量取自 [Google Material Design Icons](https://github.com/google/material-design-icons)，Apache-2.0 许可随 APK 分发。


## 动效与材质

**应用内连续性（从 M1 起）。** 按稳定实体 ID 做 [Compose shared bounds / shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements)：事件卡片 → 详情、栈帧 → 帧详情，同一对象容器转换。源不在/被过滤则淡出，不飞向占同一位置的另一张卡片。[Navigation 3 预测返回](https://developer.android.com/guide/navigation/navigation-3/animate-destinations) 随手势可取消，保留列表位置，根页不拦系统返回桌面。动画不触发业务写入。系统动画比例为 0 或选择「减弱」时直接切换/简短淡变，停弹性和装饰循环。

**联盟系统能力（M6 单列验收）。** 依据 [ITGSA SharedViewDemo](https://gitee.com/itgsa_sharedviewkit/SharedViewDemo)。`gsai.sdk:sharedview-kit` 运行时调用 `ViewAnimationManager.isViewAnimationSupported()`，不按品牌猜。仅在协议支持、动效允许、源卡片完整可见时，对队列事件/栈帧卡片用非导出 `EntityDetailActivity`，复用同一详情 Composable，Intent 只传实体类型和 ID。用 `PixelCopy` 取被点卡片的 window 矩形，不把整屏 `LocalView` 当卡片。按 Kit 能力约定区分屏幕/窗口坐标、转换 bounds，圆角转 px，交给 `makeViewAnimationOptions(bitmap, bounds, radius, …)`；成功只让系统播一次。返回按实体 key 刷新落点，在详情 Activity 的 `finish()` **之前** `overrideViewAnimationTransition`；源丢失跟随系统。能力为 0、options 为空或取图失败，走同一应用内详情路径，不叠动画、不阻断导航。

依赖候选为[官方 HTTPS 固定提交的 1.0.0.5 AAR](https://gitee.com/itgsa_sharedviewkit/SharedViewDemo/tree/b449a426a340f1827d942592bddf0a416eae3ac4/app/libs)；核对来源/授权，记录并校验 SHA-256，不用示例的 HTTP 内测 Maven 或动态 `+`。[NOTICE](https://gitee.com/itgsa_sharedviewkit/SharedViewDemo/blob/master/NOTICE) 的 MIT 只覆盖示例，SDK 授权另计。**正式分发许可和真实支持设备均未核实。** 许可明确后才以单一发行包按能力分支；此前不得分发含 SDK 的包，普通版只含真实 Compose 转场。联盟验收仍受阻，模拟器截图不能证明系统 OPEN/BACK，更不能称已支持或已获金标认证。

本地实验变体只接受 `-PstacklifeKitAar=/绝对路径/sharedview-kit-1.0.0.5.aar`，SHA-256 必须为 `7c0937ecde545a262619e5fa19d56a21b0ad3fd8013cbc4fe058c497808702bd`。`./gradlew -PstacklifeKitAar=… :app:assembleKitLab` 生成独立 `.kitlab`，只在获准设备本地测试，不交付；`StackLifeKit` 日志仅记协议值。普通 `:app:assemblePreview` 不需要 AAR，也不会打入适配器或详情 Activity。

**可选光学层。** 移动端与 [BiliPai 浮动壳体](https://github.com/jay3-yy/BiliPai/blob/5893c2c80739e3759ac64ae5f232cf03a94da7f8/app/src/main/java/com/android/purebilibili/feature/home/components/FloatingDockChrome.kt)走同一条流程：Miuix `drawBackdrop` 采样 → 调色与模糊 → 透镜 → `drawEdgeStroke` 双光源高光。透镜的普通／色散着色器直接取自 [BiliPai LiquidLens.kt](https://github.com/jay3-yy/BiliPai/blob/5893c2c80739e3759ac64ae5f232cf03a94da7f8/design-system/src/main/java/com/android/purebilibili/core/ui/effect/LiquidLens.kt)（该文件独立标明 Apache-2.0）；[Miuix 边缘着色器](https://github.com/compose-miuix-ui/miuix/blob/d27cbb0b375912cdf42670553fdb564d8ff7fc23/miuix-material/src/commonMain/kotlin/top/yukonga/miuix/kmp/material/EdgeStrokeDrawing.kt)通过固定版本 `0.9.5-beta02` 调用，不重画轮廓。壳体只采样后方内容；嵌套控件读取父级材质而非其文字，输入条额外磨砂、不叠高光。顶栏、导航、加号、弹层和课格共用材质入口；不采样登录网页、模型密钥或地址输入区。

课格的有色透镜采样真实节次线与按日底色，最后再画白字；没有预画的内圈或光晕。材质页使用与周视图相同的 `PeriodGrid` / `CourseTile` 预览课程文字描边。关闭玻璃保留 WakeUp 配色、形状、位置和点击区。外观编辑沿用 [Apple Sheets 的取消／完成位置](https://developer.apple.com/design/human-interface-guidelines/sheets)：取消和保存位于顶栏，「恢复默认设置」居中，只重置当前分组的草稿。

玻璃仅在 API 33+、硬件加速、非低内存/省电、无减少透明度/高对比要求时可用。否则提示「当前使用实色」及原因，不把模糊称为折射；关玻璃完全卸下光学层。按 [Google 无障碍标准](https://developer.android.com/guide/topics/ui/accessibility/apps)，触控目标至少 48×48 dp，普通文字对比 4.5:1、大字 3:1；TalkBack 读状态并有等价非拖拽操作。系统字体缩放、edge-to-edge 安全区、输入法和旋转都不能遮操作。减少动态与减少透明度分开处理。

## 研发分工与交接

这些是**开发职责**，不是手机里运行的 Agent 群。根 [AGENTS.md](../AGENTS.md) 是短入口；任务契约放 Issue/当前会话，只写一个结果的目标、参考及许可、写集与依赖、不改什么、输入 → 可观察结果。

|角色|职责与交接物|
|---|---|
|负责人 / 集成 Agent|给验收输入和预期，冻结接口、写集；维护公共构建、schema/迁移、根导航和契约。集成后对实际证据负责；小改可兼任实现。|
|研究 / 交互 Agent（按需、只读）|仅在新视觉、平台 API、外部格式需要时介入，给一手链接/本机位置、借用点、许可和关键帧/失败路径，不另造架构或重开已批设计。|
|实现 Agent|限域交付数据 → 命令 → 界面 → 相关用例的纵向切片，沿用已选组件；共享契约冲突交回负责人。给可复现输入与未验项，不自行宣布验收。|
|独立验收 Agent（只读）|先看漏做/多做，再看质量与运行证据；UI 看截图，动效看录屏，数据看事务后与重启状态。只给「通过 / 需修复 / 证据不足」，不验收自己的实现。|

```mermaid
flowchart LR
    G[选一个可验证目标] --> C[冻结契约与所有权]
    G --> R[必要时查来源或做关键帧]
    R --> C
    C --> I[实现纵向切片]
    I --> V[负责人集成并取证]
    V --> Q{独立验收}
    Q -->|具体偏差| I
    Q -->|符合目标| D[交付并选择下一目标]
```

默认单实现者。接口冻结、切片独立且写集不相交、确能减少工作才并行，常规最多两位；如 M2 轴和 M4 画布可分包，但两方不同时改 Room 迁移/根导航。按本项目、官方组件、授权库、最小适配的顺序找实现；新依赖/模块/抽象须对应当下缺口。禁止假数据、假回复、假成功与为过检查弱化断言。负责人收集最窄真实证据放 PR/会话，不提交私密样本，不建每轮目录或例会流水线。偏差以「要求 → 实际 → 偏差」退回实现者，同边界反复失败先修契约；缺条件只列该项未验。只在需决策的阻塞、验收点或完成时通知，不自动发布/合并；只为会复发的实际错误增补规则。

**依据及归属：**[Lauren Tan 署名的 xAI Engineering Lead 模板](https://x.ai/bot/marketplace/bots/engineering-lead)借的是沿用上下文、负责人跟到结果（派发不等于完成），不是她规定了以上角色。[xAI 工程实践](https://x.ai/bot/guides/grok-bot-for-engineering)作者是 Lingxi Li，借其证据回流。另参考 [Codex 的 AGENTS.md 约定](https://developers.openai.com/codex/guides/agents-md)作短入口、[GitHub Spec Kit 项目规则](https://github.com/github/spec-kit/blob/main/AGENTS.md)作规格 → 行为正反例、[Superpowers reviewer 契约](https://github.com/obra/superpowers/blob/main/skills/subagent-driven-development/task-reviewer-prompt.md)作先符合性后质量的验收顺序。[Android 官方 skills](https://github.com/android/skills)按具体 Compose/性能疑难查阅，不引入整套编排 CLI。

## 验收方法

**M0 不需要 Gradle。** 从根规则入口应能找到华科提取代码与许可，推演帧 `[A,B]` 恢复到 `[X]` 的结果，指出模型提案后谁能提交，以及解释为什么模拟器截图不能验联盟能力或证明金标认证。两文件可独立阅读、未把未实现项标为完成，即达到本轮目标。

**开发环境。** 安装 **Android SDK Platform 37.2**（不只是 `build-tools`）；工程 DSL 用 `compileSdk { version = release(37) { minorApiLevel = 2 } }`。启动专用 API 37 手机 AVD 或连接获授权的测试手机，`adb devices -l` 须见 `device`；不用个人主账号/日程做破坏性实验。项目已带校验分发包的 Gradle wrapper，在根目录执行：

```bash
export ANDROID_HOME="$HOME/Android/Sdk" JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:installDebug
adb shell am start -n io.github.nakanomk.stacklife/.MainActivity
```

**M1 复现：** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.nakanomk.stacklife.acceptance.WorkspaceTest,io.github.nakanomk.stacklife.acceptance.WorkspaceUiTest`。每个用例使用独立的文件型 Room 数据库；Compose 测试运行同一工作台和导航。数据库基线在 `app/schemas/`，后续变动须保留迁移路径。APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

M1 首验在 Android 17 / API 37 x86_64 专用 AVD 上通过 12 项仪器测试，涵盖三种策略、过期/重复提案、第二条快照插入失败的回滚、部分恢复和拖排/菜单移动。保存中重建 Activity 的用例锁住真实 Room 写事务：表单只恢复请求 ID，续等 ViewModel 的原回执，放锁后只创建一项并退出；若进程丢失回执则提示核对，不重发命令。

安装包另经实际界面压栈、旋转和 `am force-stop` 冷启动：队列仍为 `[A,B,X]`，历史帧保留两项、剩余零项；取消系统返回手势仍在原帧详情，完成手势才回到栈。以上不代证实体机性能、TalkBack 实机体验或联盟能力。后续阶段的纯 Kotlin 用例可用 `./gradlew :app:testDebugUnitTest --tests '*GoalOrderTest'` 等按类选择，不预造空测试。

**M2 复现：** 在专用 API 37 设备运行 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.nakanomk.stacklife.acceptance.DaySlotTest,io.github.nakanomk.stacklife.acceptance.DayTimelineTest`。前者覆盖事务、所有权、重开和当地日边界；后者向真实界面发送长按、移动、松开、取消及无障碍点击事件。连同 M1 回归共 **21 项通过**。其中一项在手指静止时推进帧时钟，检查边缘滚动改变候选时段；并发改动后松手不得写入。

**升级与无障碍实测：** 在 Android 17 x86_64 测试 AVD 保留 M1 安装数据，以 `adb install -r` 覆盖启动 v0.2.0；数据库版本 1→2。原四张业务表导出的 SQL 逐字节相同（5 个任务、3 个帧、7 条快照、修订 14），新占位表为空。在另一台 Google Play API 37 AVD 启用系统 TalkBack 17.0，实际触摸聚焦并双击「安排时间」，键盘输入 15:45；同一个队列 ID 经 16:00 改时、取消后仍在原位。会话附两条操作录屏；未评估语音播报质量或实体机性能。

**M3 复现：** `./gradlew :app:testDebugUnitTest --tests '*HustImportTest'`（7 项）；专用 API 37 设备运行 `./gradlew :app:connectedDebugAndroidTest`（含 M1/M2 回归共 33 项）。`CourseConfirmationTest` 在 Room 写事务被占用时确认导入/移除并重建 Activity，两次操作分别只写一次；`CourseConflictBoundsTest` 检查密集冲突拒绝时不改库。设备上从系统选择器导入 [`hust-week-acceptance.json`](../app/src/androidTest/assets/hust-week-acceptance.json)，明确排除 `实践`，确认前学期/课次为 0/0，确认后为 1/3，再导入仍三次。冬令当天拖到 15:00 被拒，同一任务在 15:35 紧接课程成功。v0.2.0 → v0.3.0 原地升级，五张原业务表 SQL 导出逐字节一致，保留 5 个任务、3 个帧和修订 14。

**v0.3.1 定向回归：** `HubLoginNavigationTest` 在真实 WebView 中用本地 HTTPS 响应复现「HUB → 第三方认证页（含另一域的脚本）→ HUB 总课表」，检查第三方不可提取、返回后可提取。`WorkspaceUiTest.addTaskFloatsAboveCenteredNavigationOnTodayAndInTheQueue` 检查圆形加号在胶囊上方且不挤偏导航，并从今天页实际创建一项队列任务。连同投影、Cookie 清理和拖放回归，API 37 设备定向 10 项通过；这些用例不替代本人认证后的在线验收。

**v0.3.2 手机导入：** Android 17 上实际点击「用 Chrome 浏览 HUB」进入 Chrome 145 的 `CustomTabActivity`；两个入口都从 HUB 到达 `pass.hust.edu.cn` 的「您正在登录HUB系统」表单，返回可回到来源选择。未输入校园账号。`HubLoginNavigationTest` 用本机 HTTP 302、跨站验证 Cookie 和用户点击打开的新窗口验证往返；`HubMobileImportTest` 在手机显示合成总课表，通过「总课表 → 提取 → 核对 → 确认」写入一门课，确认前 Room 未变。连同 `HubProjectionTest` 共 5 项设备用例通过；`HustImportTest` 7 项通过。v0.3.1 可覆盖安装。

**M4 复现：** `./gradlew :app:testDebugUnitTest --tests '*GoalOrderTest'` 验稳定排序与非法图；专用 API 37 设备运行 `GraphTransactionTest`、`GraphEditorTest`。事务用例还覆盖同批「建图 → 生成 → 部分恢复 → 排程」、旧修订/重复确认拒绝和最后一次写边失败时整批回滚。v0.3.2 → v0.4.0 原地升级后，七张旧业务表（含任务、帧、快照、占位与课表）的 SQL 导出逐表一致，新增图表为空。手机上可从依赖列表点选前置和后继，也可直接从节点箭头拖到后继；两者写入同一关系。

JVM 上 5 项排序用例、Android 17 上 6 项图相关仪器用例通过。触控覆盖重叠节点的连线落点、取消与过期拖动；确认期间重建 Activity，放开 Room 写锁后只生成一帧，再重建仍回到同一帧。Google Play API 37 AVD 实测空白处平移、双指缩放；启用系统 TalkBack 17.0 后，可聚焦「先做 A，再做 C」并双击选择 D、进入四项目标预览。不据此宣称实体机性能或语音质量。

**M5 复现：** `./gradlew :app:testDebugUnitTest --tests '*ChatTransportTest' --tests '*PlannerToolTest'`：16 项通过，涵盖 HTTPS 路径、取消、拒绝转发密钥、协议、字段投影及范围。Android 17 / API 37 上 `AssistantProposalTest` 6 项、`ConnectionStoreTest` 2 项通过。用仅在测试包中的回环 HTTPS 服务实际经过网络、Keystore 和文件型 Room；确认前三节点两边的计划不存在，确认后仅写一个修订，生成一帧剩两项，查资料保留同一任务 ID 并排到本地 16:00–16:30。另验取消、旧修订、重复确认、401、越权、只改时长保留私密备注，以及核对过的课程草稿导入。

在 Google Play API 37 AVD（1080×2340、440 dpi、字体 1.0）由 v0.4.0 覆盖安装 v0.5.0，打开升级前手建的四边菱形并实际点击「一键整理」，四个节点和全部连线均可见。`GraphEditorTest.arrangeLayersFollowAllEdgesAndKeepExistingFrameAndOrder` 校验缩放后的卡片和连线手柄都在画布内，`GraphTransactionTest.diamondSkipsUnrelatedTemplatesAndNeverRewritesFramesOrQueue` 校验现有图只改依赖时仍以图名展示。未配置服务能返回手动界面；软件键盘弹出时，半屏面板的输入和发送仍可见。以上不代证真实外部模型的协议兼容性或回答质量。

**M6 复现：** 在 Android 17 / API 37 运行 `AppearanceTest`、`AppearanceResourcesTest`。7 项检测覆盖草稿取消与保存、跨组重置和并发覆盖、冷读后的目的地/副标题、横屏双列操作互不遮挡、系统栏预览回退、色彩角色文本对比，以及彩色/单色启动器前景均在安全圆内。`PlannerToolTest` 的 9 项包含设置范围、字段类型和导航排列验证。普通包由 v0.5.0 原地升级后仍能看到已有待排任务；实际强制停止后以所选的深色、贴底导航和工作台冷启。

Google Play API 37 AVD 上，360dp 宽、字体 200%、TalkBack 17.0 实际聚焦并双击右下 `+` 进入任务编辑器；横屏同倍率分别点到时间编辑与新增入口。取消边缘返回手势留在原详情，完成手势才回队列。AOSP API 37 录下同一份 30 项队列实色/折射滚动对照；切省电模式、以 `debug.force_low_ram` 重启测试系统后，均显示具体实色原因。`AppearanceBenchmark` 两档三种操作的 dry run 全部跑通，**不是**固定真机 P95 结论。仅本地 `.kitlab` 在 API 37 AVD 取得实际协议值 `0`，任务往返仍留在 `MainActivity`。协议 `>0` 的双向转场、打断、落点丢失及 SDK 分发许可未验。

**v0.6.1 截图复审。** API 37 上原地升级，使用同一份两项队列对照工作台；另以 30 项队列在同一位置、同一滚动手势录制实色和玻璃。第一版玻璃仍能读出输入条下面一行任务的字，最终取 BiliPai 磨砂档的 10dp 扩散、54% 主题着色，去除锐利背景混合，保留 8dp 边缘折射；复拍后不再出现两层文字。360dp、200% 字号的横竖屏保留独立加号，横屏点待排行能进入时间编辑。1800×2420、440 dpi 窗口实际开关 Gboard 并继续输入，草稿保留，导航随键盘收起和恢复。

本次 24 项 Android 定向用例与 9 项请求/传输 JVM 用例通过，涵盖预设与换站密钥、纸飞机与 IME 授权、差异确认、草稿跨尺寸、工作台和时间轴。截图来自 Android 17 模拟器；无物理设备或真实模型密钥，未据此判断真机帧率或外部模型回答。

**v0.7.0 运行截图复审（Android 17 / API 37 模拟器）。** 在同一份本地导入的两周、六门课程上对照升级前后。1080×2340、440 dpi、系统字体 100%：玻璃/实色的逐个课程边界相同。截取课程名白色字芯与文字区的主要底色，六门课中最低对比度分别为浅色玻璃 5.40:1、浅色实色 6.45:1、深色玻璃 8.51:1、深色实色 7.74:1；标准是 [Android 小字 4.5:1](https://developer.android.com/guide/topics/ui/accessibility/apps)。上滚到底，第 12 节的 22:20 结束时间完整露在输入栏上方。

录屏逐帧可见四个根页平移，草稿和标注不动；设备用例核对焦点、同一输入节点 ID、边界与原日期授权。Tab/Shift+Tab 不进入隐藏页。实际把 `Review → Ship` 追加到已有队列，原有两项保留；菱形去重、重复点击、修订过期、一次存帧、按页恢复外观、日期/周横滑与冲突拖拽另由设备用例验证。本轮分批通过 41 个不同 Android 用例及 13 个 JVM 用例。会话截图来自本地 HTTPS 验收服务；未使用外部模型密钥，录屏不作物理设备帧率结论。

**v0.7.2 课表配色复审。** 八个底色取自 WakeUp 官方商店[浅色白字周视图](https://is1-ssl.mzstatic.com/image/thumb/PurpleSource211/v4/ea/24/ff/ea24ff53-5522-1793-2759-0e5f55df649d/8f020f24-d925-446a-bdba-41ba3eedf0d2_Apple_iPad_Pro_13_Inch_Screenshot_0.png/576x768bb.png)的课块内部，不取壁纸或品牌色。课程超过八门时在相邻取样色之间补色。浅深主题共用色板；玻璃保留底下的明暗，白字只加贴近笔画的细灰边。

在同一 API 37 模拟器、同一学期与周次、100% 字号下，对照了浅深主题的玻璃和实色截图。实色中「数据结构」为杏色 `#F3C383`，「大学物理」为薄荷色 `#8AE8D8`；浅色玻璃中分别为 `#F4C78D`、`#93E9DB`。设备用例验证了三件事：32 门课各有不同颜色且都在参考明度及饱和度范围内；换周、换教室或重启不改变分色；课程名、教室和冲突标记均能渲染出白色笔画和有对比的字边。

**v0.8.0 运行截图复审。** 在同一学期、1080×2340、440 dpi、系统字号 100% 的 API 37 模拟器上，「数据结构」中部的节次线可透过有色课块，切换实色后消失；课程位置不动。将今天的时刻线滚过固定的「＋」，圆内出现随背景移动的折射光带，圆外仍是直线。分别检查了浅色、深色、设置页去除描边和保存后冷启。浅色底配无描边白字的对比较低，保留描边为默认。截图与录屏不代表物理设备的帧率。

**v0.9.0 交互复审（API 37 模拟器）。** 从已有周表实际录入 `Lab / E101 / 周六第 1–2 节 / 第 1、2 周`，核对预览中的两次日期，在课格打开详情并确认移除。将时间轴上的 `09:00–09:30` 从下端点拖到 `09:45`，松手前显示预览，保存后仍从 `09:00` 开始。日／周箭头录屏和中间帧用例检查进入页与退出页接边、导航不动。实录中系统键盘尚有按键可见时悬浮导航已出现，第一次返回后输入框保留 `Hello`；从助理面板系统侧滑回退时底页没有重建。

**材质复审。** 同一周、同一窗口下，玻璃和实色的七个课块边界一致。体育课内的节次分隔线在实色中不可见，玻璃中可见；按住课块后线在块内弯折，块外位置不动，字不随之变形。今天页的时刻线经过「＋」时在圆内偏移，圆外保持笔直。Android 8（API 26）模拟器实际安装并冷启同一签名包，设置明确提示「当前使用实色：折射需要 Android 13 或更高版本」。

|阶段 / 用例|输入 → 可观察结果（校方登录、真实模型、联盟真机和性能有各自的验收条件）|
|---|---|
|M1 `WorkspaceTest`|新建 A、B、C，完成 C；以「去开会」压栈，添加 X。仅取 B 得 `[B,X]`，原帧仍显示 A、B 原序及原因，仅 A 可取；再全部恢复得 `[A,B,X]`。改 B 标题再压另一帧，旧快照不变。独立复位，对 `[A,B]` 帧与 `[X]` 队列分别验三种策略：队首 `[A,B,X]`、队尾 `[X,A,B]`、切换 `[A,B]` 且新帧只含 X；切换模式下选择 B 不新建帧。空原因/事务失败不移动任何项；连点、旋转、`adb shell am force-stop io.github.nakanomk.stacklife` 再启动均不重复、不丢任务。|
|M2 `DayTimelineTest`|待排「报告提纲」30 分钟拖到 15:45 → 显示 15:45–16:15，仍是同一队列 ID，只有一个块。改到 16:00 → 原块移动；取消排程 → 块消失，队列保留。用 TalkBack 的「安排时间」走同一路。压栈再恢复已排任务，时段不变。|
|M3 `HustImportTest`、`CourseTimelineTest`|自造周数组：首周周一 2026-09-07，第 1、3、9 周周一「操作系统」`JC="5–6"`，地点依次 A、B、B；周 1 重复一条，再加 `JC="实践"`。11 月 2 日起用冬表；待修正项不得成第一节。明确排除后，9 月 7 日显示 A 14:30–16:05，9 月 21 日显示 B，11 月 2 日显示 B 14:00–15:35。相同文件导两次仍三次课程，第 2 周为空。将任务拖入冬令课程 15:00 被拒，旧时段未动；15:35 可紧接安排。在线另须本人在获准设备登录 HUB，选学期、核对一门真实课再确认；遮挡身份/地点，不自动保存响应。|
|M4 `GoalOrderTest`、`GraphTransactionTest`、`GraphEditorTest`|依 A/B/C/D/X 顺序创建，画 `A→B, A→C, B→D, C→D`，选 D：预览并生成 `[A,B,C,D]`，不含 X，A 只一次；原队列不变。拖动 C 不改变次序；加 `D→A` 被拒且图/帧不变；仅选孤立 X 生成一项。生成后改图不改历史帧。|
|M5 `AssistantProposalTest`|从今天页要求「查资料→写提纲→交报告，生成栈帧，只把查资料恢复并安排 16:00」。无冲突时先只有差异，库不动；确认后一个三节点两边图、一帧剩两项、一项回队、一个时段；点第二次无新增。取消、预览后手动移动队列、401、工具参数无效、未获跨页数据授权，均不能静默写入。用测试专用录制响应/HTTP 测试端点复现边界，再以自愿提供的真实 BYOK 服务运行一次；测试替身不进生产。|
|M6 `AppearanceTest` + 真机|切浅/深、品牌/动态色、胶囊/贴底并冷启，布局/点击/偏好一致。360dp 宽、字体 200%、TalkBack、手势返回取消、低端禁玻璃都不丢主操作。同一滚动内容录开/关玻璃，支持档应能看见背景边缘折射。启动器圆形/方圆、单色主题均不裁脸。一台 Kit 能力 `>0` 的真机验 OPEN/BACK、快速打断、返回落点消失；一台能力 0 的设备照常进退。记录协议值、机型、系统和录屏，不记录事件私密正文。|

**M6 性能门槛。** 仅在 M6 建测试专用 `:macrobenchmark`；`benchmark` 构建继承 release、可 profile、仅装测试机，不拆生产模块。在固定真机/刷新率、同一份 30 项队列与一日课表，用 `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest` 分别测玻璃开/关下滚动、卡片往返、面板开合，各五轮，使用 [FrameTimingMetric](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)。API 31+ 测试机各可用档 `frameOverrunMs` P95 ≤ 0；不过只调/缩小有问题的光学表面再验，不以 debug 模拟器帧率宣称流畅。

宏基准使用独立 `.bench` 包，不读写普通包的日程。只在此变体启用 shell 可 profile 及受 `android.permission.DUMP` 保护的造数入口；通过正常事务写入 30 个任务及当天 5 门双节课。测量前检查实际胶囊状态，不把已降为实色的运行当作玻璃结果。固定设备与刷新率后查看输出 JSON 的 `sampledMetrics.frameOverrunMs` 和每轮 Perfetto trace。模拟器用 `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true` 只能检查交互链路，不用于 P95 判定。

**外部条件不互相代证。** HUB 在线登录、真实付费模型、联盟设备与 SDK 授权分别需要自己的证据。缺条件时仍保留原始文件导入、完整手动操作、普通 Compose 连续转场；只注明相应路径未验，不缩减路线图、不冒充通过。首个开发目标仍是离线可靠地中断和续做，不预设云同步、iOS 或开放 API。
