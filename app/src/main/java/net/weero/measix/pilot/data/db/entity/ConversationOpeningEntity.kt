package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Cold immutable root content; deleting or replacing message nodes cannot remove an opening. */
@Entity(
    tableName = "conversation_opening",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)],
)
data class ConversationOpeningEntity(
    @PrimaryKey @ColumnInfo("conversation_id") val conversationId: String,
    val payload: String,
)
