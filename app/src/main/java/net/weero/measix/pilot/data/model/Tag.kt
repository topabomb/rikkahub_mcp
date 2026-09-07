package net.weero.measix.pilot.data.model

import kotlinx.serialization.Serializable
import me.rerere.common.configuration.ConfigurationReference

@Serializable
data class Tag(
    val id: ConfigurationReference,
    val name: String,
)
