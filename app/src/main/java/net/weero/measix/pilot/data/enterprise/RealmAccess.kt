package net.weero.measix.pilot.data.enterprise

import net.weero.measix.pilot.data.configuration.ConfigurationScope

/** Captured session identity. Every use must be authorized again by the session owner. */
sealed interface RealmAccess {
    val scope: ConfigurationScope

    data object Personal : RealmAccess {
        override val scope = ConfigurationScope.Personal
    }

    data class Enterprise internal constructor(
        override val scope: ConfigurationScope.Enterprise,
        internal val sessionId: String,
    ) : RealmAccess
}
