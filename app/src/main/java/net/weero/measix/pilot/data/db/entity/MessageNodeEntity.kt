package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_node",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversation_id", "node_index"])]
)
data class MessageNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("node_index")
    val nodeIndex: Int,
    @ColumnInfo("messages")
    val messages: String,  // JSON serialized List<UIMessage>
    @ColumnInfo("select_index")
    val selectIndex: Int,
    /**
     * Transcript payload schema version. `3` is the V3 Turn/Step/Tool typed schema. Runtime load
     * is fail-closed on any other value; only `Migration_10_11` writes legacy rows forward to 3.
     */
    @ColumnInfo("transcript_schema", defaultValue = "3")
    val transcriptSchema: Int = TRANSCRIPT_SCHEMA_VERSION,
) {
    companion object {
        /**
         * The only transcript payload schema the runtime may load. `Migration_10_11` writes legacy
         * rows forward to this value; any other value is corrupt and must fail closed on load.
         */
        const val TRANSCRIPT_SCHEMA_VERSION = 3
    }
}
