package io.github.nakanomk.stacklife.assistant

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

internal enum class HistoryRole { USER, ASSISTANT, NOTICE }

@Entity(tableName = "conversations", indices = [Index(value = ["lastMessageId"])])
internal data class ConversationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    val title: String,
    val updatedAtMillis: Long = 0,
    val lastMessageId: Long = 0,
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [ForeignKey(entity = ConversationRow::class, parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["conversationId"])],
)
internal data class ConversationMessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: HistoryRole,
    val text: String,
)

@Dao
internal abstract class ConversationHistory {
    // The global message sequence, unlike wall time, orders simultaneous sends and survives clock changes.
    @Query("SELECT * FROM conversations ORDER BY lastMessageId DESC")
    abstract fun observeConversations(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id ORDER BY id")
    abstract fun observeMessages(id: Long): Flow<List<ConversationMessageRow>>

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Transaction
    open suspend fun start(scope: String, prompt: String): Long {
        val title = prompt.trimStart().take(80).trimEnd { it.isWhitespace() || it.isHighSurrogate() }
        val id = insertConversation(ConversationRow(scope = scope, title = title))
        record(id, HistoryRole.USER, prompt)
        return id
    }

    @Transaction
    open suspend fun append(id: Long, role: HistoryRole, text: String) {
        // A request may finish after its user deleted the conversation.
        if (exists(id)) record(id, role, text)
    }

    private suspend fun record(id: Long, role: HistoryRole, text: String) {
        val messageId = insertMessage(ConversationMessageRow(conversationId = id, role = role, text = text))
        markRecent(id, messageId, System.currentTimeMillis())
    }

    @Insert protected abstract suspend fun insertConversation(row: ConversationRow): Long
    @Insert protected abstract suspend fun insertMessage(row: ConversationMessageRow): Long

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    protected abstract suspend fun exists(id: Long): Boolean

    @Query("UPDATE conversations SET lastMessageId = :messageId, updatedAtMillis = :now WHERE id = :id")
    protected abstract suspend fun markRecent(id: Long, messageId: Long, now: Long)
}
