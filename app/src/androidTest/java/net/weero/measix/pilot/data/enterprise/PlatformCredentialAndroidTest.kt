package net.weero.measix.pilot.data.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PlatformCredentialAndroidTest {
    @Test
    fun encryptedCredentialReopensWithAndroidKeystoreAndRejectsChangedRevision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "platform-credential-test-${Uuid.random()}").apply { check(mkdirs()) }
        try {
            val credential = PlatformRefreshCredential("ses_${Uuid.random()}", "test-private-refresh", 1_900_000_000_000L, "idem_${Uuid.random()}")
            val version = EnterpriseAppliedStore(root).prepareCredential(credential)
            assertEquals(credential, EnterpriseAppliedStore(root).credential(version, credential.sessionId))
            val encrypted = File(root, "credentials/${version.revision}/credential.bin").readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains(credential.refreshToken))
            assertThrows(java.security.GeneralSecurityException::class.java) {
                EnterpriseCredentialCipher().decrypt(encrypted, Uuid.random().toString())
            }
        } finally { root.deleteRecursively() }
    }
}
