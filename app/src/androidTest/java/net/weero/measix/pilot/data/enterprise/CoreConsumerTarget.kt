package net.weero.measix.pilot.data.enterprise

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

/** A release consumer run cannot silently reuse an enrollment from another deployment. */
internal object CoreConsumerTarget {
    suspend fun requireTarget(sessions: EnterpriseSessionController, access: RealmAccess.Enterprise) {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("coreConsumerOrigin") ?: return
        val deployment = requireNotNull(arguments.getString("coreConsumerDeploymentId"))
        assertEquals(deployment, access.scope.authority.deploymentId)
        assertEquals(origin, requireNotNull(sessions.platformConfiguration(access).session.platform).connection.origin)
    }
}
