package io.github.nakanomk.stacklife

import android.app.Application
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.planner.decodeAppearance
import android.content.res.Configuration
import io.github.nakanomk.stacklife.assistant.ConnectionStore
import io.github.nakanomk.stacklife.assistant.AssistantPreference
import io.github.nakanomk.stacklife.assistant.AttachmentStore
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.assistant.OpenAiTransport
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import io.github.nakanomk.stacklife.reminders.AgendaReminders
import io.github.nakanomk.stacklife.updates.AppUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class StackLifeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocales.initialize(this)
        reminders.initialize()
        CoroutineScope(Dispatchers.IO).launch {
            try { attachments.recover() }
            catch (e: Exception) { android.util.Log.w("StackLife", "Could not recover local chat attachments", e) }
            try { backgrounds.recover() }
            catch (e: Exception) { android.util.Log.w("StackLife", "Could not recover private backgrounds", e) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocales.sync(this)
    }

    internal val updates by lazy { AppUpdates(this) }
    internal val assistantPreference by lazy { AssistantPreference(this) }
    internal val connections by lazy { ConnectionStore(this) }
    internal val chat by lazy { OpenAiTransport() }
    private val database by lazy { PlannerDatabase.open(this, "stacklife.db") }
    internal val history by lazy { database.conversationHistory() }
    internal val attachments by lazy { AttachmentStore(this, { history.attachmentIds().toSet() }) }
    internal val reminders by lazy { AgendaReminders(this, database) { planner } }
    internal val backgrounds by lazy { BackgroundStore(this) { database.withTransaction {
        buildSet { addAll(database.countdownDao().backgroundIds()); addAll(history.backgroundIds())
            database.plannerDao().settings()?.let { addAll(decodeAppearance(it.appearance).backgroundIds()) } }
    } } }
    internal val planner: PlannerApi by lazy { RoomPlannerApi(database, imageExists = backgrounds::exists,
        collectBackgrounds = backgrounds::collect, afterCommit = { reminders.refresh() }) }
}
