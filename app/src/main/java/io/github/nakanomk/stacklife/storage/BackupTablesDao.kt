package io.github.nakanomk.stacklife.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.Owner

/** All methods run inside the coordinator's Room transaction. Parent deletion is only used for
 * children exclusively owned by that selected category. Tasks are never REPLACEd. */
@Dao internal interface BackupTablesDao {
    @Query("SELECT * FROM tasks WHERE owner IN (:owners) ORDER BY id") fun tasks(owners:List<Owner>):List<TaskRow>
    @Query("SELECT id,owner FROM tasks") fun owners():List<BackupOwner>
    @Query("SELECT * FROM frames ORDER BY rowid") fun frames():List<FrameRow>
    @Query("SELECT * FROM frame_items ORDER BY frameId,originalPosition") fun items():List<FrameItemRow>
    @Query("SELECT * FROM agenda_schedules ORDER BY id") fun schedules():List<ScheduleRow>
    @Query("SELECT * FROM agenda_schedule_segments ORDER BY scheduleId,fromDay") fun segments():List<SegmentRow>
    @Query("SELECT * FROM agenda_exceptions ORDER BY scheduleId,originalDay") fun exceptions():List<ExceptionRow>
    @Query("SELECT occurrenceKey FROM agenda_completions ORDER BY occurrenceKey") fun completions():List<String>
    @Query("SELECT * FROM reminder_settings ORDER BY subject") fun policies():List<ReminderSettingRow>
    @Query("SELECT * FROM reminder_rules ORDER BY subject,rowid") fun rules():List<ReminderRuleRow>
    @Query("SELECT * FROM plan_graphs ORDER BY rowid") fun graphs():List<GraphRow>
    @Query("SELECT * FROM graph_nodes ORDER BY graphId,id") fun nodes():List<GraphNodeRow>
    @Query("SELECT * FROM graph_edges ORDER BY graphId,fromId,toId") fun edges():List<GraphEdgeRow>
    @Query("SELECT * FROM course_meetings ORDER BY termId,source,week,day,firstPeriod,lastPeriod,title,room") fun meetings():List<CourseRow>
    @Query("SELECT * FROM conversations ORDER BY id LIMIT :limit OFFSET :offset") fun threads(limit:Int,offset:Int):List<ConversationRow>
    @Query("SELECT * FROM conversation_messages ORDER BY id LIMIT :limit OFFSET :offset") fun messages(limit:Int,offset:Int):List<ConversationMessageRow>
    @Query("SELECT * FROM conversation_images ORDER BY id LIMIT :limit OFFSET :offset") fun images(limit:Int,offset:Int):List<ConversationImageRow>
    @Query("SELECT * FROM conversation_documents ORDER BY id LIMIT :limit OFFSET :offset") fun documents(limit:Int,offset:Int):List<ConversationDocumentRow>

    @Upsert fun putTasks(rows:List<TaskRow>)
    @Query("DELETE FROM tasks WHERE id=:id") fun deleteTask(id:String)
    @Query("DELETE FROM frame_items") fun clearItems()
    @Query("UPDATE tasks SET owner='REMOVED', frameId=NULL WHERE owner='FRAME'") fun detachFrameTasks()
    @Query("DELETE FROM frames") fun clearFrames()
    @Query("DELETE FROM agenda_schedules") fun clearSchedules()
    @Query("DELETE FROM plan_graphs") fun clearGraphs()
    @Query("DELETE FROM course_terms") fun clearTerms()
    @Query("DELETE FROM countdowns") fun clearCountdowns()
    @Query("DELETE FROM conversations") fun clearHistory()
    @Insert fun putThreads(rows:List<ConversationRow>)
    @Insert fun putMessages(rows:List<ConversationMessageRow>)
    @Insert fun putImages(rows:List<ConversationImageRow>)
    @Insert fun putDocuments(rows:List<ConversationDocumentRow>)
}
internal data class BackupOwner(val id:String,val owner:Owner)
