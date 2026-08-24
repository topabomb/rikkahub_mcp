package net.weero.measix.pilot.data.sync

import net.weero.measix.pilot.data.datastore.ChatFontFamily
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.ArtifactReferencePolicy

/** Settings-only archives cannot carry identities owned by the database or local payload domains. */
internal object BackupSettingsPolicy {
    fun withoutLocalPayloadReferences(settings: Settings): Settings {
        val localArtifactRoots = ArtifactReferencePolicy.roots(settings)
            .filterNot(::isPortableReference)
            .toSet()
        val detached = ArtifactReferencePolicy.detach(settings, localArtifactRoots)
        val display = detached.displaySetting
        return detached.copy(
            displaySetting = if (display.chatCustomFontPath.isNotBlank() ||
                display.chatFontFamily == ChatFontFamily.CUSTOM
            ) {
                display.copy(
                    chatFontFamily = ChatFontFamily.DEFAULT,
                    chatCustomFontPath = "",
                    chatCustomFontName = "",
                )
            } else {
                display
            },
            pendingAssistantDeletions = emptyList(),
        )
    }

    private fun isPortableReference(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true)
}
