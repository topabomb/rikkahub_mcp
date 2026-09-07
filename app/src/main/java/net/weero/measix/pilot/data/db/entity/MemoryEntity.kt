package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.weero.measix.pilot.data.configuration.ConfigurationScope

@Entity(indices = [Index(value = ["assistant_id"])])
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("scope", defaultValue = "personal")
    val scope: ConfigurationScope = ConfigurationScope.Personal,
)
