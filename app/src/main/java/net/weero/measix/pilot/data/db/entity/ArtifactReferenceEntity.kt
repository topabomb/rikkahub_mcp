package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Derived references from durable messages and context entries; never an independent fact source. */
enum class ArtifactReferenceType {
    ATTACHMENT,
    TOOL_OUTPUT,
    CONTEXT,
}

@Entity(
    tableName = "artifact_reference",
    indices = [
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
