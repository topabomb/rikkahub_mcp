package net.weero.measix.pilot.data.enterprise

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import kotlinx.serialization.Serializable

/** Captured session identity. Every use must be authorized again by the session owner. */
@Serializable
sealed interface RealmAccess {
    val scope: ConfigurationScope

    @Serializable
    data object Personal : RealmAccess {
        override val scope = ConfigurationScope.Personal
    }

    @Serializable
    data class Enterprise internal constructor(
        override val scope: ConfigurationScope.Enterprise,
        internal val sessionId: String,
    ) : RealmAccess
}

/** A rendered selection cannot regain authority after leaving and returning to the same session. */
@ConsistentCopyVisibility
data class RealmSelection internal constructor(
    internal val access: RealmAccess,
    internal val revision: Long,
)
