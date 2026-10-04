package net.weero.measix.pilot.data.model

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterInitialContext
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot

/** Exceeds a CursorWindow in UTF-8 and UTF-16, with supplementary code points across SQL slices. */
internal fun largeConversationOpening() = ConversationOpening(
    assistant = ConfigurationReference.parse("managed~dep_example~assistant_one") as ConfigurationReference.Enterprise,
    releaseId = "release-original", generation = 7, snapshotHash = "a".repeat(64),
    definition = EnterpriseStarter("starter_one", "assistant_one", "Original opening", "  user prompt {{literal}}\r\n",
        openingSnapshot = EnterpriseStarterOpeningSnapshot(1,
            "System {{literal}}\r\n", listOf(
                EnterpriseStarterInitialContext("large", "数😀".repeat(430_000) + "\r\nEND"),
                EnterpriseStarterInitialContext("empty", ""),
            ))),
)
