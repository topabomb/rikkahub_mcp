package net.weero.measix.pilot.service

import android.content.Context
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.*
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import java.math.BigDecimal
import java.math.RoundingMode

internal data class EnterpriseJoinConfirmation(val id: Uuid, val platformOrigin: String)

internal enum class EnterpriseResetPath { CONNECTED, STORAGE_FAILURE }

internal enum class EnterpriseConfigurationDefaultKind {
    ASSISTANT,
    CHAT_MODEL,
    FAST_MODEL,
    TITLE_MODEL,
    IMAGE_GENERATION,
    ATTACHMENT_INSPECTION_MODEL,
    SUGGESTION_MODEL,
    COMPRESS_MODEL,
    TTS,
    ASR,
}

internal enum class EnterpriseConfigurationReferenceState { AVAILABLE, UNSET, UNAVAILABLE }

internal data class EnterpriseConfigurationDefaultUiModel(
    val kind: EnterpriseConfigurationDefaultKind,
    val displayName: String?,
    val state: EnterpriseConfigurationReferenceState,
)

internal enum class EnterpriseConfigurationPolicyKind {
    LOCAL_PROVIDERS,
    LOCAL_TTS,
    LOCAL_ASR,
    LOCAL_MCP,
    LOCAL_ASSISTANTS,
}

internal data class EnterpriseConfigurationPolicyUiModel(
    val kind: EnterpriseConfigurationPolicyKind,
    val allowed: Boolean,
)

internal enum class EnterpriseConfigurationResourceKind {
    PROVIDER,
    CHAT_MODEL,
    IMAGE_GENERATOR,
    TTS,
    ASR,
    MCP,
    ASSISTANT,
    STARTER,
    MEMORY_SEED,
    GATEWAY,
}

internal enum class EnterpriseConfigurationResourceFactKind {
    PROVIDER,
    MAX_IMAGES,
    ALLOWED_SIZES,
    ASSISTANT,
    DESCRIPTION,
}

internal data class EnterpriseConfigurationResourceFactUiModel(
    val kind: EnterpriseConfigurationResourceFactKind,
    val value: String,
)

internal data class EnterpriseConfigurationResourceUiModel(
    val key: String,
    val displayName: String,
    val enabled: Boolean,
    val facts: List<EnterpriseConfigurationResourceFactUiModel> = emptyList(),
)

internal data class EnterpriseConfigurationResourceGroupUiModel(
    val kind: EnterpriseConfigurationResourceKind,
    val items: List<EnterpriseConfigurationResourceUiModel>,
    /** Count-only resources deliberately omit item payloads from the UI projection. */
    val redactedItemCount: Int = 0,
) {
    val itemCount: Int get() = items.size + redactedItemCount
}

internal data class EnterpriseConfigurationDetailsUiModel(
    val enterpriseName: String,
    val phase: EnterpriseSessionPhase,
    val generation: Long,
    val lastSyncMillis: Long?,
    val platformOrigin: String,
    val defaults: List<EnterpriseConfigurationDefaultUiModel>,
    val policies: List<EnterpriseConfigurationPolicyUiModel>,
    val resources: List<EnterpriseConfigurationResourceGroupUiModel>,
)

internal enum class EnterpriseUpdateCategory { ANNOUNCEMENT, MAINTENANCE, NOTICE }
internal enum class EnterpriseUpdateSeverity { INFO, WARNING, CRITICAL }

internal data class EnterpriseUpdateSummaryUiModel(
    val id: String,
    val title: String,
    val publishedAt: String,
    val content: String,
    val markdown: Boolean,
    val category: EnterpriseUpdateCategory,
    val severity: EnterpriseUpdateSeverity,
)
internal data class EnterpriseUpdatesUiModel(
    val timezone: String,
    val items: List<EnterpriseUpdateSummaryUiModel>,
)

internal enum class EnterpriseBudgetCapabilityKind { MODEL, TTS, ASR, MCP, IMAGE_GENERATION }

internal enum class EnterpriseBudgetPeriodKind { DAY, WEEK, MONTH, LIFETIME }

internal enum class EnterpriseBudgetMeterKind {
    REQUESTS,
    REQUESTED_IMAGES,
    INPUT_TOKENS,
    OUTPUT_TOKENS,
    CACHED_TOKENS,
    TOTAL_TOKENS,
    CHARACTERS,
    AUDIO_SECONDS,
}

internal enum class EnterpriseBudgetCompleteness { EXACT, PARTIAL, UNKNOWN }

internal enum class EnterpriseBudgetAvailability {
    UNLIMITED,
    AVAILABLE,
    NEAR_LIMIT,
    EXHAUSTED,
    RECONCILING,
    UNAVAILABLE,
}

internal data class EnterpriseBudgetLimitUiModel(
    val period: EnterpriseBudgetPeriodKind,
    val meter: EnterpriseBudgetMeterKind,
    val limit: String,
    val used: String,
    val reserved: String,
    val remaining: String,
    val occupiedFraction: Float,
    val resetAt: String?,
)

internal data class EnterpriseBudgetUsageUiModel(
    val meter: EnterpriseBudgetMeterKind,
    val quantity: String,
    val completeness: EnterpriseBudgetCompleteness,
)

internal data class EnterpriseBudgetCapabilityUiModel(
    val capability: EnterpriseBudgetCapabilityKind,
    val availability: EnterpriseBudgetAvailability,
    val primaryLimit: EnterpriseBudgetLimitUiModel?,
    val additionalLimitCount: Int,
    val usageSummary: List<EnterpriseBudgetUsageUiModel>,
)

internal data class EnterpriseBudgetSummaryUiModel(
    val timezone: String,
    val items: List<EnterpriseBudgetCapabilityUiModel>,
    val totalInFlightRequests: Long,
    val asOf: String,
)

internal fun projectEnterpriseBudget(value: PlatformUserBudgetView): EnterpriseBudgetSummaryUiModel {
    val items = value.items.map { item ->
        val projectedLimits = item.limits.map { limit ->
            val used = limit.used.toBigDecimal()
            val reserved = limit.reserved.toBigDecimal()
            val maximum = limit.limit.toBigDecimal()
            val occupied = if (maximum.signum() == 0) {
                BigDecimal.ONE
            } else {
                used.add(reserved).divide(maximum, 8, RoundingMode.HALF_UP)
            }
            EnterpriseBudgetLimitUiModel(
                period = limit.period.toUiModel(),
                meter = limit.meter.toUiModel(),
                limit = limit.limit,
                used = limit.used,
                reserved = limit.reserved,
                remaining = limit.remaining,
                occupiedFraction = occupied.coerceIn(BigDecimal.ZERO, BigDecimal.ONE).toFloat(),
                resetAt = limit.resetAt,
            )
        }
        val primaryLimit = projectedLimits.maxByOrNull { it.occupiedFraction }
        val availability = when {
            item.mode == PlatformBudgetMode.UNLIMITED -> EnterpriseBudgetAvailability.UNLIMITED
            primaryLimit == null -> EnterpriseBudgetAvailability.UNAVAILABLE
            item.status == PlatformBudgetStatus.PENDING_RECONCILIATION -> EnterpriseBudgetAvailability.RECONCILING
            item.status == PlatformBudgetStatus.EXHAUSTED || primaryLimit.occupiedFraction >= 1f ->
                EnterpriseBudgetAvailability.EXHAUSTED
            primaryLimit.occupiedFraction >= 0.8f -> EnterpriseBudgetAvailability.NEAR_LIMIT
            else -> EnterpriseBudgetAvailability.AVAILABLE
        }
        EnterpriseBudgetCapabilityUiModel(
            capability = item.capability.toUiModel(),
            availability = availability,
            primaryLimit = primaryLimit,
            additionalLimitCount = (projectedLimits.size - 1).coerceAtLeast(0),
            usageSummary = if (item.mode == PlatformBudgetMode.UNLIMITED) {
                item.usageMeters
                    .sortedBy { item.capability.usageMeterPriority(it.meter) }
                    .take(2)
                    .map { usage ->
                        EnterpriseBudgetUsageUiModel(
                            meter = usage.meter.toUiModel(),
                            quantity = usage.quantity,
                            completeness = usage.completeness.toUiModel(),
                        )
                    }
            } else {
                emptyList()
            },
        )
    }
    return EnterpriseBudgetSummaryUiModel(
        timezone = value.timezone,
        items = items,
        totalInFlightRequests = value.items.sumOf(PlatformBudgetCapabilityView::inFlightRequests),
        asOf = value.asOf,
    )
}

private fun PlatformBudgetCapability.toUiModel(): EnterpriseBudgetCapabilityKind = when (this) {
    PlatformBudgetCapability.MODEL -> EnterpriseBudgetCapabilityKind.MODEL
    PlatformBudgetCapability.TTS -> EnterpriseBudgetCapabilityKind.TTS
    PlatformBudgetCapability.ASR -> EnterpriseBudgetCapabilityKind.ASR
    PlatformBudgetCapability.MCP -> EnterpriseBudgetCapabilityKind.MCP
    PlatformBudgetCapability.IMAGE_GENERATION -> EnterpriseBudgetCapabilityKind.IMAGE_GENERATION
}

private fun PlatformBudgetPeriod.toUiModel(): EnterpriseBudgetPeriodKind = when (this) {
    PlatformBudgetPeriod.DAY -> EnterpriseBudgetPeriodKind.DAY
    PlatformBudgetPeriod.WEEK -> EnterpriseBudgetPeriodKind.WEEK
    PlatformBudgetPeriod.MONTH -> EnterpriseBudgetPeriodKind.MONTH
    PlatformBudgetPeriod.LIFETIME -> EnterpriseBudgetPeriodKind.LIFETIME
}

private fun PlatformUsageMeter.toUiModel(): EnterpriseBudgetMeterKind = when (this) {
    PlatformUsageMeter.REQUESTS -> EnterpriseBudgetMeterKind.REQUESTS
    PlatformUsageMeter.REQUESTED_IMAGES -> EnterpriseBudgetMeterKind.REQUESTED_IMAGES
    PlatformUsageMeter.INPUT_TOKENS -> EnterpriseBudgetMeterKind.INPUT_TOKENS
    PlatformUsageMeter.OUTPUT_TOKENS -> EnterpriseBudgetMeterKind.OUTPUT_TOKENS
    PlatformUsageMeter.CACHED_TOKENS -> EnterpriseBudgetMeterKind.CACHED_TOKENS
    PlatformUsageMeter.TOTAL_TOKENS -> EnterpriseBudgetMeterKind.TOTAL_TOKENS
    PlatformUsageMeter.CHARACTERS -> EnterpriseBudgetMeterKind.CHARACTERS
    PlatformUsageMeter.AUDIO_SECONDS -> EnterpriseBudgetMeterKind.AUDIO_SECONDS
}

private fun PlatformUsageCompleteness.toUiModel(): EnterpriseBudgetCompleteness = when (this) {
    PlatformUsageCompleteness.EXACT -> EnterpriseBudgetCompleteness.EXACT
    PlatformUsageCompleteness.PARTIAL -> EnterpriseBudgetCompleteness.PARTIAL
    PlatformUsageCompleteness.UNKNOWN -> EnterpriseBudgetCompleteness.UNKNOWN
}

private fun PlatformBudgetCapability.usageMeterPriority(meter: PlatformUsageMeter): Int {
    val preferred = when (this) {
        PlatformBudgetCapability.MODEL -> listOf(PlatformUsageMeter.TOTAL_TOKENS, PlatformUsageMeter.REQUESTS)
        PlatformBudgetCapability.TTS -> listOf(PlatformUsageMeter.CHARACTERS, PlatformUsageMeter.REQUESTS)
        PlatformBudgetCapability.ASR -> listOf(PlatformUsageMeter.AUDIO_SECONDS, PlatformUsageMeter.REQUESTS)
        PlatformBudgetCapability.MCP -> listOf(PlatformUsageMeter.REQUESTS)
        PlatformBudgetCapability.IMAGE_GENERATION ->
            listOf(PlatformUsageMeter.REQUESTED_IMAGES, PlatformUsageMeter.REQUESTS)
    }
    return preferred.indexOf(meter).takeIf { it >= 0 } ?: (preferred.size + meter.ordinal)
}

private data class EnterpriseConfigurationReferenceTarget(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

internal fun projectEnterpriseConfigurationDetails(
    identity: EnterpriseIdentity,
    phase: EnterpriseSessionPhase,
    generation: Long,
    lastSyncMillis: Long?,
    platformOrigin: String,
    configuration: EnterpriseConfiguration,
): EnterpriseConfigurationDetailsUiModel {
    require(phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE))

    fun reference(
        kind: EnterpriseConfigurationDefaultKind,
        id: String?,
        targets: List<EnterpriseConfigurationReferenceTarget>,
    ): EnterpriseConfigurationDefaultUiModel {
        val target = id?.let { reference -> targets.firstOrNull { it.id == reference } }
        return EnterpriseConfigurationDefaultUiModel(
            kind = kind,
            displayName = target?.name,
            state = when {
                id == null -> EnterpriseConfigurationReferenceState.UNSET
                target?.enabled == true -> EnterpriseConfigurationReferenceState.AVAILABLE
                else -> EnterpriseConfigurationReferenceState.UNAVAILABLE
            },
        )
    }

    fun fact(kind: EnterpriseConfigurationResourceFactKind, value: String?): EnterpriseConfigurationResourceFactUiModel? =
        value?.takeIf(String::isNotBlank)?.let { EnterpriseConfigurationResourceFactUiModel(kind, it) }
    fun resourceKey(kind: EnterpriseConfigurationResourceKind, index: Int): String =
        "$generation:${kind.name}:$index"

    val providers = configuration.providers.map { EnterpriseConfigurationReferenceTarget(it.id, it.name, it.enabled) }
    val chatModels = configuration.models.filter { it.type == me.rerere.ai.provider.ModelType.CHAT }
    val chatModelTargets = chatModels.map { EnterpriseConfigurationReferenceTarget(it.id, it.name, it.enabled) }
    val imageTargets = configuration.imageGenerators.map { EnterpriseConfigurationReferenceTarget(it.id, it.name, it.enabled) }
    val ttsTargets = configuration.tts.map { EnterpriseConfigurationReferenceTarget(it.id, it.name, it.enabled) }
    val asrTargets = configuration.asr.map { EnterpriseConfigurationReferenceTarget(it.id, it.name, it.enabled) }
    val assistantTargets = configuration.assistants.map { EnterpriseConfigurationReferenceTarget(it.id, it.name, it.enabled) }
    val providerNames = providers.associate { it.id to it.name }
    val assistantNames = assistantTargets.associate { it.id to it.name }
    val defaults = configuration.defaults

    val resourceGroups = listOf(
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.PROVIDER,
            configuration.providers.mapIndexed { index, provider ->
                EnterpriseConfigurationResourceUiModel(
                    key = resourceKey(EnterpriseConfigurationResourceKind.PROVIDER, index),
                    displayName = provider.name,
                    enabled = provider.enabled,
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.CHAT_MODEL,
            chatModels.mapIndexed { index, model ->
                EnterpriseConfigurationResourceUiModel(
                    key = resourceKey(EnterpriseConfigurationResourceKind.CHAT_MODEL, index),
                    displayName = model.name,
                    enabled = model.enabled,
                    facts = listOfNotNull(
                        fact(EnterpriseConfigurationResourceFactKind.PROVIDER, model.providerId?.let(providerNames::get)),
                    ),
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.IMAGE_GENERATOR,
            configuration.imageGenerators.mapIndexed { index, image ->
                EnterpriseConfigurationResourceUiModel(
                    key = resourceKey(EnterpriseConfigurationResourceKind.IMAGE_GENERATOR, index),
                    displayName = image.name,
                    enabled = image.enabled,
                    facts = listOfNotNull(
                        fact(EnterpriseConfigurationResourceFactKind.MAX_IMAGES, image.maxImagesPerRequest.toString()),
                        fact(EnterpriseConfigurationResourceFactKind.ALLOWED_SIZES, image.allowedSizes.joinToString()),
                    ),
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.TTS,
            configuration.tts.mapIndexed { index, value ->
                EnterpriseConfigurationResourceUiModel(
                    resourceKey(EnterpriseConfigurationResourceKind.TTS, index),
                    value.name,
                    value.enabled,
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.ASR,
            configuration.asr.mapIndexed { index, value ->
                EnterpriseConfigurationResourceUiModel(
                    resourceKey(EnterpriseConfigurationResourceKind.ASR, index),
                    value.name,
                    value.enabled,
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.MCP,
            configuration.mcpServers.mapIndexed { index, value ->
                EnterpriseConfigurationResourceUiModel(
                    resourceKey(EnterpriseConfigurationResourceKind.MCP, index),
                    value.name,
                    value.enabled,
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.ASSISTANT,
            configuration.assistants.mapIndexed { index, value ->
                EnterpriseConfigurationResourceUiModel(
                    resourceKey(EnterpriseConfigurationResourceKind.ASSISTANT, index),
                    value.name,
                    value.enabled,
                    listOfNotNull(fact(EnterpriseConfigurationResourceFactKind.DESCRIPTION, value.description)),
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.STARTER,
            configuration.starters.mapIndexed { index, value ->
                EnterpriseConfigurationResourceUiModel(
                    resourceKey(EnterpriseConfigurationResourceKind.STARTER, index),
                    value.title,
                    value.enabled,
                    listOfNotNull(
                        fact(
                            EnterpriseConfigurationResourceFactKind.ASSISTANT,
                            assistantNames[value.assistantId],
                        ),
                        fact(EnterpriseConfigurationResourceFactKind.DESCRIPTION, value.description),
                    ),
                )
            },
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            kind = EnterpriseConfigurationResourceKind.MEMORY_SEED,
            items = emptyList(),
            redactedItemCount = configuration.memorySeeds.size,
        ),
        EnterpriseConfigurationResourceGroupUiModel(
            EnterpriseConfigurationResourceKind.GATEWAY,
            configuration.gateways.mapIndexed { index, value ->
                EnterpriseConfigurationResourceUiModel(
                    resourceKey(EnterpriseConfigurationResourceKind.GATEWAY, index),
                    value.name,
                    enabled = true,
                )
            },
        ),
    )

    return EnterpriseConfigurationDetailsUiModel(
        enterpriseName = identity.enterpriseName,
        phase = phase,
        generation = generation,
        lastSyncMillis = lastSyncMillis,
        platformOrigin = platformOrigin,
        defaults = listOf(
            reference(EnterpriseConfigurationDefaultKind.ASSISTANT, defaults.assistantId, assistantTargets),
            reference(EnterpriseConfigurationDefaultKind.CHAT_MODEL, defaults.chatModelId, chatModelTargets),
            reference(EnterpriseConfigurationDefaultKind.FAST_MODEL, defaults.fastModelId, chatModelTargets),
            reference(EnterpriseConfigurationDefaultKind.TITLE_MODEL, defaults.titleModelId, chatModelTargets),
            reference(EnterpriseConfigurationDefaultKind.IMAGE_GENERATION, defaults.imageGenerationModelId, imageTargets),
            reference(
                EnterpriseConfigurationDefaultKind.ATTACHMENT_INSPECTION_MODEL,
                defaults.attachmentInspectionModelId,
                chatModelTargets,
            ),
            reference(EnterpriseConfigurationDefaultKind.SUGGESTION_MODEL, defaults.suggestionModelId, chatModelTargets),
            reference(EnterpriseConfigurationDefaultKind.COMPRESS_MODEL, defaults.compressModelId, chatModelTargets),
            reference(EnterpriseConfigurationDefaultKind.TTS, defaults.ttsId, ttsTargets),
            reference(EnterpriseConfigurationDefaultKind.ASR, defaults.asrId, asrTargets),
        ),
        policies = listOf(
            EnterpriseConfigurationPolicyUiModel(
                EnterpriseConfigurationPolicyKind.LOCAL_PROVIDERS,
                configuration.policy.allowLocalProviders,
            ),
            EnterpriseConfigurationPolicyUiModel(
                EnterpriseConfigurationPolicyKind.LOCAL_TTS,
                configuration.policy.allowLocalTts,
            ),
            EnterpriseConfigurationPolicyUiModel(
                EnterpriseConfigurationPolicyKind.LOCAL_ASR,
                configuration.policy.allowLocalAsr,
            ),
            EnterpriseConfigurationPolicyUiModel(
                EnterpriseConfigurationPolicyKind.LOCAL_MCP,
                configuration.policy.allowLocalMcp,
            ),
            EnterpriseConfigurationPolicyUiModel(
                EnterpriseConfigurationPolicyKind.LOCAL_ASSISTANTS,
                configuration.policy.allowLocalAssistants,
            ),
        ),
        resources = resourceGroups,
    )
}

internal data class EnterpriseOverview(
    val selection: RealmSelection?,
    val phase: EnterpriseSessionPhase?,
    val enterpriseName: String?,
    val userName: String?,
    val access: RealmAccess.Enterprise?,
    val generation: Long?,
    val lastSyncMillis: Long?,
    val failure: String?,
    val exitFailure: EnterpriseExitFailure?,
    val switching: Boolean,
    val resetPath: EnterpriseResetPath? = null,
    val reset: EnterpriseDataResetProgress? = null,
    val enrollmentRecoveryFailure: String? = null,
    val recoveryLogoutFailure: String? = null,
    val exitReason: EnterpriseExitReason? = null,
    val platformOrigin: String? = null,
    val configurationDetails: EnterpriseConfigurationDetailsUiModel? = null,
)

/** Native enterprise UI commands share the existing source, Session, synchronization and exit owners. */
internal class EnterpriseApplicationService(
    private val sessions: EnterpriseSessionController,
    private val synchronization: EnterpriseSynchronizationService,
    private val exit: EnterpriseExitService,
    private val dataReset: EnterpriseDataResetService,
    private val portals: PortalDocumentRegistry,
    private val recovery: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
    private val media: PortalMediaStore,
    private val terminals: net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime,
    private val speech: SpeechApplicationService,
    private val platform: PlatformEnterpriseService,
) {
    private data class Switching(val request: RealmSwitchRequest, val result: Deferred<RealmSelection>)
    private val mutex = Mutex()
    private val switching = MutableStateFlow<Switching?>(null)
    private val joinMutex = Mutex()
    /** Prevents an old-origin Portal document from registering after a new connection is committed. */
    private val portalConnectionMutex = Mutex()
    private var pendingJoin: Pair<EnterpriseJoinConfirmation, EnrollmentMaterial.Platform>? = null
    private val enrollmentRecoveryFailure = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            try {
                recovery.awaitReady()
                platform.recoverPlatformAccess()?.let { synchronization.synchronize(it) }
                enrollmentRecoveryFailure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("EnterpriseEnrollment", "Platform session recovery could not synchronize", error)
                enrollmentRecoveryFailure.value = error.userVisibleDiagnostic()
            }
        }
    }

    private fun observePresentation(): Flow<EnterprisePresentation> =
        combine(sessions.state, sessions.selectionRevision) { _, _ -> Unit }
            .map { sessions.readPresentation() }
            .distinctUntilChanged()

    // Task progress must remain observable while Session admission waits for host teardown.
    fun observe(): Flow<EnterpriseOverview> = combine(observePresentation(), exit.failure, switching,
        combine(enrollmentRecoveryFailure, dataReset.progress) { resume, reset -> resume to reset },
        combine(exit.recoveryLogoutFailure, platform.pendingLogoutFailure) { exitFailure, pendingFailure ->
            exitFailure to pendingFailure
        }) {
            presentation, exitFailure, activeSwitch, resumeAndReset, logoutFailures ->
            val (resumeFailure, resetProgress) = resumeAndReset
            val available = presentation.state as? EnterpriseState.Available
            val manifest = available?.manifest
            val identity = manifest?.session?.identity ?: manifest?.lastIdentity
            val access = manifest?.session?.let { RealmAccess.Enterprise(it.identity.scope, it.id) }
            val selection = presentation.selection
            val platformOrigin = manifest?.session?.platform?.connection?.origin
            EnterpriseOverview(
                selection = selection,
                phase = manifest?.phase,
                enterpriseName = identity?.enterpriseName,
                userName = identity?.userName,
                access = access,
                generation = manifest?.applied?.generation,
                lastSyncMillis = manifest?.lastConfigurationSyncMillis,
                failure = (presentation.state as? EnterpriseState.Failed)?.reason,
                exitFailure = exitFailure,
                switching = activeSwitch != null,
                resetPath = when {
                    presentation.state is EnterpriseState.Loading -> null
                    presentation.state is EnterpriseState.Failed -> EnterpriseResetPath.STORAGE_FAILURE
                    else -> EnterpriseResetPath.CONNECTED
                },
                reset = resetProgress,
                enrollmentRecoveryFailure = resumeFailure,
                recoveryLogoutFailure = logoutFailures.first.takeIf { manifest?.session == null } ?: logoutFailures.second,
                exitReason = manifest?.exitReason,
                platformOrigin = platformOrigin,
                configurationDetails = if (
                    access != null && identity != null && platformOrigin != null &&
                    available.configuration != null &&
                    manifest.phase in setOf(EnterpriseSessionPhase.READY, EnterpriseSessionPhase.OFFLINE)
                ) {
                    projectEnterpriseConfigurationDetails(
                        identity = identity,
                        phase = manifest.phase,
                        generation = requireNotNull(manifest.applied).generation,
                        lastSyncMillis = manifest.lastConfigurationSyncMillis,
                        platformOrigin = platformOrigin,
                        configuration = available.configuration,
                    )
                } else null,
            )
        }.distinctUntilChanged()

    suspend fun join(text: String): EnterpriseJoinConfirmation? {
        recovery.awaitReady()
        val material = EnrollmentMaterialParser().parse(text)
        return joinMutex.withLock {
            EnterpriseJoinConfirmation(Uuid.random(), material.platformOrigin).also { pendingJoin = it to material }
        }
    }

    suspend fun dismissJoin(confirmation: EnterpriseJoinConfirmation) = joinMutex.withLock {
        if (pendingJoin?.first == confirmation) pendingJoin = null
    }

    suspend fun confirmJoin(confirmation: EnterpriseJoinConfirmation) {
        recovery.awaitReady()
        val material = joinMutex.withLock {
            val pending = pendingJoin?.takeIf { it.first == confirmation }
                ?: throw EnterpriseConfigurationException("enterprise_enrollment_replaced")
            pendingJoin = null
            pending.second
        }
        val access = platform.enroll(material, android.os.Build.MODEL, net.weero.measix.pilot.BuildConfig.VERSION_NAME)
        enrollmentRecoveryFailure.value = null
        val applied = synchronization.synchronize(access)
        if (applied.configuration != null) {
            val selection = requireNotNull(sessions.readPresentation().selection)
            switchRealm(RealmSwitchRequest(selection, access))
        }
    }
    suspend fun synchronize(access: RealmAccess.Enterprise) {
        recovery.awaitReady()
        synchronization.synchronize(access)
        enrollmentRecoveryFailure.value = null
    }
    suspend fun changeAddress(request: EnterpriseAddressChangeRequest, origin: String) {
        portalConnectionMutex.withLock {
            recovery.awaitReady()
            if (sessions.readPresentation().selection != request.selection) {
                throw EnterpriseConfigurationException("enterprise_selection_revoked")
            }
            val normalized = EnrollmentMaterialParser.normalizeOrigin(origin)
            if (sessions.platformContext(request.access.sessionId).platform.connection.origin == normalized) return
            platform.changeAddress(request, normalized)
            portals.closeAndAwait(request.access, PortalCloseReason.CONNECTION_CHANGED)
        }
    }
    suspend fun recentUpdates(selection: RealmSelection, access: RealmAccess.Enterprise): EnterpriseUpdatesUiModel {
        recovery.awaitReady()
        if (sessions.readPresentation().selection != selection) {
            throw EnterpriseConfigurationException("enterprise_selection_revoked")
        }
        val result = platform.recentUpdates(access)
        if (sessions.readPresentation().selection != selection) {
            throw EnterpriseConfigurationException("enterprise_selection_revoked")
        }
        return EnterpriseUpdatesUiModel(result.enterpriseTimezone,
            result.items.take(5).map { EnterpriseUpdateSummaryUiModel(
                it.enterpriseUpdateId, it.title, it.publishedAt, it.content,
                it.contentFormat == PlatformEnterpriseUpdateContentFormat.MARKDOWN,
                when (it.category) {
                    PlatformEnterpriseUpdateCategory.ANNOUNCEMENT -> EnterpriseUpdateCategory.ANNOUNCEMENT
                    PlatformEnterpriseUpdateCategory.MAINTENANCE -> EnterpriseUpdateCategory.MAINTENANCE
                    PlatformEnterpriseUpdateCategory.NOTICE -> EnterpriseUpdateCategory.NOTICE
                },
                when (it.severity) {
                    PlatformEnterpriseUpdateSeverity.INFO -> EnterpriseUpdateSeverity.INFO
                    PlatformEnterpriseUpdateSeverity.WARNING -> EnterpriseUpdateSeverity.WARNING
                    PlatformEnterpriseUpdateSeverity.CRITICAL -> EnterpriseUpdateSeverity.CRITICAL
                },
            ) })
    }

    suspend fun budgets(selection: RealmSelection, access: RealmAccess.Enterprise): EnterpriseBudgetSummaryUiModel {
        recovery.awaitReady()
        if (sessions.readPresentation().selection != selection) {
            throw EnterpriseConfigurationException("enterprise_selection_revoked")
        }
        val result = platform.budgets(access)
        if (sessions.readPresentation().selection != selection) {
            throw EnterpriseConfigurationException("enterprise_selection_revoked")
        }
        return projectEnterpriseBudget(result)
    }
    fun runtimeUsageChanges(): Flow<RealmAccess.Enterprise> = platform.runtimeUsageChanged
    suspend fun localDataReset(request: EnterpriseDataResetRequest) = dataReset.reset(request)
    suspend fun retryLocalDataReset() = dataReset.retryReset()

    suspend fun captureExitRequest(): EnterpriseExitRequest? = exit.captureRequest()
    suspend fun exit(request: EnterpriseExitRequest): EnterpriseExitResult = exit.exit(request)
    suspend fun retryExit(failure: EnterpriseExitFailure) { exit.retry(failure) }

    suspend fun openPortal(context: Context, selection: RealmSelection, onClosed: (PortalClosure) -> Unit): PortalWebView {
        return openPortal(context, selection, PortalDestination.HOME, onClosed)
    }

    suspend fun openPortal(context: Context, selection: RealmSelection, destination: PortalDestination,
        onClosed: (PortalClosure) -> Unit): PortalWebView {
        return portalConnectionMutex.withLock {
            recovery.awaitReady()
            val access = selection.access as? RealmAccess.Enterprise
                ?: throw EnterpriseConfigurationException("enterprise_session_required")
            val source = PortalPageSource(platform.createPortalGrant(access), destination)
            PortalWebView.open(context, selection, sessions, synchronization, scope, portals, source,
                { document -> PortalNativeActions(context, document, sessions, exit, media.open(document.id),
                    AndroidPortalCaptureFactory(context, scope), scope) }, onClosed)
        }
    }

    suspend fun switchRealm(request: RealmSwitchRequest): RealmSelection {
        recovery.awaitReady()
        val pending = mutex.withLock {
            switching.value?.let { current ->
                if (current.request != request) throw EnterpriseConfigurationException("enterprise_switch_in_progress")
                return@withLock current.result
            }
            scope.coroutineContext.ensureActive()
            scope.async(start = CoroutineStart.LAZY) { performSwitch(request) }.also { pending ->
                switching.value = Switching(request, pending)
                pending.start()
            }
        }
        return pending.await()
    }

    private suspend fun performSwitch(request: RealmSwitchRequest): RealmSelection {
        var receipt: PortalCloseReceipt? = null
        var selected: RealmSelection? = null
        var failure: Exception? = null
        try {
            selected = sessions.switchRealm(request) { previous ->
                speech.revoke(previous)
                terminals.revokeViewports(previous)
                if (previous is RealmAccess.Enterprise) {
                    val captured = portals.capture(previous)
                    receipt = captured
                    captured.revoke(PortalCloseReason.AUTHORIZATION_REVOKED)
                    captured.awaitHostsClosed()
                }
            }
        } catch (error: Exception) {
            failure = error
        }
        try {
            // The accepted switch owns this receipt even if its caller or application scope is cancelled.
            withContext(NonCancellable) {
                var portalFailure: Exception? = null
                try { receipt?.awaitClosed() } catch (error: Exception) { portalFailure = error }
                try { speech.closeRealm(request.selection.access) } catch (error: Exception) {
                    if (portalFailure == null) throw error else portalFailure.addSuppressed(error)
                }
                portalFailure?.let { throw it }
            }
        } catch (cleanup: Exception) {
            if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup)
        } finally {
            withContext(NonCancellable) { mutex.withLock { switching.value = null } }
        }
        failure?.let { throw it }
        return requireNotNull(selected)
    }
}
