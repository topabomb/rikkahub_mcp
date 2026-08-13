package net.weero.measix.pilot.di

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.AssistantDataRecovery
import net.weero.measix.pilot.service.ChatNotificationManager
import net.weero.measix.pilot.service.ChatService
import net.weero.measix.pilot.service.ConversationSessionRegistry
import net.weero.measix.pilot.service.SubAssistantCoordinator
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
        LocalTools(get(), get(), get(), get())
    }

    single {
        UpdateChecker(get(), get())
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
            subAssistantCoordinator = get(),
        )
    }

    single {
        ConversationSessionRegistry(
            appScope = get(),
            settingsStore = get(),
        )
    }

    single {
        GenerationToolSetFactory(
            localTools = get(),
            conversationRepo = get(),
            skillManager = get(),
            workspaceRepository = get(),
            mcpManager = get(),
        )
    }

    single {
        SubAssistantCoordinator(
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
        )
    }

    single {
        AssistantToolFactory(
            settingsStore = get(),
            assistantManagementService = get(),
            json = get(),
            subAssistantCoordinator = get(),
        )
    }

    // 启动即消费删除 tombstone，并修复进程中断的子助手运行。
    single(createdAtStart = true) {
        AssistantDataRecovery(
            appScope = get(),
            settingsStore = get(),
            assistantManagementService = get(),
            subAssistantCoordinator = get(),
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
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            soundEffectPlayer = get(),
            assistantToolFactory = get(),
            subAssistantCoordinator = get(),
            sessionRegistry = get(),
            json = get(),
        )
    }
}
