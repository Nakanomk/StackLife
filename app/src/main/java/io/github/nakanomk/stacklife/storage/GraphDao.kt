package io.github.nakanomk.stacklife.storage

import androidx.room.*
import io.github.nakanomk.stacklife.planner.DailySegment
import io.github.nakanomk.stacklife.planner.GraphEdge
import io.github.nakanomk.stacklife.planner.GraphLink
import io.github.nakanomk.stacklife.planner.GraphListItem
import io.github.nakanomk.stacklife.planner.GraphNode
import io.github.nakanomk.stacklife.planner.GraphSummary
import io.github.nakanomk.stacklife.planner.Owner
import io.github.nakanomk.stacklife.planner.RoutineKind
import io.github.nakanomk.stacklife.planner.TaskIcon

@Entity(tableName = "plan_graphs")
internal data class GraphRow(@PrimaryKey val id: String, val title: String)

@Entity(tableName = "graph_nodes", primaryKeys = ["graphId", "id"],
    foreignKeys = [
        ForeignKey(entity = GraphRow::class, parentColumns = ["id"], childColumns = ["graphId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaskRow::class, parentColumns = ["id"], childColumns = ["linkedTaskId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["graphId", "creationOrder", "id"]), Index(value = ["graphId", "linkKey"], unique = true),
        Index(value = ["linkedTaskId"])])
internal data class GraphNodeRow(val graphId: String, val id: String, val title: String, val notes: String,
    val minutes: Int, val creationOrder: Long, val x: Float, val y: Float,
    val linkKey: String? = null, val linkedTaskId: String? = null, @ColumnInfo(defaultValue = "'AUTO'") val icon: TaskIcon = TaskIcon.AUTO) {
    fun node() = GraphNode(id, title, notes, minutes, creationOrder, x, y,
        link = linkKey?.let { snapshotJson.decodeFromString<GraphLink>(it) }, icon = icon)
}

@Entity(tableName = "graph_edges", primaryKeys = ["graphId", "fromId", "toId"], foreignKeys = [
    ForeignKey(entity = GraphNodeRow::class, parentColumns = ["graphId", "id"], childColumns = ["graphId", "fromId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = GraphNodeRow::class, parentColumns = ["graphId", "id"], childColumns = ["graphId", "toId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["graphId", "toId"])])
internal data class GraphEdgeRow(val graphId: String, val fromId: String, val toId: String)

internal data class CandidateTaskRow(val id: String, val title: String, val owner: Owner, val scheduled: Boolean,
    val startMillis: Long?)
internal data class CandidateDailyRow(val id: String, val title: String?, val owner: Owner?, val role: RoutineKind?,
    val movedStart: Long?, @Embedded val segment: DailySegment?)

// The ordinary partition includes one-offs even outside the chosen day, but never the task behind
// a daily series. That series is selectable only by a bounded, concrete occurrence.
private const val CANDIDATE_TASKS = """ FROM tasks t LEFT JOIN agenda_schedules s ON s.taskId=t.id
    WHERE t.owner != 'REMOVED' AND (:owner IS NULL OR t.owner=:owner)
    AND (s.id IS NULL OR s.startMillis IS NOT NULL)
    AND (instr(lower(t.title),lower(:search))>0 OR instr(t.id,:search)>0) """

@Dao
internal interface GraphDao {
    @Query("""
        SELECT g.id, g.title,
            (SELECT COUNT(*) FROM graph_nodes n WHERE n.graphId = g.id) AS nodeCount,
            (SELECT COUNT(*) FROM graph_edges e WHERE e.graphId = g.id) AS edgeCount
        FROM plan_graphs g ORDER BY g.rowid DESC
    """)
    fun list(): List<GraphListItem>
    @Query("SELECT id, title FROM plan_graphs WHERE id = :id") fun graph(id: String): GraphSummary?
    @Query("SELECT * FROM graph_nodes WHERE graphId=:id ORDER BY creationOrder,id")
    fun nodeRows(id: String): List<GraphNodeRow>
    fun nodes(id: String): List<GraphNode> = nodeRows(id).map { it.node() }
    @Query("SELECT linkKey FROM graph_nodes WHERE graphId=:id AND linkKey IN (:keys)")
    fun linkedKeys(id: String, keys: List<String>): List<String>
    @Query("SELECT linkedTaskId FROM graph_nodes WHERE graphId=:id AND id=:nodeId")
    fun linkedTaskId(id: String, nodeId: String): String?
    @Query("SELECT * FROM tasks WHERE id=:id AND owner!='REMOVED'")
    fun linkableTask(id: String): TaskRow?
    @Query("SELECT COUNT(*)" + CANDIDATE_TASKS)
    fun taskCount(owner: Owner?, search: String): Int
    // Select only the segment in force on the original date (not every segment ever created).
    // An explicit exception overrides the segment even if an all-series edit changed its range.
    @Query("""SELECT s.id,t.title,t.owner,s.role,e.startMillis AS movedStart,
        seg.fromDay,seg.minute,seg.zone,seg.minutes,seg.endDay
        FROM agenda_schedules s LEFT JOIN tasks t ON t.id=s.taskId
        LEFT JOIN agenda_exceptions e ON e.scheduleId=s.id AND e.originalDay=:day
        LEFT JOIN agenda_schedule_segments seg ON seg.scheduleId=s.id AND seg.fromDay=(
            SELECT max(x.fromDay) FROM agenda_schedule_segments x WHERE x.scheduleId=s.id AND x.fromDay<=:day)
        WHERE s.enabled AND s.startMillis IS NULL AND (t.owner IN ('QUEUE','FRAME') OR s.role IS NOT NULL)
        AND (:owner IS NULL OR t.owner=:owner)
        AND (s.role IS NOT NULL OR instr(lower(t.title),lower(:search))>0 OR instr(s.id,:search)>0)
        AND (e.startMillis IS NOT NULL OR (e.originalDay IS NULL AND seg.fromDay IS NOT NULL
            AND (seg.endDay IS NULL OR seg.endDay>=:day)))
        ORDER BY s.id""")
    fun dailyOn(day: Long, owner: Owner?, search: String): List<CandidateDailyRow>
    @Query("""SELECT t.id,t.title,t.owner,coalesce(s.enabled,0) AS scheduled,
        CASE WHEN s.enabled THEN s.startMillis END AS startMillis""" + CANDIDATE_TASKS +
        " ORDER BY t.title COLLATE NOCASE,t.id LIMIT :limit OFFSET :offset")
    fun taskPage(owner: Owner?, search: String, offset: Int, limit: Int): List<CandidateTaskRow>
    @Query("SELECT fromId AS `from`, toId AS `to` FROM graph_edges WHERE graphId = :id ORDER BY fromId,toId") fun edges(id: String): List<GraphEdge>
    @Upsert fun putGraph(row: GraphRow)
    @Upsert fun putNodes(rows: List<GraphNodeRow>)
    @Insert fun putEdges(rows: List<GraphEdgeRow>)
    @Query("DELETE FROM plan_graphs WHERE id = :id") fun deleteGraph(id: String)
    @Query("DELETE FROM graph_nodes WHERE graphId = :graphId AND id IN (:ids)") fun deleteNodes(graphId: String, ids: List<String>)
    @Delete fun deleteEdges(rows: List<GraphEdgeRow>)
}
