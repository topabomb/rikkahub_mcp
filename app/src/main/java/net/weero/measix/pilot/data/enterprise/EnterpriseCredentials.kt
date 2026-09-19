package net.weero.measix.pilot.data.enterprise

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable

@Serializable
internal data class EnterpriseCredentialVersion(val revision: String, val hash: String)

@Serializable
internal data class PlatformSessionDetails(
    val connection: PlatformConnection,
    val deviceId: String,
    val credential: EnterpriseCredentialVersion,
)

@Serializable
internal data class PendingPlatformEnrollment(
    val sessionId: String,
    val userId: String,
    val expiresAtMillis: Long,
    val platform: PlatformSessionDetails,
)

@Serializable
internal data class PlatformRefreshCredential(
    val sessionId: String,
    val refreshToken: String,
    val refreshExpiresAtMillis: Long,
    val pendingIdempotencyKey: String? = null,
) {
    override fun toString() = "PlatformRefreshCredential(sessionId=$sessionId, pending=${pendingIdempotencyKey != null})"
}

internal class PlatformAccessToken(val value: String, val expiresAtMillis: Long) {
    override fun toString() = "PlatformAccessToken(redacted)"
}

internal data class PlatformEnrollmentAttempt(val id: String, val installationId: String)

internal data class PlatformSessionContext(
    val sessionId: String,
    val userId: String,
    val expiresAtMillis: Long,
    val platform: PlatformSessionDetails,
)

internal data class PlatformRefreshAttempt(val context: PlatformSessionContext, val credential: PlatformRefreshCredential)

internal data class PlatformLogoutRequest(val connection: PlatformConnection, val credential: PlatformRefreshCredential)

internal data class PlatformConfigurationInput(val session: EnterpriseSession, val candidate: EnterpriseCandidate?)

/** Encryption is a storage primitive; the Session owner alone chooses and publishes credential versions. */
internal class EnterpriseCredentialCipher(private val key: () -> SecretKey = EnterpriseCredentialKey::get) {
    fun encrypt(plain: ByteArray, revision: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(revision.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12) { "enterprise_credential_iv_size" }
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(encoded: ByteArray, revision: String): ByteArray {
        require(encoded.size >= 29 && encoded[0] == 1.toByte()) { "invalid_enterprise_credential_ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(revision.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encoded, 13, encoded.size - 13)
    }
}

private object EnterpriseCredentialKey {
    private const val ALIAS = "measix.enterprise.credentials"

    @Synchronized
    fun get(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
