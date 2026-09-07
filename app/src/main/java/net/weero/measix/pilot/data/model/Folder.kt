package net.weero.measix.pilot.data.model

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 会话文件夹（助手内分组）。
 */
data class Folder(
    val id: Uuid = Uuid.random(),
    val assistantId: ConfigurationReference,
    val name: String,
    val sortIndex: Int = 0,
    val createAt: Instant = Instant.now(),
    val scope: ConfigurationScope = ConfigurationScope.Personal,
)
