package net.weero.measix.pilot.data.datastore

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.URI
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedConfigurationStorageTest {
    private lateinit var filesDir: File
    private lateinit var storage: ManagedConfigurationStorage

    @Before
    fun setUp() {
        filesDir = createTempDirectory("managed-configuration").toFile()
        storage = ManagedConfigurationStorage(TestContext(ApplicationProvider.getApplicationContext(), filesDir))
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `publish exposes complete assets with its envelope and cleanup preserves a newer generation`() = runTest {
        val firstEnvelope = "first-envelope".encodeToByteArray()
        val firstAsset = asset("avatar", "first-avatar".encodeToByteArray())

        val stagedAssetUris = storage.publish(firstEnvelope, generation = 1, assets = listOf(firstAsset)) { assetUris ->
            assetUris
        }

        assertArrayEquals(firstEnvelope, requireNotNull(storage.load()))
        assertArrayEquals(
            firstAssetBytes(firstAsset),
            File(URI(requireNotNull(stagedAssetUris["avatar"]))).readBytes(),
        )
        val secondEnvelope = "second-envelope".encodeToByteArray()
        storage.publish(secondEnvelope, generation = 2, assets = emptyList()) { }

        storage.cleanupRetired(firstEnvelope, activeGeneration = 1)
        assertTrue(generationDirectory(1).isDirectory)
        assertTrue(generationDirectory(2).isDirectory)

        storage.cleanupRetired(secondEnvelope, activeGeneration = 2)
        assertFalse(generationDirectory(1).exists())
        assertTrue(generationDirectory(2).isDirectory)
    }

    @Test
    fun `failed validation rolls back the staged generation without publishing an envelope`() = runTest {
        var failure: IllegalStateException? = null
        try {
            storage.publish(
                envelope = "rejected-envelope".encodeToByteArray(),
                generation = 3,
                assets = listOf(asset("avatar", "rejected-avatar".encodeToByteArray())),
            ) {
                error("test validation failure")
            }
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertTrue(failure?.message?.contains("test validation failure") == true)
        assertNull(storage.load())
        assertFalse(generationDirectory(3).exists())
    }

    @Test
    fun `a retried generation replaces an unreferenced process-death residue`() = runTest {
        generationDirectory(5).apply {
            mkdirs()
            File(this, "interrupted").writeText("orphaned")
        }
        val asset = asset("avatar", "retried-avatar".encodeToByteArray())

        storage.publish("retried-envelope".encodeToByteArray(), generation = 5, assets = listOf(asset)) { }

        assertFalse(File(generationDirectory(5), "interrupted").exists())
        assertTrue(File(generationDirectory(5), "assets/avatar").isFile)
    }

    private fun generationDirectory(generation: Long) = File(filesDir, "managed_configuration/generation-$generation")

    private fun asset(id: String, bytes: ByteArray) = ManagedConfigurationAsset(
        id = id,
        base64 = Base64.getEncoder().encodeToString(bytes),
    )

    private fun firstAssetBytes(asset: ManagedConfigurationAsset): ByteArray = Base64.getDecoder().decode(asset.base64)

    private class TestContext(base: Context, private val testFilesDir: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = testFilesDir
    }
}
