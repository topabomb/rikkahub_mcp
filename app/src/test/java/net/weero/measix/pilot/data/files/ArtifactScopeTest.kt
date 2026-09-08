package net.weero.measix.pilot.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class ArtifactScopeTest : ArtifactStoreLifecycleTestBase() {
    private val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "user")

    @Test
    fun `creation and clone preserve the original principal independently of later selection`() = runTest {
        val folder = folder()
        val original = store.createFromBytes(enterprise, byteArrayOf(1, 2), "result.bin", folder = folder, origin = ArtifactOrigin.SYSTEM)
        val clone = store.copyFilePreservingOrigin(store.file(original.entity), original.entity.mimeType, "clone.bin", folder)
        assertEquals(enterprise, database.artifactDao().getById(original.entity.id)?.scope)
        assertEquals(enterprise, database.artifactDao().getById(clone.entity.id)?.scope)
        assertEquals(ArtifactOrigin.SYSTEM.name, clone.entity.origin)
        assertTrue(original.entity.relativePath != clone.entity.relativePath)
    }

    @Test
    fun `reference and draft retention reject another domain or another enterprise user`() = runTest {
        val owned = store.createText(enterprise, "enterprise attachment", folder = folder())
        val node = node(owned)
        assertEquals(owned.entity.id, store.prepareReferenceDelta(enterprise, listOf(node), emptyList()).references.single().artifactId)
        val denied = listOf(ConfigurationScope.Personal, enterprise.copy(userId = "other"),
            enterprise.copy(authority = EnterpriseAuthority("platform:example", "deployment")))
        for (scope in denied) {
            assertTrue(runCatching { store.prepareReferenceDelta(scope, listOf(node), emptyList()) }.exceptionOrNull() is ArtifactProjectionException)
            assertTrue(runCatching { store.retainInputUris(scope, setOf(owned.uri.toString())) }.exceptionOrNull() is ArtifactProjectionException)
        }
        store.retainInputUris(enterprise, setOf(owned.uri.toString())).close()
        store.discardUnpublished(owned).requireDiscarded("scope test")
    }

    @Test
    fun `failed reference rebuild leaves the old projection and completion marker untouched`() = runTest {
        val owned = store.createText(enterprise, "enterprise attachment", folder = folder())
        val node = node(owned)
        val conversationId = Uuid.random().toString()
        database.conversationDao().insert(ConversationEntity(
            id = conversationId, scope = ConfigurationScope.Personal,
            assistantId = ConfigurationReference.random().toString(), title = "invalid cross-domain reference",
            createAt = 1, updateAt = 1, chatSuggestions = "[]", isPinned = false,
        ))
        database.messageNodeDao().insertAll(listOf(MessageNodeEntity(node.id.toString(), conversationId, 0,
            JsonInstant.encodeToString(node.messages), 0)))
        database.artifactReferenceDao().insertAll(listOf(net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity(
            artifactId = owned.entity.id, nodeId = node.id.toString(), referenceType = "ATTACHMENT",
        )))
        assertTrue(runCatching { store.ensureReferenceProjection() }.exceptionOrNull() is ArtifactProjectionException)
        assertFalse(store.isReferenceProjectionCurrent())
        assertTrue(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
        assertEquals(enterprise, database.artifactDao().getById(owned.entity.id)?.scope)
    }

    private fun node(owned: OwnedArtifact) = MessageNode(id = Uuid.random(), messages = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Document(
            url = owned.uri.toString(), fileName = owned.entity.displayName, mime = owned.entity.mimeType,
        ))),
    ))
}
