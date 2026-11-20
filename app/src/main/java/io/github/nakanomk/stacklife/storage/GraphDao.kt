package io.github.nakanomk.stacklife.storage

import androidx.room.*
import io.github.nakanomk.stacklife.planner.GraphEdge
import io.github.nakanomk.stacklife.planner.GraphNode
import io.github.nakanomk.stacklife.planner.GraphSummary

@Entity(tableName = "plan_graphs")
internal data class GraphRow(@PrimaryKey val id: String, val title: String)

@Entity(tableName = "graph_nodes", primaryKeys = ["graphId", "id"],
    foreignKeys = [ForeignKey(entity = GraphRow::class, parentColumns = ["id"], childColumns = ["graphId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["graphId", "creationOrder", "id"])])
internal data class GraphNodeRow(val graphId: String, val id: String, val title: String, val notes: String,
    val minutes: Int, val creationOrder: Long, val x: Float, val y: Float)

@Entity(tableName = "graph_edges", primaryKeys = ["graphId", "fromId", "toId"], foreignKeys = [
    ForeignKey(entity = GraphNodeRow::class, parentColumns = ["graphId", "id"], childColumns = ["graphId", "fromId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = GraphNodeRow::class, parentColumns = ["graphId", "id"], childColumns = ["graphId", "toId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["graphId", "toId"])])
internal data class GraphEdgeRow(val graphId: String, val fromId: String, val toId: String)

@Dao
internal interface GraphDao {
    @Query("SELECT id, title FROM plan_graphs ORDER BY rowid DESC") fun list(): List<GraphSummary>
    @Query("SELECT id, title FROM plan_graphs WHERE id = :id") fun graph(id: String): GraphSummary?
    @Query("SELECT id,title,notes,minutes,creationOrder,x,y FROM graph_nodes WHERE graphId = :id ORDER BY creationOrder,id")
    fun nodes(id: String): List<GraphNode>
    @Query("SELECT fromId AS `from`, toId AS `to` FROM graph_edges WHERE graphId = :id ORDER BY fromId,toId") fun edges(id: String): List<GraphEdge>
    @Upsert fun putGraph(row: GraphRow)
    @Upsert fun putNodes(rows: List<GraphNodeRow>)
    @Insert fun putEdges(rows: List<GraphEdgeRow>)
    @Query("DELETE FROM plan_graphs WHERE id = :id") fun deleteGraph(id: String)
    @Query("DELETE FROM graph_nodes WHERE graphId = :graphId AND id IN (:ids)") fun deleteNodes(graphId: String, ids: List<String>)
    @Delete fun deleteEdges(rows: List<GraphEdgeRow>)
}
