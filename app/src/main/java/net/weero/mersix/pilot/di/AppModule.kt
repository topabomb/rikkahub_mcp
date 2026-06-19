package net.weero.mersix.pilot.di

import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import net.weero.mersix.pilot.AppScope
import net.weero.mersix.pilot.data.ai.tools.LocalTools
import net.weero.mersix.pilot.data.event.AppEventBus
import net.weero.mersix.pilot.service.ChatService
import net.weero.mersix.pilot.utils.EmojiData
import net.weero.mersix.pilot.utils.EmojiUtils
import net.weero.mersix.pilot.utils.JsonInstant
import net.weero.mersix.pilot.utils.SoundEffectPlayer
import net.weero.mersix.pilot.utils.UpdateChecker
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get())
    }

    single {
        UpdateChecker(get())
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

    single {
        ChatService(
            context = get(),
            appScope = get(),
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
            workspaceRepository = get()
        )
    }
}
