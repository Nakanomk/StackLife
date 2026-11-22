package io.github.nakanomk.stacklife.courses

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.PreviewResult
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal enum class ManualStep { TERM, COURSE, WEEKS, REVIEW }

internal data class ManualDraft(
    val openedWeek: Int,
    val existing: TermSpec?,
    val original: CoursePattern?,
    val newTerm: TermForm = TermForm(weeks = 16),
    val pattern: CoursePattern = original ?: CoursePattern("", "", 1, 1, 2),
    val weeks: Set<Int> = setOf(openedWeek),
    val originalWeeks: Set<Int>? = null,
    val step: ManualStep = ManualStep.TERM,
    val removing: Boolean = false,
    val submitted: Boolean = false,
    val scroll: List<Int> = List(ManualStep.entries.size) { 0 },
) {
    val weekCount: Int get() = existing?.weeks ?: newTerm.weeks ?: 0
    fun term() = existing ?: newTerm.term()
    fun previous() = when (step) {
        ManualStep.REVIEW -> if (removing) ManualStep.COURSE else ManualStep.WEEKS
        ManualStep.WEEKS -> ManualStep.COURSE
        else -> ManualStep.TERM
    }
}

private fun TermForm.json() = JSONObject().put("id", id).put("monday", monday?.toEpochDay()).put("weeks", weeks)
    .put("initial", initial.name).put("switchDay", switchDate?.toEpochDay()).put("expanded", expanded)
private fun JSONObject.date(key: String) = if (has(key)) LocalDate.ofEpochDay(getLong(key)) else null
private fun JSONObject.termForm() = TermForm(getString("id"), date("monday"), if (has("weeks")) getInt("weeks") else null,
    BellSchedule.valueOf(getString("initial")), date("switchDay"), getBoolean("expanded"))
private fun CoursePattern.json() = JSONObject().put("title", title).put("room", room).put("day", day)
    .put("firstPeriod", firstPeriod).put("lastPeriod", lastPeriod)
private fun JSONObject.pattern() = CoursePattern(getString("title"), getString("room"), getInt("day"), getInt("firstPeriod"), getInt("lastPeriod"))
private fun JSONArray.ints() = List(length()) { getInt(it) }
private fun ManualDraft.encode(): String = JSONObject().put("version", 1).put("openedWeek", openedWeek)
    .put("existing", existing?.let { TermForm(it.id, it.firstMonday, it.weeks, it.initial, it.switchDate).json() })
    .put("newTerm", newTerm.json()).put("pattern", pattern.json()).put("weeks", JSONArray(weeks))
    .put("original", original?.json()).put("originalWeeks", originalWeeks?.let { JSONArray(it) })
    .put("step", step.name).put("removing", removing).put("submitted", submitted).put("scroll", JSONArray(scroll)).toString()
private fun JSONObject.draft(): ManualDraft {
    require(getInt("version") == 1)
    return ManualDraft(openedWeek = getInt("openedWeek"), existing = optJSONObject("existing")?.termForm()?.term(),
        original = optJSONObject("original")?.pattern(), newTerm = getJSONObject("newTerm").termForm(),
        pattern = getJSONObject("pattern").pattern(), weeks = getJSONArray("weeks").ints().toSet(),
        originalWeeks = optJSONArray("originalWeeks")?.ints()?.toSet(), step = ManualStep.valueOf(getString("step")),
        removing = getBoolean("removing"), submitted = getBoolean("submitted"),
        scroll = getJSONArray("scroll").ints().also { require(it.size == ManualStep.entries.size && it.all { n -> n >= 0 }) })
}

/** The receipt and executable preview exist only in memory. Persist the attempted-write marker *before* commit. */
internal class ManualCourseForm(initial: ManualDraft, private val file: AtomicFile, val unreadable: Boolean = false) {
    var draft by mutableStateOf(initial)
        private set
    var ready by mutableStateOf<PreviewResult.Ready?>(null)
    var receipt by mutableStateOf<String?>(null)
    var preparing by mutableStateOf(false)
        private set
    var fileError by mutableStateOf(false)
    val busy get() = preparing || receipt != null
    private var checkpointed: ManualDraft? = null
    private var closed = false

    fun update(edit: (ManualDraft) -> ManualDraft) { draft = edit(draft) }
    fun rememberScroll(step: ManualStep, offset: Int) {
        val old = draft.scroll
        if (old[step.ordinal] != offset) update { it.copy(scroll = old.toMutableList().apply { set(step.ordinal, offset) }) }
    }

    // Like EditingNote.checkpoint, a final ON_STOP flush cannot be overtaken by an older queued write.
    @Synchronized fun checkpoint() {
        val value = draft
        if (closed || unreadable || checkpointed === value) return
        val out = file.startWrite()
        try {
            out.write(value.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            checkpointed = value
        } catch (e: Exception) { file.failWrite(out); throw e }
    }

    fun submit(model: WorkspaceViewModel) {
        val preview = ready ?: return
        if (busy || draft.submitted || unreadable) return
        preparing = true
        update { it.copy(submitted = true) }
        model.viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { checkpoint() }
                receipt = model.confirmProposal(preview.proposalId)
            } catch (_: IOException) { fileError = true }
            finally {
                if (receipt == null) update { it.copy(submitted = false) }
                preparing = false
            }
        }
    }

    @Synchronized fun delete() { closed = true; file.delete() }
}

internal class ManualEditorModel(private val savedStateHandle: SavedStateHandle, context: Context) : ViewModel() {
    private val directory = File(context.noBackupFilesDir, "manual-course-drafts")
    var form by mutableStateOf<ManualCourseForm?>(null)
        private set

    init {
        savedStateHandle.get<String>(KEY)?.let { id ->
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) savedStateHandle.remove<String>(KEY)
            else viewModelScope.launch {
                val file = AtomicFile(File(directory, id))
                val restored = withContext(Dispatchers.IO) {
                    try { ManualCourseForm(JSONObject(file.readFully().toString(Charsets.UTF_8)).draft(), file) }
                    catch (_: FileNotFoundException) { null } // A successful save/cancel may postdate the system's saved Bundle.
                    catch (_: IOException) { ManualCourseForm(ManualDraft(1, null, null), file, unreadable = true) }
                    catch (_: JSONException) { ManualCourseForm(ManualDraft(1, null, null), file, unreadable = true) }
                    catch (_: IllegalArgumentException) { ManualCourseForm(ManualDraft(1, null, null), file, unreadable = true) }
                }
                if (savedStateHandle.get<String>(KEY) == id) {
                    if (restored == null) savedStateHandle.remove<String>(KEY) else form = restored
                }
            }
        }
    }

    fun open(term: TermSpec?, week: Int, original: CoursePattern? = null) {
        if (savedStateHandle.contains(KEY)) return
        val id = UUID.randomUUID().toString()
        savedStateHandle[KEY] = id
        form = ManualCourseForm(ManualDraft(week, term, original), AtomicFile(File(directory, id)))
    }
    fun close() {
        form?.delete()
        form = null
        savedStateHandle.remove<String>(KEY)
    }
    private companion object { const val KEY = "manualCourseDraftId" }
}
