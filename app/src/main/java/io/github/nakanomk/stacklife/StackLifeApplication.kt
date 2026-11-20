package io.github.nakanomk.stacklife

import android.app.Application
import androidx.room.Room
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi

class StackLifeApplication : Application() {
    internal val planner: PlannerApi by lazy {
        RoomPlannerApi(Room.databaseBuilder(this, PlannerDatabase::class.java, "stacklife.db").build())
    }
}
