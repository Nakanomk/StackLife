package io.github.nakanomk.stacklife

import android.app.Application
import android.content.res.Configuration
import androidx.room.Room
import io.github.nakanomk.stacklife.assistant.ConnectionStore
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.assistant.OpenAiTransport
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi

open class StackLifeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocales.initialize(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocales.sync(this)
    }

    internal val connections by lazy { ConnectionStore(this) }
    internal val chat by lazy { OpenAiTransport() }
    private val database by lazy { Room.databaseBuilder(this, PlannerDatabase::class.java, "stacklife.db").build() }
    internal val history by lazy { database.conversationHistory() }
    internal val planner: PlannerApi by lazy { RoomPlannerApi(database) }
}
