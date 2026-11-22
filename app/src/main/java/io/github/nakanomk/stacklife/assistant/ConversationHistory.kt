package io.github.nakanomk.stacklife.assistant

import androidx.room.*
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.PlannerChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class HistoryRole { USER, ASSISTANT, NOTICE }

@Serializable internal enum class ProposalStatus { PROPOSED, APPLIED, DISCARDED, STALE, FAILED }
@Serializable internal data class ChatEvent(val status: ProposalStatus, val changes: List<PlannerChange>, val reason: String? = null)

internal data class ChatLine(val id: Long, val role: HistoryRole, val text: String = "",
    val images: List<ChatImage> = emptyList(), val event: ChatEvent? = null)

@Entity(tableName = "conversations", indices = [Index(value = ["lastMessageId"])])
internal data class ConversationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    val title: String,
    val updatedAtMillis: Long = 0,
    val lastMessageId: Long = 0,
    val scopeKey: String? = null,
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
    @ColumnInfo(defaultValue = "0") val payloadVersion: Int = 0,
    val eventJson: String? = null,
)

@Entity(tableName = "conversation_images",
    foreignKeys = [ForeignKey(entity = ConversationMessageRow::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["messageId", "ordinal"], unique = true)])
internal data class ConversationImageRow(@PrimaryKey val id: String, val messageId: Long, val ordinal: Int,
    val mime: String, val width: Int, val height: Int, val bytes: Long)

internal data class SavedChatLine(@Embedded val row: ConversationMessageRow,
    @Relation(parentColumn = "id", entityColumn = "messageId") val images: List<ConversationImageRow>) {
    fun line(): ChatLine {
        val event = if (row.payloadVersion == 1 && row.eventJson != null) try {
            Json.decodeFromString<ChatEvent>(row.eventJson)
        } catch (_: IllegalArgumentException) { null } else null
        val text = if (row.payloadVersion != 0 && event == null)
            uiText("This saved preview cannot be displayed.", "无法显示这条已保存的预览。") else row.text
        return ChatLine(row.id, row.role, text, images.sortedBy { it.ordinal }.map {
            ChatImage(it.id, it.mime, it.width, it.height, it.bytes)
        }, event)
    }
}

internal data class SentMessage(val conversationId: Long, val messageId: Long)

@Dao
internal abstract class ConversationHistory {
    // The global message sequence, unlike wall time, orders simultaneous sends and survives clock changes.
    @Query("SELECT * FROM conversations ORDER BY lastMessageId DESC")
    abstract fun observeConversations(): Flow<List<ConversationRow>>

    @Transaction
    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id ORDER BY id")
    protected abstract fun observeRows(id: Long): Flow<List<SavedChatLine>>

    fun observeMessages(id: Long): Flow<List<ChatLine>> = observeRows(id).map { rows ->
        withContext(Dispatchers.Default) { rows.map { it.line() } }
    }

    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id AND role IN ('USER', 'ASSISTANT') AND text != '' ORDER BY id DESC LIMIT :limit")
    abstract suspend fun recentDialogue(id: Long, limit: Int): List<ConversationMessageRow>

    @Query("SELECT eventJson FROM conversation_messages WHERE eventJson IS NOT NULL AND (instr(eventJson, '\"backgroundId\"') > 0 OR instr(eventJson, '\"imageId\"') > 0)")
    protected abstract suspend fun backgroundEvents(): List<String>

    suspend fun backgroundIds(): Set<String> = buildSet {
        fun collect(snapshot: io.github.nakanomk.stacklife.planner.ChangeSnapshot?) { when (snapshot) {
            is io.github.nakanomk.stacklife.planner.CountdownSnapshot -> snapshot.value.backgroundId?.let(::add)
            is io.github.nakanomk.stacklife.planner.AppearanceSnapshot -> addAll(snapshot.value.backgroundIds())
            else -> Unit
        } }
        for (json in backgroundEvents()) for (change in Json.decodeFromString<ChatEvent>(json).changes) {
            collect(change.before); collect(change.after)
        }
    }

    @Query("SELECT id FROM conversation_images")
    abstract suspend fun imageIds(): List<String>

    @Query("SELECT i.id FROM conversation_images i INNER JOIN conversation_messages m ON m.id = i.messageId WHERE m.conversationId = :id")
    protected abstract suspend fun threadImages(id: Long): List<String>

    @Query("DELETE FROM conversations WHERE id = :id")
    protected abstract suspend fun deleteRows(id: Long)

    @Transaction
    open suspend fun delete(id: Long): List<String> = threadImages(id).also { deleteRows(id) }

    @Transaction
    open suspend fun sendUser(threadId: Long?, scope: String, prompt: String, scopeKey: String?, images: List<ChatImage>): SentMessage {
        require((prompt.isNotBlank() || images.isNotEmpty()) && images.size <= MAX_CHAT_IMAGES)
        val title = prompt.trimStart().take(80).trimEnd { it.isWhitespace() || it.isHighSurrogate() }
        val id = threadId ?: insertConversation(ConversationRow(scope = scope, scopeKey = scopeKey,
            title = title.ifEmpty { uiText("Image message", "图片消息") }))
        val message = record(ConversationMessageRow(conversationId = id, role = HistoryRole.USER, text = prompt))
        if (images.isNotEmpty()) insertImages(images.mapIndexed { ordinal, image ->
            ConversationImageRow(image.id, message, ordinal, image.mime, image.width, image.height, image.bytes)
        })
        return SentMessage(id, message)
    }

    @Transaction
    open suspend fun append(id: Long, role: HistoryRole, text: String): Long? {
        require(text.isNotBlank())
        if (!exists(id)) return null
        return record(ConversationMessageRow(conversationId = id, role = role, text = text))
    }

    @Transaction
    open suspend fun propose(id: Long, event: ChatEvent): Long {
        require(event.status == ProposalStatus.PROPOSED && event.changes.isNotEmpty())
        return record(ConversationMessageRow(conversationId = id, role = HistoryRole.NOTICE, text = "",
            payloadVersion = 1, eventJson = Json.encodeToString(event)))
    }

    @Query("UPDATE conversation_messages SET eventJson = :payload WHERE id = :id AND role = 'NOTICE' AND payloadVersion = 1")
    abstract suspend fun updateEvent(id: Long, payload: String): Int

    private suspend fun record(row: ConversationMessageRow): Long {
        val message = insertMessage(row)
        markRecent(row.conversationId, message, System.currentTimeMillis())
        return message
    }

    @Insert protected abstract suspend fun insertImages(rows: List<ConversationImageRow>)

    @Insert protected abstract suspend fun insertConversation(row: ConversationRow): Long
    @Insert protected abstract suspend fun insertMessage(row: ConversationMessageRow): Long

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    protected abstract suspend fun exists(id: Long): Boolean

    @Query("UPDATE conversations SET lastMessageId = :messageId, updatedAtMillis = :now WHERE id = :id")
    protected abstract suspend fun markRecent(id: Long, messageId: Long, now: Long)
}
