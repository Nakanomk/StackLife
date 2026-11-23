package io.github.nakanomk.stacklife.courses

import android.os.Bundle
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nakanomk.stacklife.i18n.uiText
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

internal enum class ImportPage { SOURCE, HUB, CALENDAR, ENTRIES, FIX, DIFF }

// Like the private note drafts, Android retains an identity, never the document or a proposal.
// Source JSON is written once when acquired, not rewritten when editing a row or rotating the phone.
@OptIn(ExperimentalSerializationApi::class)
internal class ImportReviewState(handle: SavedStateHandle, private val root: File, private val termExists: suspend (String) -> Boolean) : ViewModel() {
    var page by mutableStateOf(ImportPage.SOURCE)
    var fromHub by mutableStateOf(false)
    var form by mutableStateOf(TermForm())
    var selectedId by mutableStateOf<String?>(null)
    var rows by mutableStateOf<JsonArray?>(null)
        private set
    var draft by mutableStateOf<ImportDraft?>(null)
        private set
    val decisions = mutableStateMapOf<String, IssueDecision>()
    var editing by mutableStateOf<String?>(null)
    var working by mutableStateOf<CourseFields?>(null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    private var directory: File? = null

    @Serializable private data class Fields(val week: String, val day: Int, val title: String, val room: String, val period: String) {
        constructor(value: CourseFields) : this(value.week, value.day, value.title, value.room, value.period)
        fun value() = CourseFields(week, day, title, room, period)
    }
    @Serializable private data class Calendar(val id: String, val monday: Long?, val weeks: Int?, val initial: BellSchedule, val switch: Long?, val expanded: Boolean) {
        constructor(f: TermForm) : this(f.id, f.monday?.toEpochDay(), f.weeks, f.initial, f.switchDate?.toEpochDay(), f.expanded)
        fun form() = TermForm(id, monday?.let(LocalDate::ofEpochDay), weeks, initial, switch?.let(LocalDate::ofEpochDay), expanded)
    }
    @Serializable private data class Checkpoint(val page: ImportPage, val fromHub: Boolean, val form: Calendar,
        val selected: String?, val parsed: Calendar?, val choices: Map<String, Fields?>, val editing: String?, val working: Fields?,
        val namedSelection: Boolean = false)

    init {
        val last = handle.remove<Bundle>("import-review")
        val id = last?.getString("id")
        if (id != null) {
            val dir = File(root, UUID.fromString(id).toString())
            directory = dir
            busy = true
            viewModelScope.launch {
                try {
                    if (last?.getBoolean("failed") == true) throw IOException("Incomplete checkpoint")
                    val (input, saved, parsed) = withContext(Dispatchers.IO) {
                        val checkpoint = AtomicFile(File(dir, "review")).openRead().use { Json.decodeFromStream<Checkpoint>(it) }
                        val input = File(dir, "rows").inputStream().buffered().use { Json.decodeFromStream<JsonArray>(it) }
                        Triple(input, checkpoint, checkpoint.parsed?.let { HustCourseImporter.parse(input, it.form().term()) })
                    }
                    val legacyNew = !saved.namedSelection && saved.selected == saved.form.id && !termExists(saved.form.id)
                    rows = input; fromHub = saved.fromHub; form = saved.form.form(); selectedId = saved.selected.takeUnless { legacyNew }; draft = parsed
                    saved.choices.forEach { (position, fields) -> decisions[position] = fields?.let { IssueDecision.Correct(it.value()) } ?: IssueDecision.Exclude }
                    editing = saved.editing; working = saved.working?.value(); page = saved.page
                } catch (_: IOException) { restoreFailed() } catch (_: IllegalArgumentException) { restoreFailed() }
                finally { busy = false }
            }
        } else if (last?.getBoolean("hub") == true) page = ImportPage.HUB
        handle.setSavedStateProvider("import-review", ::checkpoint)
    }

    internal fun checkpoint(): Bundle = Bundle().apply {
        val dir = directory
        if (dir == null) { putBoolean("hub", page == ImportPage.HUB); return@apply }
        putString("id", dir.name)
        // If Android saves again during restoration the previous complete checkpoint still applies.
        if (rows == null) return@apply
        val state = Checkpoint(page, fromHub, Calendar(form), selectedId, draft?.term?.let { Calendar(it.asForm()) },
            decisions.mapValues { (_, v) -> (v as? IssueDecision.Correct)?.let { Fields(it.fields) } }, editing, working?.let(::Fields), namedSelection = true)
        val index = AtomicFile(File(dir, "review"))
        try {
            val out = index.startWrite()
            try { Json.encodeToStream(state, out); index.finishWrite(out) }
            catch (failure: Exception) { index.failWrite(out); throw failure }
        } catch (_: IOException) {
            putBoolean("failed", true)
            message = uiText("The draft could not be saved for restarting the app.", "无法保存用于重启恢复的草稿。")
        }
    }

    private fun restoreFailed() {
        reset()
        message = uiText("Could not reopen the private draft. Select the source again.", "无法重新打开本地草稿，请重新选择来源。")
    }

    fun receive(isHub: Boolean, read: () -> JsonArray) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            val dir = File(root, UUID.randomUUID().toString())
            try {
                val input = withContext(Dispatchers.IO) {
                    val data = read()
                    if (data.isEmpty()) throw SourceProblem(uiText("The source is empty. Your timetable was not changed.", "来源为空，已有课表未更改。"))
                    if (!dir.mkdirs()) throw IOException()
                    File(dir, "rows").outputStream().buffered().use { Json.encodeToStream(data, it) }
                    data
                }
                directory = dir; rows = input; fromHub = isHub
                val hint = firstWeekHints(input).singleOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                form = TermForm(monday = hint?.takeIf { it.year in 1..9999 && it.dayOfWeek.value == 1 })
                selectedId = null; page = ImportPage.CALENDAR
            } catch (e: SourceProblem) { message = e.message }
            catch (_: SecurityException) { message = uiText("Read permission denied. Select the file again.", "没有读取权限，请重新选择文件。") }
            catch (_: IOException) { message = uiText("Could not read or save the private draft. Select the file again.", "无法读取或保存本地草稿，请重新选择文件。") }
            finally { if (directory != dir) dir.deleteRecursively(); busy = false }
        }
    }

    fun review(termForm: TermForm) {
        if (busy) return
        val input = rows ?: return
        busy = true; message = null
        viewModelScope.launch {
            try {
                val term = if (selectedId == null) termForm.copy(id = termForm.id.trim()).term() else termForm.term()
                if (draft?.term != term) {
                    draft = withContext(Dispatchers.Default) { HustCourseImporter.parse(input, term) }
                    decisions.clear()
                }
                page = ImportPage.ENTRIES
            } catch (e: IllegalArgumentException) { message = e.message }
            finally { busy = false }
        }
    }

    fun reset() {
        directory?.deleteRecursively(); directory = null; rows = null; draft = null; selectedId = null
        decisions.clear(); editing = null; working = null; form = TermForm(); fromHub = false; message = null; page = ImportPage.SOURCE
    }

    override fun onCleared() { directory?.deleteRecursively() }
}

internal fun TermSpec.asForm() = TermForm(id, firstMonday, weeks, initial, switchDate)
