package io.github.nakanomk.stacklife.storage

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate

/** Only used by the database builder for new installs and the v9→10 transaction. */
internal object AgendaDefaults : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) { seed(db, null, null) }

    fun seed(db: SupportSQLiteDatabase, wake: Int?, sleep: Int?) {
        val first = LocalDate.of(1, 1, 1).toEpochDay()
        reminder(db, ReminderSubject.CourseDefault)
        for (kind in RoutineKind.entries) {
            val minute = if (kind == RoutineKind.WAKE) wake else sleep
            db.execSQL("INSERT INTO agenda_schedules(id,taskId,startMillis,endMillis,enabled,role,version) VALUES (?,NULL,NULL,NULL,?,?,0)",
                arrayOf<Any>(kind.scheduleId, if (minute == null) 0 else 1, kind.name))
            db.execSQL("INSERT INTO agenda_schedule_segments(scheduleId,fromDay,minute,zone,minutes,endDay) VALUES (?,?,?,NULL,0,NULL)",
                arrayOf<Any>(kind.scheduleId, first, minute ?: if (kind == RoutineKind.WAKE) 7 * 60 else 23 * 60))
            reminder(db, ReminderSubject.Schedule(kind.scheduleId))
        }
    }

    fun reminder(db: SupportSQLiteDatabase, subject: ReminderSubject) {
        val key = subject.storageKey()
        db.execSQL("INSERT INTO reminder_settings(subject,inherit,rootId) VALUES (?,0,?)", arrayOf(key,subject.rootId()))
        db.execSQL("INSERT INTO reminder_rules(subject,id,leadMillis,instantMillis) VALUES (?,?,?,NULL)",
            arrayOf<Any>(key, initialReminder.id, (initialReminder.time as ReminderWhen.BeforeStart).leadMillis))
    }
}

internal object AgendaMigration : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE agenda_schedules (id TEXT NOT NULL PRIMARY KEY, taskId TEXT, startMillis INTEGER, endMillis INTEGER,
            enabled INTEGER NOT NULL, role TEXT, version INTEGER NOT NULL, FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX index_agenda_schedules_taskId ON agenda_schedules(taskId)")
        db.execSQL("CREATE UNIQUE INDEX index_agenda_schedules_role ON agenda_schedules(role)")
        db.execSQL("CREATE TABLE agenda_schedule_segments (scheduleId TEXT NOT NULL, fromDay INTEGER NOT NULL, minute INTEGER NOT NULL, zone TEXT, minutes INTEGER NOT NULL, endDay INTEGER, PRIMARY KEY(scheduleId,fromDay), FOREIGN KEY(scheduleId) REFERENCES agenda_schedules(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE agenda_exceptions (scheduleId TEXT NOT NULL, originalDay INTEGER NOT NULL, startMillis INTEGER, endMillis INTEGER, version INTEGER NOT NULL, PRIMARY KEY(scheduleId,originalDay), FOREIGN KEY(scheduleId) REFERENCES agenda_schedules(id) ON DELETE CASCADE)")
        for (table in listOf("agenda_schedules", "agenda_exceptions")) for (field in listOf("startMillis", "endMillis"))
            db.execSQL("CREATE INDEX index_${table}_$field ON $table($field)")
        db.execSQL("CREATE TABLE agenda_completions (occurrenceKey TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE reminder_settings (subject TEXT NOT NULL PRIMARY KEY, inherit INTEGER NOT NULL, rootId TEXT, FOREIGN KEY(rootId) REFERENCES agenda_schedules(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_reminder_settings_rootId ON reminder_settings(rootId)")
        db.execSQL("CREATE TABLE reminder_rules (subject TEXT NOT NULL, id TEXT NOT NULL, leadMillis INTEGER, instantMillis INTEGER, PRIMARY KEY(subject,id), FOREIGN KEY(subject) REFERENCES reminder_settings(subject) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_reminder_rules_instantMillis ON reminder_rules(instantMillis)")
        db.execSQL("CREATE TABLE reminder_receipts (occurrenceKey TEXT NOT NULL, ruleId TEXT NOT NULL, triggerMillis INTEGER NOT NULL, status TEXT NOT NULL, PRIMARY KEY(occurrenceKey,ruleId,triggerMillis))")
        db.execSQL("CREATE INDEX index_reminder_receipts_triggerMillis ON reminder_receipts(triggerMillis)")
        db.execSQL("ALTER TABLE course_meetings ADD COLUMN occurrenceVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("INSERT INTO agenda_schedules(id,taskId,startMillis,endMillis,enabled,role,version) SELECT taskId,taskId,startMillis,endMillis,1,NULL,0 FROM time_blocks")
        db.query("SELECT taskId FROM time_blocks").use { c -> while (c.moveToNext()) AgendaDefaults.reminder(db, ReminderSubject.Schedule(c.getString(0))) }
        val (wake, sleep) = db.query("SELECT wakeMinute,sleepMinute FROM planner_settings WHERE id=1").use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) null else c.getInt(0)) to (if (c.isNull(1)) null else c.getInt(1)) else null to null
        }
        AgendaDefaults.seed(db, wake, sleep)
        db.compileStatement("INSERT INTO agenda_completions(occurrenceKey) VALUES (?)").use { mark ->
            fun done(key: OccurrenceKey) { mark.bindString(1, key.storageKey()); mark.executeInsert() }
            db.query("SELECT termId,source,week,day,firstPeriod,lastPeriod,title,room FROM course_ticks").use { c ->
                while (c.moveToNext()) done(OccurrenceKey.Course(CourseOccurrenceId(c.getString(0),c.getString(1),c.getInt(2),
                    c.getInt(3),c.getInt(4),c.getInt(5),c.getString(6),c.getString(7))))
            }
            db.query("SELECT kind,epochDay FROM routine_ticks").use { c -> while (c.moveToNext())
                done(OccurrenceKey.Schedule(RoutineKind.valueOf(c.getString(0)).scheduleId,c.getLong(1))) }
            db.query("SELECT b.taskId FROM time_blocks b JOIN tasks t ON t.id=b.taskId WHERE t.owner='DONE'").use { c ->
                while (c.moveToNext()) done(OccurrenceKey.Schedule(c.getString(0))) }
        }
        db.execSQL("CREATE TABLE frame_items_new(frameId TEXT NOT NULL, originalPosition INTEGER NOT NULL, taskId TEXT NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL, minutes INTEGER NOT NULL, scheduleJson TEXT, PRIMARY KEY(frameId,originalPosition), FOREIGN KEY(frameId) REFERENCES frames(id), FOREIGN KEY(taskId) REFERENCES tasks(id))")
        db.execSQL("INSERT INTO frame_items_new(frameId,originalPosition,taskId,title,notes,minutes) SELECT frameId,originalPosition,taskId,title,notes,minutes FROM frame_items")
        db.compileStatement("UPDATE frame_items_new SET scheduleJson=? WHERE frameId=? AND originalPosition=?").use { save ->
            db.query("SELECT frameId,originalPosition,taskId,scheduledStartMillis,scheduledEndMillis FROM frame_items WHERE scheduledStartMillis IS NOT NULL OR scheduledEndMillis IS NOT NULL").use { c ->
                while (c.moveToNext()) {
                    check(!c.isNull(3) && !c.isNull(4)) { "Incomplete legacy frame time" }
                    val snap = SavedSchedule(schedule = ScheduleSummary(c.getString(2), SchedulePattern.Once(ExactInterval(c.getLong(3),c.getLong(4)))))
                    save.bindString(1, snapshotJson.encodeToString(snap)); save.bindString(2,c.getString(0)); save.bindLong(3,c.getLong(1)); save.executeUpdateDelete()
                }
            }
        }
        db.execSQL("DROP TABLE frame_items"); db.execSQL("ALTER TABLE frame_items_new RENAME TO frame_items")
        db.execSQL("CREATE UNIQUE INDEX index_frame_items_frameId_taskId ON frame_items(frameId,taskId)")
        db.execSQL("CREATE INDEX index_frame_items_taskId ON frame_items(taskId)")
        db.execSQL("CREATE TABLE planner_settings_new(id INTEGER NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, restorePolicy TEXT NOT NULL, appearance TEXT NOT NULL DEFAULT '{}', onboardingVersion INTEGER NOT NULL DEFAULT 0, receiptsImported INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO planner_settings_new(id,revision,restorePolicy,appearance,onboardingVersion) SELECT id,revision,restorePolicy,appearance,onboardingVersion FROM planner_settings")
        db.execSQL("DROP TABLE planner_settings"); db.execSQL("ALTER TABLE planner_settings_new RENAME TO planner_settings")
        db.execSQL("DROP TABLE course_ticks"); db.execSQL("DROP TABLE routine_ticks"); db.execSQL("DROP TABLE time_blocks")
    }
}
