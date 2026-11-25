package io.github.nakanomk.stacklife.archive

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import io.github.nakanomk.stacklife.i18n.LanguageChoice
import io.github.nakanomk.stacklife.updates.UpdateChannel
import io.github.nakanomk.stacklife.widget.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.Semaphore
import kotlinx.serialization.Serializable

/** Normal UI mutations are synchronous. Only the short snapshot/activation (never SAF streaming or
 * a main-thread callback) holds this permit across dispatcher switches. */
internal object DevicePreferenceGate {
    private val permit = Semaphore(1, true)
    var epoch = 0L; private set
    private var replay: Pair<java.io.File, () -> Unit>? = null
    // Called inside exclusive(), never handed to Room or a main dispatcher callback.
    fun defer(marker: java.io.File, action: () -> Unit) { replay = marker to action }
    fun completed(marker: java.io.File) { if (replay?.first == marker) replay = null }
    fun restored() { epoch++ }
    fun <T> access(action: () -> T): T {
        while (true) {
            permit.acquireUninterruptibly()
            val retry = replay?.second
            if (retry == null) try { return action() } finally { permit.release() }
            permit.release()
            retry() // recover takes connection -> preference, never the reverse.
        }
    }
    fun change(action: () -> Unit) = access { action(); epoch++; Unit }
    suspend fun <T> exclusive(action: suspend () -> T): T {
        permit.acquire()
        try { return action() } finally { permit.release() }
    }
}

@Serializable internal data class UpdatePreferences(val channel: UpdateChannel, val automaticCheck: Boolean, val automaticInstall: Boolean) {
    init { require(!automaticInstall || automaticCheck) }
}

@Serializable internal data class DevicePreferences(
    val assistantEnabled: Boolean, val language: LanguageChoice, val updates: UpdatePreferences,
    val widgets: List<WidgetTemplate>,
) { init { require(widgets.size <= 512) } }

/** Launcher sizes, provider identities, host IDs and PendingIntents never enter this model. */
@Serializable internal data class WidgetTemplate(val weekly: Boolean, val monday: Long?, val pageDate: Long?, val pageStart: Int?) {
    init {
        if (monday != null) require(weekly && LocalDate.ofEpochDay(monday).dayOfWeek == DayOfWeek.MONDAY)
        if (pageDate != null) LocalDate.ofEpochDay(pageDate)
        require((pageDate == null) == (pageStart == null) && (pageStart == null || pageStart >= 0))
    }
    fun options() = Bundle().apply {
        if (weekly) putLong(PICKED_WEEK, monday ?: FOLLOW)
        putInt(PAGE_START, pageStart ?: -1)
        if (pageDate != null) putLong(PAGE_DATE, pageDate)
    }
}

/** Only inputs for *newly added* instances. Existing launcher-owned options are never overwritten.
 * A claimed template and its local ID are journaled together before calling the host, so a crash
 * cannot hand that template to a second widget. The claims themselves are never exported. */
internal object BackupWidgetTemplates {
    private const val PREFS = "backup-widget-templates"
    private const val QUEUE = "available"
    private const val KNOWN = "existing"
    private const val PREFIX = "claim:"
    private const val APPLIED = "stacklife.archive.templateApplied"
    private fun store(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun available(c: Context) = archiveJson.decodeFromString<List<WidgetTemplate>>(store(c).getString(QUEUE, null) ?: "[]")
    private fun ids(c: Context, manager: AppWidgetManager, weekly: Boolean) = manager.getAppWidgetIds(ComponentName(c,
        if (weekly) WeekWidgetProvider::class.java else TodayWidgetProvider::class.java)).sorted()

    fun snapshot(c: Context): List<WidgetTemplate> = buildList {
        val manager = AppWidgetManager.getInstance(c)
        for (weekly in listOf(false, true)) for (id in ids(c, manager, weekly)) {
            val options = manager.getAppWidgetOptions(id)
            val claim = store(c).getString("$PREFIX$id", null)
            if (claim != null && !options.getBoolean(APPLIED)) { add(archiveJson.decodeFromString<WidgetTemplate>(claim)); continue }
            val monday = if (weekly) options.getLong(PICKED_WEEK, FOLLOW).takeUnless { it == FOLLOW } else null
            val page = options.getInt(PAGE_START, -1).takeIf { it >= 0 && options.containsKey(PAGE_DATE) }
            add(WidgetTemplate(weekly, monday, page?.let { options.getLong(PAGE_DATE) }, page))
        }
        addAll(available(c))
    }

    fun existing(c: Context): List<Int> {
        val manager = AppWidgetManager.getInstance(c)
        return ids(c, manager, false) + ids(c, manager, true)
    }

    fun restore(c: Context, templates: List<WidgetTemplate>, existing: List<Int>, activation: String) {
        val prefs = store(c)
        if (prefs.getString("activation", null) == activation) return
        check(prefs.edit().clear().putString("activation", activation).putString(QUEUE, archiveJson.encodeToString(templates))
            .putStringSet(KNOWN, existing.mapTo(mutableSetOf()) { it.toString() }).commit())
    }

    fun applyToNew(context: Context, manager: AppWidgetManager, ids: IntArray, weekly: Boolean) = DevicePreferenceGate.access {
        val prefs = store(context)
        if (!prefs.contains(QUEUE)) return@access
        val available = available(context).toMutableList()
        val known = prefs.getStringSet(KNOWN, emptySet()).orEmpty().toMutableSet()
        for (id in ids) {
            val claim = "$PREFIX$id"
            val saved = prefs.getString(claim, null)
            val template = if (saved != null) archiveJson.decodeFromString<WidgetTemplate>(saved) else {
                val index = available.indexOfFirst { it.weekly == weekly }
                if (index < 0 || !known.add(id.toString())) continue
                available.removeAt(index).also {
                    check(prefs.edit().putStringSet(KNOWN, known.toSet()).putString(QUEUE, archiveJson.encodeToString(available))
                        .putString(claim, archiveJson.encodeToString(it)).commit())
                }
            }
            if (template.weekly == weekly && !manager.getAppWidgetOptions(id).getBoolean(APPLIED))
                manager.updateAppWidgetOptions(id, template.options().apply { putBoolean(APPLIED, true) })
            check(prefs.edit().remove(claim).commit())
        }
    }
}
