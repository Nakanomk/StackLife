package io.github.nakanomk.stacklife

import android.app.Application
import androidx.room.Room
import io.github.nakanomk.stacklife.assistant.ConnectionStore
import io.github.nakanomk.stacklife.assistant.OpenAiTransport
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi

class StackLifeApplication : Application() {
    internal val connections by lazy { ConnectionStore(this) }
    internal val chat by lazy { OpenAiTransport() }
    internal val planner: PlannerApi by lazy {
        RoomPlannerApi(Room.databaseBuilder(this, PlannerDatabase::class.java, "stacklife.db").build())
    }
}
