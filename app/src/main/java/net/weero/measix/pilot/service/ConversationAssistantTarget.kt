package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference

/** The assistant shown by one original conversation view, including a deleted or unavailable reference. */
@ConsistentCopyVisibility
data class ConversationAssistantTarget internal constructor(
    internal val conversation: ConversationCommandTarget,
    val assistantId: ConfigurationReference,
)
