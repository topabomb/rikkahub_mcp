package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_context_admission",
    foreignKeys = [ForeignKey(
        entity = MessageNodeEntity::class,
        parentColumns = ["id"], childColumns = ["owner_node_id"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["owner_node_id", "owner_message_id", "step_id"], unique = true)],
)
data class ConversationContextAdmissionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("owner_node_id") val ownerNodeId: String,
    @ColumnInfo("owner_message_id") val ownerMessageId: String,
    @ColumnInfo("step_id") val stepId: String,
    @ColumnInfo("window_start_node_id") val windowStartNodeId: String,
    @ColumnInfo("window_start_message_id") val windowStartMessageId: String,
    @ColumnInfo("selection_payload") val selectionPayload: String?,
)

@Entity(
    tableName = "conversation_context_use",
    primaryKeys = ["admission_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = ConversationContextAdmissionEntity::class,
            parentColumns = ["id"], childColumns = ["admission_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationModelContextEntity::class,
            parentColumns = ["id"], childColumns = ["entry_id"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [Index("entry_id")],
)
data class ConversationContextUseEntity(
    @ColumnInfo("admission_id") val admissionId: String,
    val ordinal: Int,
    @ColumnInfo("entry_id") val entryId: String,
    val role: String,
    val placement: String,
)
