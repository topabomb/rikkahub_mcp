package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.ChatFontFamily
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomChatFontServiceTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var settingsStore: SettingsStore
    private lateinit var settings: Settings
    private lateinit var service: CustomChatFontService

    @Before
    fun setUp() {
        root = createTempDirectory("custom-chat-font").toFile()
        context = TestContext(ApplicationProvider.getApplicationContext(), root)
        settingsStore = mockk()
        settings = Settings()
        coEvery { settingsStore.updateAtomicAndGet(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(settings).also { settings = it }
        }
        service = CustomChatFontService(context, settingsStore)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `remove clears only the observed reference and never leaves the owned file`() = runTest {
        val managed = managedFont("chat_font.old.ttf")
        val unrelated = File(root, "upload/chat_font.keep.ttf").apply {
            parentFile?.mkdirs()
            writeText("not owned by the font domain")
        }
        settings = Settings(
            displaySetting = settings.displaySetting.copy(
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/${managed.name}",
                chatCustomFontName = "old.ttf",
            )
        )

        val result = service.remove("fonts/${managed.name}")

        assertEquals(ChatFontFamily.DEFAULT, result.chatFontFamily)
        assertEquals("", result.chatCustomFontPath)
        assertFalse(managed.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `stale removal retains a newer import while collecting the obsolete file`() = runTest {
        val obsolete = managedFont("chat_font.old.ttf")
        val current = managedFont("chat_font.new.ttf")
        settings = Settings(
            displaySetting = settings.displaySetting.copy(
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/${current.name}",
                chatCustomFontName = "new.ttf",
            )
        )

        val result = service.remove("fonts/${obsolete.name}")

        assertEquals("fonts/${current.name}", result.chatCustomFontPath)
        assertTrue(current.exists())
        assertFalse(obsolete.exists())
    }

    @Test
    fun `caller cancellation after settings commit cannot strand the old font`() = runTest {
        val managed = managedFont("chat_font.cancel.ttf")
        settings = Settings(
            displaySetting = settings.displaySetting.copy(
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/${managed.name}",
                chatCustomFontName = "cancel.ttf",
            )
        )
        val caller = CompletableDeferred<Job>()
        coEvery { settingsStore.updateAtomicAndGet(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(settings).also { committed ->
                settings = committed
                caller.await().cancel()
            }
        }

        val operation = async {
            caller.complete(requireNotNull(currentCoroutineContext()[Job]))
            service.remove("fonts/${managed.name}")
        }
        var cancelled = false
        try {
            operation.await()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals("", settings.displaySetting.chatCustomFontPath)
        assertFalse(managed.exists())
    }

    @Test
    fun `empty import is discarded before settings can reference it`() = runTest {
        val invalid = File(root, "empty.ttf").apply { createNewFile() }

        var failure: Throwable? = null
        try {
            service.import(Uri.fromFile(invalid))
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure != null)
        coVerify(exactly = 0) { settingsStore.updateAtomicAndGet(any()) }
        assertTrue(File(root, "fonts").listFiles().isNullOrEmpty())
    }

    @Test
    fun `reconcile retains a referenced legacy font and removes every unowned file`() = runTest {
        val legacy = managedFont("legacy-name.ttf")
        val obsolete = managedFont("chat_font.obsolete.ttf")
        val interrupted = managedFont("chat_font.import.interrupted.tmp")
        settings = Settings(
            displaySetting = settings.displaySetting.copy(
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/${legacy.name}",
                chatCustomFontName = "legacy.ttf",
            )
        )

        service.reconcile()

        assertEquals("fonts/${legacy.name}", settings.displaySetting.chatCustomFontPath)
        assertTrue(legacy.exists())
        assertFalse(obsolete.exists())
        assertFalse(interrupted.exists())
    }

    @Test
    fun `reconcile clears a broken path even when the selected family is not custom`() = runTest {
        settings = Settings(
            displaySetting = settings.displaySetting.copy(
                chatFontFamily = ChatFontFamily.SERIF,
                chatCustomFontPath = "fonts/missing.ttf",
                chatCustomFontName = "missing.ttf",
            )
        )

        service.reconcile()

        assertEquals(ChatFontFamily.DEFAULT, settings.displaySetting.chatFontFamily)
        assertEquals("", settings.displaySetting.chatCustomFontPath)
        assertEquals("", settings.displaySetting.chatCustomFontName)
    }

    private fun managedFont(name: String): File = File(root, "fonts/$name").apply {
        parentFile?.mkdirs()
        writeText("font payload")
    }

    private class TestContext(base: Context, private val testFilesDir: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = testFilesDir
    }
}
