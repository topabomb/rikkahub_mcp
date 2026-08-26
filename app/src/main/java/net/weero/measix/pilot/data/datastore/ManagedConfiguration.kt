package net.weero.measix.pilot.data.datastore

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.asr.ASRProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.model.QuickMessage
import net.weero.measix.pilot.data.model.Tag
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

private val MANAGED_ASSET_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private const val MAX_MANAGED_ENVELOPE_BYTES = 2 * 1024 * 1024

internal fun decodeManagedConfigurationEnvelope(raw: ByteArray): ManagedConfigurationEnvelope {
    require(raw.size <= MAX_MANAGED_ENVELOPE_BYTES) { "Managed configuration envelope is too large" }
    return JsonInstant.decodeFromString(raw.decodeToString())
}

internal enum class ManagedConfigurationState {
    ABSENT,
    ACTIVE,
    DEGRADED,
    BLOCKED,
}

internal sealed interface ManagedApplyResult {
    data class Applied(val generation: Long) : ManagedApplyResult
    data class Rejected(val reason: String) : ManagedApplyResult
}

/** A signed document aggregate. Its payload is intentionally not a serialised [Settings]. */
@Serializable
internal data class ManagedConfigurationEnvelope(
    val schemaVersion: Int,
    val keyId: String,
    val tenantId: String,
    val generation: Long,
    val expiresAtEpochMillis: Long? = null,
    val payload: ManagedConfigurationPayload,
    val signature: String,
)

@Serializable
internal data class ManagedConfigurationPayload(
    val records: List<ManagedConfigurationRecord> = emptyList(),
    val defaults: ManagedConfigurationDefaults = ManagedConfigurationDefaults(),
    val locks: Map<String, String> = emptyMap(),
    val assets: List<ManagedConfigurationAsset> = emptyList(),
    val assetBindings: List<ManagedAssistantAssetBinding> = emptyList(),
    val revoked: Boolean = false,
)

@Serializable
internal data class ManagedConfigurationRecord(
    val kind: ManagedConfigurationRecordKind,
    val id: String,
    val value: JsonObject,
)

internal enum class ManagedConfigurationRecordKind(val settingsPath: String) {
    PROVIDER("providers"),
    ASSISTANT("assistants"),
    ASSISTANT_TAG("assistantTags"),
    MCP_SERVER("mcpServers"),
    TTS_PROVIDER("ttsProviders"),
    ASR_PROVIDER("asrProviders"),
    SEARCH_SERVICE("searchServices"),
    MODE_INJECTION("modeInjections"),
    QUICK_MESSAGE("quickMessages"),
}

@Serializable
internal data class ManagedConfigurationDefaults(
    val chatModelId: Uuid? = null,
    val fastModelId: Uuid? = null,
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid? = null,
    val attachmentInspectionModelId: Uuid? = null,
    val compressModelId: Uuid? = null,
    val assistantId: Uuid? = null,
    val selectedSearchServiceId: Uuid? = null,
    val selectedTTSProviderId: Uuid? = null,
    val selectedASRProviderId: Uuid? = null,
)

@Serializable
internal data class ManagedConfigurationAsset(
    val id: String,
    val base64: String,
)

private fun ManagedConfigurationAsset.decode(): ByteArray = try {
    Base64.getDecoder().decode(base64)
} catch (error: IllegalArgumentException) {
    throw IllegalArgumentException("Managed asset is not base64: $id", error)
}

@Serializable
internal data class ManagedAssistantAssetBinding(
    val assistantId: Uuid,
    val avatarAssetId: String? = null,
    val backgroundAssetId: String? = null,
)

/** The verified, decoded and materialised managed view used only inside the Settings aggregate. */
internal data class ManagedConfigurationSnapshot(
    val state: ManagedConfigurationState,
    val generation: Long? = null,
    val tenantId: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val overlay: ManagedSettingsOverlay? = null,
    val failureReason: String? = null,
)

internal fun interface ManagedSnapshotVerifier {
    fun verify(
        raw: ByteArray,
        assetRoot: File,
        previous: ManagedConfigurationSnapshot?,
        assetUris: Map<String, String>?,
        nowMillis: Long,
    ): ManagedConfigurationSnapshot
}

internal data class ManagedConfigurationRuntime(
    val nowMillis: () -> Long,
    val verifier: ManagedSnapshotVerifier,
)

internal fun managedConfigurationRuntime() = ManagedConfigurationRuntime(
    nowMillis = System::currentTimeMillis,
    verifier = ManagedSnapshotVerifier { raw, assetRoot, previous, assetUris, now ->
        ManagedConfigurationVerifier.verify(
            raw = raw,
            assetRoot = assetRoot,
            previousGeneration = previous?.generation,
            previousTenantId = previous?.tenantId,
            nowMillis = now,
            assetUris = assetUris,
        )
    },
)

internal data class ManagedSettingsOverlay(
    val records: Settings,
    val defaults: ManagedConfigurationDefaults = ManagedConfigurationDefaults(),
    val access: SettingsAccessIndex = SettingsAccessIndex(),
)

private fun ManagedConfigurationDefaults.managedDefaultPaths(): Set<String> = setOfNotNull(
    chatModelId?.let { "defaults/chatModelId" },
    fastModelId?.let { "defaults/fastModelId" },
    titleModelId?.let { "defaults/titleModelId" },
    imageGenerationModelId?.let { "defaults/imageGenerationModelId" },
    attachmentInspectionModelId?.let { "defaults/attachmentInspectionModelId" },
    compressModelId?.let { "defaults/compressModelId" },
    assistantId?.let { "defaults/assistantId" },
    selectedSearchServiceId?.let { "defaults/selectedSearchServiceId" },
    selectedTTSProviderId?.let { "defaults/selectedTTSProviderId" },
    selectedASRProviderId?.let { "defaults/selectedASRProviderId" },
)

/** Verifies and atomically persists one managed document aggregate and its generation assets. */
internal class ManagedConfigurationStorage(
    context: Context,
    private val runtime: ManagedConfigurationRuntime = managedConfigurationRuntime(),
) {
    internal val root = File(context.filesDir, "managed_configuration")
    private val activeEnvelope = File(root, "active.envelope")

    suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        activeEnvelope.takeIf(File::isFile)?.let {
            require(it.length() <= MAX_MANAGED_ENVELOPE_BYTES) { "Managed configuration envelope is too large" }
            it.readBytes()
        }
    }

    suspend fun <T> publish(
        envelope: ByteArray,
        generation: Long,
        assets: List<ManagedConfigurationAsset>,
        validate: (assetUris: Map<String, String>) -> T,
    ) = withContext(Dispatchers.IO) {
        check(root.exists() || root.mkdirs()) { "Unable to create managed configuration storage" }
        val generationDir = File(root, "generation-$generation")
        val accepted = validate(assets.associate { asset ->
            asset.id to File(generationDir, "assets/${asset.id}").toUri().toString()
        })
        check(!generationDir.exists() || generationDir.deleteRecursively()) {
            "Unable to remove an unreferenced managed asset generation"
        }
        val staging = File(root, ".staging-$generation-${System.nanoTime()}")
        var envelopePublished = false
        try {
            check(staging.mkdirs()) { "Unable to create managed asset staging" }
            writeAssets(staging, assets)
            check(staging.renameTo(generationDir)) { "Unable to publish managed asset generation" }
            val stagedEnvelope = File(root, ".active-${System.nanoTime()}")
            stagedEnvelope.writeBytes(envelope)
            try {
                Files.move(stagedEnvelope.toPath(), activeEnvelope.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                if (stagedEnvelope.exists()) stagedEnvelope.delete()
            }
            envelopePublished = true
            accepted
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            if (!envelopePublished && generationDir.exists()) generationDir.deleteRecursively()
        }
    }

    suspend fun cleanupRetired(expectedEnvelope: ByteArray, activeGeneration: Long) = withContext(Dispatchers.IO) {
        if (activeEnvelope.isFile && activeEnvelope.readBytes().contentEquals(expectedEnvelope)) {
            cleanupGenerations(activeGeneration)
        }
    }

    suspend fun loadSnapshot(): ManagedConfigurationSnapshot = try {
        val envelope = load() ?: return ManagedConfigurationSnapshot(ManagedConfigurationState.ABSENT)
        val snapshot = verify(envelope)
        snapshot.generation?.let { generation -> cleanupRetired(envelope, generation) }
        snapshot
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        ManagedConfigurationSnapshot(
            state = ManagedConfigurationState.BLOCKED,
            failureReason = error.message ?: "managed_configuration_invalid",
        )
    }

    suspend fun prepare(
        envelope: ByteArray,
        previous: ManagedConfigurationSnapshot,
    ): ManagedConfigurationPreparation {
        val decoded = try {
            decodeManagedConfigurationEnvelope(envelope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return ManagedConfigurationPreparation.Rejected(error.message ?: "managed_configuration_invalid")
        }
        val snapshot = try {
            publish(envelope, decoded.generation, decoded.payload.assets) { assetUris ->
                verify(envelope, previous, assetUris)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return ManagedConfigurationPreparation.Rejected(error.message ?: "managed_configuration_persist_failed")
        }
        return ManagedConfigurationPreparation.Accepted(decoded.generation, snapshot)
    }

    private fun verify(
        envelope: ByteArray,
        previous: ManagedConfigurationSnapshot? = null,
        assetUris: Map<String, String>? = null,
    ): ManagedConfigurationSnapshot = runtime.verifier.verify(
        raw = envelope,
        assetRoot = root,
        previous = previous,
        assetUris = assetUris,
        nowMillis = runtime.nowMillis(),
    )

    private fun writeAssets(
        staging: File,
        assets: List<ManagedConfigurationAsset>,
    ) {
        assets.forEach { asset ->
            val bytes = asset.decode()
            val relative = "assets/${asset.id}"
            val target = File(staging, relative)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    private fun cleanupGenerations(activeGeneration: Long) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("generation-") && it.name != "generation-$activeGeneration" }
            .forEach(File::deleteRecursively)
    }
}

internal sealed interface ManagedConfigurationPreparation {
    data class Accepted(
        val generation: Long,
        val snapshot: ManagedConfigurationSnapshot,
    ) : ManagedConfigurationPreparation

    data class Rejected(val reason: String) : ManagedConfigurationPreparation
}

internal object ManagedConfigurationVerifier {
    fun verify(
        raw: ByteArray,
        assetRoot: File? = null,
        previousGeneration: Long?,
        previousTenantId: String? = null,
        nowMillis: Long,
        assetUris: Map<String, String>? = null,
        trustedKey: (keyId: String, tenantId: String) -> PublicKey? = ::trustedKey,
    ): ManagedConfigurationSnapshot {
        val envelope = decodeManagedConfigurationEnvelope(raw)
        require(envelope.schemaVersion == SCHEMA_VERSION) { "Unsupported managed configuration schema" }
        require(envelope.tenantId.isNotBlank()) { "Managed configuration tenant is missing" }
        require(previousTenantId == null || envelope.tenantId == previousTenantId) {
            "Managed configuration tenant changed"
        }
        require(envelope.generation >= 0) { "Managed configuration generation is invalid" }
        require(envelope.expiresAtEpochMillis == null || envelope.expiresAtEpochMillis >= 0) {
            "Managed configuration expiry is invalid"
        }
        require(previousGeneration == null || envelope.generation > previousGeneration) {
            "Managed configuration generation is not monotonic"
        }
        verifySignature(envelope, trustedKey)
        if (envelope.payload.revoked) {
            require(
                envelope.payload.records.isEmpty() && envelope.payload.locks.isEmpty() &&
                    envelope.payload.assets.isEmpty() && envelope.payload.assetBindings.isEmpty() &&
                    envelope.payload.defaults == ManagedConfigurationDefaults(),
            ) {
                "Managed revocation cannot carry configuration"
            }
            return ManagedConfigurationSnapshot(
                state = ManagedConfigurationState.ABSENT,
                generation = envelope.generation,
                tenantId = envelope.tenantId,
            )
        }
        require(envelope.payload.assets.map(ManagedConfigurationAsset::id).distinct().size == envelope.payload.assets.size) {
            "Managed configuration contains duplicate assets"
        }
        require(envelope.payload.assets.all { it.id.matches(MANAGED_ASSET_ID) }) { "Managed asset id is invalid" }
        val resolvedAssetUris = assetUris ?: verifyAssets(
            envelope,
            requireNotNull(assetRoot) { "Managed asset root is required" },
        )
        val overlay = decodeOverlay(envelope.payload, resolvedAssetUris)
        val state = if (envelope.expiresAtEpochMillis?.let { it <= nowMillis } == true) {
            ManagedConfigurationState.DEGRADED
        } else {
            ManagedConfigurationState.ACTIVE
        }
        return ManagedConfigurationSnapshot(
            state = state,
            generation = envelope.generation,
            tenantId = envelope.tenantId,
            expiresAtEpochMillis = envelope.expiresAtEpochMillis,
            overlay = overlay,
        )
    }

    private fun verifySignature(
        envelope: ManagedConfigurationEnvelope,
        trustedKey: (keyId: String, tenantId: String) -> PublicKey?,
    ) {
        val key = requireNotNull(trustedKey(envelope.keyId, envelope.tenantId)) {
            "Managed configuration key is not trusted"
        }
        val signature = try {
            Base64.getDecoder().decode(envelope.signature)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Managed configuration signature is not base64", error)
        }
        val signed = JsonInstant.encodeToString(envelope.copy(signature = "")).encodeToByteArray()
        check(Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(signed)
            verify(signature)
        }) { "Managed configuration signature is invalid" }
    }

    private fun verifyAssets(
        envelope: ManagedConfigurationEnvelope,
        assetRoot: File,
    ): Map<String, String> {
        val generationDir = File(assetRoot, "generation-${envelope.generation}")
        return envelope.payload.assets.associate { asset ->
            val file = File(generationDir, "assets/${asset.id}")
            require(file.isFile && file.readBytes().contentEquals(asset.decode())) {
                "Managed asset content is invalid: ${asset.id}"
            }
            asset.id to file.toUri().toString()
        }
    }

    private fun decodeOverlay(
        payload: ManagedConfigurationPayload,
        assetUris: Map<String, String>,
    ): ManagedSettingsOverlay {
        require(payload.records.map { it.kind to it.id }.distinct().size == payload.records.size) {
            "Managed configuration contains duplicate records"
        }
        val providers = payload.records.decode(ManagedConfigurationRecordKind.PROVIDER, ProviderSetting::id)
        val assistants = payload.records.decode(ManagedConfigurationRecordKind.ASSISTANT, Assistant::id) { assistant ->
            require(assistant.workspaceId == null) { "Managed assistant workspace is not allowed" }
            require(assistant.background == null && assistant.avatar !is Avatar.Image) {
                "Managed assistant media requires a signed asset binding"
            }
        }.associateByTo(linkedMapOf(), Assistant::id)
        val tags = payload.records.decode(ManagedConfigurationRecordKind.ASSISTANT_TAG, Tag::id)
        val mcp = payload.records.decode(ManagedConfigurationRecordKind.MCP_SERVER, McpServerConfig::id)
        val tts = payload.records.decode(ManagedConfigurationRecordKind.TTS_PROVIDER, TTSProviderSetting::id)
        val asr = payload.records.decode(ManagedConfigurationRecordKind.ASR_PROVIDER, ASRProviderSetting::id)
        val search = payload.records.decode(ManagedConfigurationRecordKind.SEARCH_SERVICE, SearchServiceOptions::id)
        val injections = payload.records.decode(ManagedConfigurationRecordKind.MODE_INJECTION, PromptInjection.ModeInjection::id)
        val quickMessages = payload.records.decode(ManagedConfigurationRecordKind.QUICK_MESSAGE, QuickMessage::id)
        require(payload.assetBindings.map(ManagedAssistantAssetBinding::assistantId).distinct().size == payload.assetBindings.size) {
            "Managed configuration contains duplicate asset bindings"
        }
        payload.assetBindings.forEach { binding ->
            val assistant = requireNotNull(assistants[binding.assistantId]) { "Managed asset binding target is missing" }
            assistants[binding.assistantId] = assistant.copy(
                avatar = binding.avatarAssetId?.let { assetId ->
                    Avatar.Image(requireNotNull(assetUris[assetId]) { "Managed avatar asset is missing" })
                } ?: assistant.avatar,
                background = binding.backgroundAssetId?.let { assetId ->
                    requireNotNull(assetUris[assetId]) { "Managed background asset is missing" }
                } ?: assistant.background,
            )
        }
        require(payload.locks.all { (path, reason) -> path.isNotBlank() && reason.isNotBlank() }) {
            "Managed lock is invalid"
        }
        val records = Settings(
            providers = providers,
            assistants = assistants.values.toList(),
            assistantTags = tags,
            mcpServers = mcp,
            ttsProviders = tts,
            asrProviders = asr,
            searchServices = search,
            modeInjections = injections,
            quickMessages = quickMessages,
        )
        validateReferences(payload.defaults, records)
        val lockablePaths = records.recordValues().flatMapTo(linkedSetOf()) { (kind, values) ->
            values.keys.map { id -> "records/${kind.settingsPath}/$id" }
        } + payload.defaults.managedDefaultPaths()
        require(payload.locks.keys.all(lockablePaths::contains)) {
            "Managed lock path is not a managed record or default"
        }
        return ManagedSettingsOverlay(
            records = records,
            defaults = payload.defaults,
            access = SettingsAccessIndex.managed(
                records,
                payload.defaults.managedDefaultPaths(),
                payload.locks,
            ),
        )
    }

    private inline fun <reified T> List<ManagedConfigurationRecord>.decode(
        kind: ManagedConfigurationRecordKind,
        idOf: (T) -> Uuid,
        validate: (T) -> Unit = {},
    ): List<T> = filter { it.kind == kind }.map { record ->
        val value = JsonInstant.decodeFromJsonElement<T>(record.value)
        require(idOf(value) == Uuid.parse(record.id)) { "Managed ${kind.name} id mismatch" }
        validate(value)
        value
    }

    private fun validateReferences(
        defaults: ManagedConfigurationDefaults,
        records: Settings,
    ) {
        val modelIds = (records.providers + DEFAULT_PROVIDERS).flatMap { it.models }.mapTo(hashSetOf()) { it.id }
        val knownAssistants = records.assistants.mapTo(linkedSetOf(), Assistant::id).apply {
            addAll(DEFAULT_ASSISTANTS.map(Assistant::id))
        }
        val knownInjections = records.modeInjections.mapTo(linkedSetOf(), PromptInjection.ModeInjection::id).apply {
            addAll(DEFAULT_MODE_INJECTIONS.map(PromptInjection.ModeInjection::id))
        }
        val knownSearchServices = records.searchServices.map(SearchServiceOptions::id) +
            SearchServiceOptions.DEFAULT.id
        val knownTtsProviders = records.ttsProviders.map(TTSProviderSetting::id) +
            DEFAULT_TTS_PROVIDERS.map(TTSProviderSetting::id)
        val knownAsrProviders = records.asrProviders.map(ASRProviderSetting::id)
        listOfNotNull(
            defaults.chatModelId,
            defaults.fastModelId,
            defaults.titleModelId,
            defaults.imageGenerationModelId,
            defaults.attachmentInspectionModelId,
            defaults.compressModelId,
        ).requireKnown(modelIds, "Managed default model is not in its generation")
        defaults.assistantId.requireKnown(knownAssistants, "Managed default assistant is missing")
        defaults.selectedSearchServiceId.requireKnown(knownSearchServices, "Managed default search service is missing")
        defaults.selectedTTSProviderId.requireKnown(knownTtsProviders, "Managed default TTS provider is missing")
        defaults.selectedASRProviderId.requireKnown(knownAsrProviders, "Managed default ASR provider is missing")
        val tagIds = records.assistantTags.mapTo(hashSetOf(), Tag::id)
        val mcpIds = records.mcpServers.mapTo(hashSetOf(), McpServerConfig::id)
        val quickMessageIds = records.quickMessages.mapTo(hashSetOf(), QuickMessage::id)
        records.assistants.forEach { assistant ->
            assistant.chatModelId.requireKnown(modelIds, "Managed assistant model is missing")
            assistant.tags.requireKnown(tagIds, "Managed assistant tag is missing")
            assistant.mcpServers.requireKnown(mcpIds, "Managed assistant MCP server is missing")
            assistant.modeInjectionIds.requireKnown(knownInjections, "Managed assistant mode injection is missing")
            assistant.quickMessageIds.requireKnown(quickMessageIds, "Managed assistant quick message is missing")
            assistant.allowedSubAssistantIds.requireKnown(knownAssistants, "Managed assistant sub-assistant is missing")
            require(assistant.enabledSkills.isEmpty()) {
                "Managed assistant skills require signed managed skill references"
            }
        }
    }

    private fun Uuid?.requireKnown(known: Collection<Uuid>, error: String) {
        if (this != null) require(this in known) { error }
    }

    private fun Iterable<Uuid>.requireKnown(known: Collection<Uuid>, error: String) {
        require(all(known::contains)) { error }
    }

    private const val SCHEMA_VERSION = 1
    private const val TRUSTED_KEY_ID = "rikkahub-managed-v1"
    private const val TRUSTED_TENANT_ID = "rikkahub"
    private const val TRUSTED_KEY = "MCowBQYDK2VwAyEAMMMQpThlNAbzMayYVw9nHs4atsKWywnSPV+9HBRiuaE="

    /** A package trust anchor; an envelope cannot nominate its own public key. */
    private val trustedPublicKey: PublicKey? by lazy {
        runCatching {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(TRUSTED_KEY)))
        }.getOrNull()
    }

    private fun trustedKey(keyId: String, tenantId: String): PublicKey? =
        trustedPublicKey?.takeIf { keyId == TRUSTED_KEY_ID && tenantId == TRUSTED_TENANT_ID }
}
