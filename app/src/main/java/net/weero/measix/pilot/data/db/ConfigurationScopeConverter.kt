package net.weero.measix.pilot.data.db

import androidx.room.TypeConverter
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.configurationScopeFromStorageKey
import net.weero.measix.pilot.data.configuration.storageKey

class ConfigurationScopeConverter {
    @TypeConverter
    fun encode(scope: ConfigurationScope): String = scope.storageKey()

    @TypeConverter
    fun decode(value: String): ConfigurationScope = configurationScopeFromStorageKey(value)
}
