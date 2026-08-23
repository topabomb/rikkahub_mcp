package net.weero.measix.pilot.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "system_meta")
data class SystemMetaEntity(
    @PrimaryKey
    val key: String,
    val value: String,
)
