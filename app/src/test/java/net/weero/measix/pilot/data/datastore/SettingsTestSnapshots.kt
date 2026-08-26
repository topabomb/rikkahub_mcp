package net.weero.measix.pilot.data.datastore

internal fun Settings.toEffectiveSettingsSnapshot(
    revision: Long = 1,
    managedState: ManagedConfigurationState = ManagedConfigurationState.ABSENT,
) = EffectiveSettingsSnapshot(
    settings = this,
    access = SettingsAccessIndex(),
    revision = revision,
    managedState = managedState,
)
