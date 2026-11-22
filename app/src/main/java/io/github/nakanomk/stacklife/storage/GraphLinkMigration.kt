package io.github.nakanomk.stacklife.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Nullable graph links preserve every template, node id and edge; no graph table is dropped. */
internal object GraphLinkMigration : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE graph_nodes ADD COLUMN linkKey TEXT")
        db.execSQL("ALTER TABLE graph_nodes ADD COLUMN linkedTaskId TEXT REFERENCES tasks(id) ON DELETE SET NULL")
        db.execSQL("CREATE UNIQUE INDEX index_graph_nodes_graphId_linkKey ON graph_nodes(graphId,linkKey)")
        db.execSQL("CREATE INDEX index_graph_nodes_linkedTaskId ON graph_nodes(linkedTaskId)")

        // An occurrence reference has identity even without a task (system routines), and two
        // dates of one series can belong to one snapshot. Only owned rows participate in restore.
        db.execSQL("""CREATE TABLE frame_items_new(frameId TEXT NOT NULL, originalPosition INTEGER NOT NULL,
            taskId TEXT NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL, minutes INTEGER NOT NULL,
            scheduleJson TEXT, referenceOnly INTEGER NOT NULL DEFAULT 0, occurrenceDay INTEGER, ownedTaskId TEXT,
            PRIMARY KEY(frameId,originalPosition), FOREIGN KEY(frameId) REFERENCES frames(id),
            FOREIGN KEY(ownedTaskId) REFERENCES tasks(id))""")
        db.execSQL("""INSERT INTO frame_items_new(frameId,originalPosition,taskId,title,notes,minutes,scheduleJson,ownedTaskId)
            SELECT frameId,originalPosition,taskId,title,notes,minutes,scheduleJson,taskId FROM frame_items""")
        db.execSQL("DROP TABLE frame_items")
        db.execSQL("ALTER TABLE frame_items_new RENAME TO frame_items")
        db.execSQL("CREATE INDEX index_frame_items_frameId_taskId ON frame_items(frameId,taskId)")
        db.execSQL("CREATE INDEX index_frame_items_ownedTaskId ON frame_items(ownedTaskId)")
    }
}
