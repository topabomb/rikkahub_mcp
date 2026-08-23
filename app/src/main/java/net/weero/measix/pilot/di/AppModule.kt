package net.weero.measix.pilot.di

import android.content.Context
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.AssistantDataRecovery
import net.weero.measix.pilot.service.AssistantDataRecoveryGate
import net.weero.measix.pilot.service.ChatNotificationManager
import net.weero.measix.pilot.service.ChatService
import net.weero.measix.pilot.service.SubAssistantRunGate
import net.weero.measix.pilot.service.TurnRecovery
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.FavoriteModelService
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

    single {
        AppEventBus()
    }

    single {
        ImageGenerationSelectionResolver(get())
    }

    single {
        ManagedLocalArtifactStore(get(), get())
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
        ).apply { startBackgroundMaintenance() }
    }

    single {
        AssistantBackgroundService(
            context = get(),
            settingsStore = get(),
            artifactStore = get(),
            conversationRepository = get(),
            genMediaRepository = get(),
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

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
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
            conversationRepo = get(),
            filesManager = get(),
            sessionRegistry = get(),
            delegationCoordinator = get(),
            recoveryGate = get(),
        )
    }

    single {
        ConversationRuntimeRegistry(
            appScope = get(),
            settingsStore = get(),
            repository = get(),
        )
    }

    single {
        GenerationToolSetFactory(
            localTools = get(),
            conversationRepo = get(),
            skillManager = get(),
            workspaceRepository = get(),
            mcpManager = get(),
            providerManager = get(),
        )
    }

    single {
        SafeRemoteMediaFetcher()
    }

    single {
        AttachmentResolver(
            context = get(),
            filesManager = get(),
            artifactStore = get(),
            fetcher = get(),
            artifactRewriter = get(),
        )
    }

    single { SubAssistantRunGate() }

    single {
        TurnRecovery(
            conversationRepo = get(),
            sessionRegistry = get(),
            settingsStore = get(),
            json = get(),
            runGate = get(),
        )
    }

    single {
        DelegationCoordinator(
            generationHandler = get(),
            conversationRepo = get(),
            sessionRegistry = get(),
            toolSetFactory = get(),
            settingsStore = get(),
            memoryRepository = get(),
            templateTransformer = get(),
            workspaceRepository = get(),
            filesManager = get(),
            json = get(),
            attachmentResolver = get(),
            context = get(),
            turnRecovery = get(),
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

    single { AssistantDataRecoveryGate() }

    // 启动即消费删除 tombstone，并修复进程中断的子助手运行。
    single(createdAtStart = true) {
        AssistantDataRecovery(
            appScope = get(),
            settingsStore = get(),
            assistantManagementService = get(),
            turnRecovery = get(),
            recoveryGate = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            mcpManager = get(),
            filesManager = get(),
            toolSetFactory = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            soundEffectPlayer = get(),
            assistantToolFactory = get(),
            delegationCoordinator = get(),
            sessionRegistry = get(),
            recoveryGate = get(),
            json = get(),
            toolArtifactRewriter = get(),
            turnRecovery = get(),
        )
    }
}
