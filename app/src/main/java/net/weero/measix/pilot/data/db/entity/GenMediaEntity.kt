package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.weero.measix.pilot.data.configuration.ConfigurationScope

@Entity(indices = [Index(value = ["path"]), Index(value = ["create_at"])])
data class GenMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo(name = "type", defaultValue = TYPE_IMAGE_GENERATION)
    val type: String = TYPE_IMAGE_GENERATION,
    @ColumnInfo("source_paths")
    val sourcePaths: String? = null,
    @ColumnInfo("scope", defaultValue = "personal")
    val scope: ConfigurationScope = ConfigurationScope.Personal,
) {
    companion object {
        const val TYPE_IMAGE_GENERATION = "image_generation"
        const val TYPE_IMAGE_EDIT = "image_edit"
    }
}
