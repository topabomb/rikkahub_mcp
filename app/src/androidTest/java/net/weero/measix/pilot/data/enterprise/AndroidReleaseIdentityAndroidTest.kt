package net.weero.measix.pilot.data.enterprise

import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the installed target's fields through its class loader, avoiding test APK constant inlining. */
@RunWith(AndroidJUnit4::class)
class AndroidReleaseIdentityAndroidTest {
    @Suppress("DEPRECATION")
    @Test
    fun installedApkMatchesPreservedRelease() {
        val encoded = InstrumentationRegistry.getArguments().getString("androidReleaseIdentity")
        assumeTrue("Explicit fixed release record required", encoded != null)
        val record = JSONObject(String(Base64.decode(requireNotNull(encoded), Base64.NO_WRAP), Charsets.UTF_8))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val build = context.assets.open("android-build-identity.json").use {
            JSONObject(it.reader(Charsets.UTF_8).readText())
        }
        assertEquals(record.getString("sourceCommit"), build.getString("sourceCommit"))
        assertFalse("Installed APK was built from dirty source", build.getBoolean("sourceDirty"))
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        assertEquals(record.getString("applicationId"), context.packageName)
        assertEquals(record.getString("versionName"), info.versionName)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        assertEquals(record.getLong("versionCode"), versionCode)
        val config = context.classLoader.loadClass("net.weero.measix.pilot.BuildConfig")
        fun field(name: String): Any = requireNotNull(config.getField(name).get(null))
        assertEquals(context.packageName, field("APPLICATION_ID"))
        assertEquals(record.getString("versionName"), field("VERSION_NAME"))
        assertEquals(record.getInt("versionCode").toString(), field("VERSION_CODE"))
        assertEquals(record.getInt("platformContractVersion"), field("PLATFORM_CONTRACT_VERSION"))
        assertEquals(record.getString("coreBaselineVersion"), field("CORE_BASELINE_VERSION"))
        assertEquals(record.getString("baselineHash"), field("PLATFORM_CONTRACT_BASELINE_HASH"))
        val supported = record.getJSONArray("supportedPlatformContractVersions")
        assertArrayEquals(IntArray(supported.length()) { supported.getInt(it) }, field("SUPPORTED_PLATFORM_CONTRACT_VERSIONS") as IntArray)
        assertEquals(record.getString("variant"), field("BUILD_TYPE"))
        val signatures = requireNotNull(if (Build.VERSION.SDK_INT >= 28) requireNotNull(info.signingInfo).apkContentsSigners else info.signatures)
        assertEquals("Exactly one actual APK signer", 1, signatures.size)
        assertEquals(record.getString("signingCertificateSha256"), hash(signatures.single().toByteArray()))
        val installed = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        installed.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(record.getString("apkSha256"), "sha256:" + digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun hash(bytes: ByteArray): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
