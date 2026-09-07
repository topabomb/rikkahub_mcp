package net.weero.measix.pilot.data.model

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope

/** A namespace in the durable memory store, never an authorization credential. */
data class MemoryAddress(val scope: ConfigurationScope, val owner: MemoryOwner)

sealed interface MemoryOwner {
    data object RealmShared : MemoryOwner
    data class Assistant(val id: ConfigurationReference) : MemoryOwner

    companion object {
        internal fun fromStorageId(value: String): MemoryOwner =
            if (value == REALM_SHARED_ID) RealmShared else Assistant(ConfigurationReference.parse(value))
    }
}

private const val REALM_SHARED_ID = "__global__"

internal val MemoryOwner.storageId: String
    get() = when (this) {
        MemoryOwner.RealmShared -> REALM_SHARED_ID
        is MemoryOwner.Assistant -> id.toString()
    }

internal fun Assistant.memoryAddress(scope: ConfigurationScope): MemoryAddress =
    MemoryAddress(scope, if (useGlobalMemory) MemoryOwner.RealmShared else MemoryOwner.Assistant(id))
