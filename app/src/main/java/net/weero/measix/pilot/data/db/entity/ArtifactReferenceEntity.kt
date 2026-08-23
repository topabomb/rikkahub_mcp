package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 消息历史对 artifact 的引用投影。可从消息 JSON 全量重建，永不当事实源。 */
enum class ArtifactReferenceType {
    ATTACHMENT,
    TOOL_OUTPUT,
}

@Entity(
    tableName = "artifact_reference",
    indices = [
        Index("artifact_id"),
        Index("node_id"),
        Index(value = ["artifact_id", "node_id", "reference_type"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ArtifactEntity::class,
            parentColumns = ["id"],
            childColumns = ["artifact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArtifactReferenceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("rowId")
    val rowId: Long = 0,
    @ColumnInfo("artifact_id")
    val artifactId: Long,
    @ColumnInfo("node_id")
    val nodeId: String,
    @ColumnInfo("reference_type")
    val referenceType: String,
)
