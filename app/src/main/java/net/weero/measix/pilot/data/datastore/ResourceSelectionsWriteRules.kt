package net.weero.measix.pilot.data.datastore

import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot

internal fun requireResourceSelectionsWriteAllowed(
    before: ResourceSelections,
    proposed: ResourceSelections,
    resolved: ResolvedConfiguration,
) {
    ResourceSelectionSlot.entries.forEach { slot ->
        val reference = slot.reference(proposed)
        // Clearing an override must remain possible even when no usable default exists.
        if (reference != null && reference != slot.reference(before)) {
            val choice = resolved.choice(slot, reference)
            if (!choice.isAvailable) throw SettingsLockedException("selections/${slot.name}", choice.unavailableReason!!.name)
        }
    }
    (proposed.favoriteModels - before.favoriteModels.toSet()).forEach { reference ->
        val access = resolved.access(ConfigurationCategory.MODEL, reference)
        if (!access.canSelect) throw SettingsLockedException("selections/favoriteModels", access.unavailableReason!!.name)
    }
}
