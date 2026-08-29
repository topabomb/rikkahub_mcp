package net.weero.measix.pilot.di

import android.content.Context
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.ArtifactUseCase
import net.weero.measix.pilot.service.FileManagementApplicationService
import net.weero.measix.pilot.service.FileManagementQueryService
import net.weero.measix.pilot.service.ConversationTitleCoordinator
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.service.ApplicationRecoveryCoordinator
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ChatNotificationManager
import net.weero.measix.pilot.service.BackupRestoreApplicationService
import net.weero.measix.pilot.service.MasterTurnCoordinator
import net.weero.measix.pilot.service.ProviderSettingsApplicationService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationAttachmentPreviewProjector
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.CustomChatFontService
import net.weero.measix.pilot.service.SearchIndexMaintenanceService
import net.weero.measix.pilot.service.ChatErrorStore
import net.weero.measix.pilot.service.GenerationSideEffects
import net.weero.measix.pilot.service.SubAssistantRunGate
import net.weero.measix.pilot.service.SubAssistantDetailReader
import net.weero.measix.pilot.service.SubAssistantLifecycle
import net.weero.measix.pilot.service.StatsQueryService
import net.weero.measix.pilot.service.TurnFinalization
import net.weero.measix.pilot.service.TurnRecovery
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.FavoriteModelService
import net.weero.measix.pilot.service.FavoriteService
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import net.weero.measix.pilot.utils.EmojiData
import net.weero.measix.pilot.utils.EmojiUtils
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.SoundEffectPlayer
import net.weero.measix.pilot.utils.UpdateChecker
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single { ApplicationRecoveryGate() }
    single { ArtifactUseCase(get(), get()) }
    single { FileManagementApplicationService(get(), get(), get()) }
    single { FileManagementQueryService(get(), get(), get()) }
    single { MediaExportService(get()) }
    single { StatsQueryService(get(), get(), get()) }
    single { ChatErrorStore() }
    single { BackupRestoreApplicationService(get(), get(), get()) }
    single { ProviderSettingsApplicationService(get(), get()) }
    single { WorkspaceTerminalRuntime(get(), get()) }
    single { WorkspaceApplicationService(get(), get()) }
    single { WorkspaceQueryService(get(), get()) }

    single {
        AppEventBus()
    }

    single {
        ImageGenerationSelectionResolver(get())
    }

    single {
        val context: Context = get()
        GeneratedMediaStore(
            filesDir = context.filesDir,
            genMediaRepository = get(),
            artifactStore = get(),
        )
    }

    single(createdAtStart = true) {
        ImageGenerationCoordinator(
            scope = get<AppScope>(),
            mediaStore = get(),
        )
    }

    single {
        AssistantBackgroundService(
            artifactStore = get(),
            context = get(),
            remoteMediaFetcher = get(),
        )
    }

    single {
        val context: Context = get()
        ToolArtifactRewriter(
            filesDir = context.filesDir,
            artifactStore = get(),
        )
    }

    single {
        val context: Context = get()
        ImageGenerationToolFactory(
            filesDir = context.filesDir,
            settingsStore = get(),
            resolver = get(),
            coordinator = get(),
            backgroundService = get(),
            artifactStore = get(),
            rewriter = get(),
        )
    }

    single {
        LocalTools(get(), get(), get(), get(), get())
    }

    single {
        UpdateChecker(get(), get())
    }

    single {
        FavoriteModelService(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：MasterTurnCoordinator 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        AssistantManagementService(
            settingsStore = get(),
            memoryRepository = get(),
            artifactStore = get(),
            runtimeRegistry = get(),
            delegationCoordinator = get(),
            recoveryGate = get(),
            conversationApplicationService = get(),
        )
    }

    single {
        net.weero.measix.pilot.service.runtime.ConversationOperationLocks()
    }

    single {
        ConversationRuntimeRegistry(
            appScope = get(),
            repository = get(),
            operationLocks = get(),
        )
    }

    single {
        ConversationCommandCoordinator(
            registry = get(),
            repository = get(),
            recoveryGate = get(),
            operationLocks = get(),
        )
    }

    single {
        GenerationToolSetFactory(
            localTools = get(),
            conversationQueryService = get(),
            skillManager = get(),
            workspaceApplicationService = get(),
            workspaceQueryService = get(),
            mcpManager = get(),
            providerManager = get(),
            artifactStore = get(),
        )
    }

    single {
        SafeRemoteMediaFetcher()
    }

    single {
        AttachmentResolver(
            context = get(),
            artifactStore = get(),
            fetcher = get(),
            artifactRewriter = get(),
        )
    }

    single { SubAssistantRunGate() }

    single {
        TurnRecovery(
            conversationRepo = get(),
            commandCoordinator = get(),
            settingsStore = get(),
            json = get(),
            runGate = get(),
        )
    }

    single {
        DelegationCoordinator(
            generationLoop = get(),
            conversationRepo = get(),
            runtimeRegistry = get(),
            commandCoordinator = get(),
            toolSetFactory = get(),
            settingsStore = get(),
            memoryRepository = get(),
            templateTransformer = get(),
            workspaceRepository = get(),
            artifactStore = get(),
            toolArtifactRewriter = get(),
            json = get(),
            attachmentResolver = get(),
            context = get(),
            turnFinalization = get(),
            runGate = get(),
        )
    }

    single {
        AssistantToolFactory(
            settingsStore = get(),
            assistantManagementService = get(),
            json = get(),
            delegationCoordinator = get(),
            toolSetFactory = get(),
        )
    }

    // 唯一启动恢复入口；任一步失败都不会打开 durable write gate。
    single(createdAtStart = true) {
        ApplicationRecoveryCoordinator(
            appScope = get<AppScope>(),
            settingsStore = get(),
            artifactStore = get(),
            generatedMediaStore = get(),
            conversationRepository = get(),
            assistantManagementService = get(),
            turnRecovery = get(),
            gate = get(),
            restorePendingBackup = {
                net.weero.measix.pilot.data.sync.PendingBackupRestore.restoreSettingsIfPending(
                    get(),
                    get(),
                    get(),
                    get(),
                )
            },
            completePendingBackup = {
                net.weero.measix.pilot.data.sync.PendingBackupRestore.complete(get())
            },
            postRecoveryMaintenance = {
                get<net.weero.measix.pilot.service.CustomChatFontService>().reconcile()
                get<net.weero.measix.pilot.data.repository.WorkspaceRepository>().checkIntegrity()
                get<net.weero.measix.pilot.data.files.ArtifactStore>().collectGarbage()
                get<net.weero.measix.pilot.data.datastore.SettingsStore>().updateLocal { current ->
                    current.copy(launchCount = current.launchCount + 1)
                }
            },
        )
    }

    single {
        TurnFinalization(
            conversationRepository = get(),
            runtimeRegistry = get(),
            commandCoordinator = get(),
            json = get(),
        )
    }

    single {
        SubAssistantLifecycle(
            conversationRepository = get(),
            runtimeRegistry = get(),
            commandCoordinator = get(),
            settingsStore = get(),
            turnFinalization = get(),
            json = get(),
        )
    }

    single { ConversationTitleCoordinator() }

    single {
        GenerationSideEffects(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            providerManager = get(),
            artifactStore = get(),
            runtimeRegistry = get(),
            commandCoordinator = get(),
            soundEffectPlayer = get(),
            json = get(),
            chatErrorStore = get(),
            titleCoordinator = get(),
        )
    }

    single {
        MasterTurnCoordinator(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            memoryRepository = get(),
            generationLoop = get(),
            templateTransformer = get(),
            mcpManager = get(),
            toolSetFactory = get(),
            workspaceRepository = get(),
            assistantToolFactory = get(),
            delegationCoordinator = get(),
            turnFinalization = get(),
            subAssistantLifecycle = get(),
            runtimeRegistry = get(),
            commandCoordinator = get(),
            recoveryGate = get(),
            chatErrorStore = get(),
            sideEffects = get(),
            artifactStore = get(),
            artifactUseCase = get(),
            toolArtifactRewriter = get(),
            titleCoordinator = get(),
        )
    }

    single {
        ConversationApplicationService(
            settingsStore = get(),
            conversationRepo = get(),
            folderRepository = get(),
            runtimeRegistry = get(),
            commandCoordinator = get(),
            recoveryGate = get(),
            subAssistantLifecycle = get(),
            sideEffects = get(),
            artifactStore = get(),
            artifactUseCase = get(),
            turnFinalization = get(),
            json = get(),
            toolArtifactRewriter = get(),
            titleCoordinator = get(),
        )
    }

    single { ConversationAttachmentPreviewProjector(get()) }
    single { ConversationQueryService(get(), get(), get(), get(), get()) }
    single { CustomChatFontService(get(), get()) }
    single { SearchIndexMaintenanceService(get(), get()) }
    single { FavoriteService(get(), get()) }
    single { SubAssistantDetailReader(get()) }
}
