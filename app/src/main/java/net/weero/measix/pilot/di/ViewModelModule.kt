package net.weero.measix.pilot.di

import net.weero.measix.pilot.ui.pages.assistant.AssistantVM
import net.weero.measix.pilot.ui.pages.assistant.detail.AssistantDetailVM
import net.weero.measix.pilot.ui.pages.backup.BackupVM
import net.weero.measix.pilot.ui.pages.chat.ChatDrawerVM
import net.weero.measix.pilot.ui.pages.chat.ChatVM
import net.weero.measix.pilot.ui.pages.debug.DebugVM
import net.weero.measix.pilot.ui.pages.favorite.FavoriteVM
import net.weero.measix.pilot.ui.pages.search.SearchVM
import net.weero.measix.pilot.ui.pages.history.HistoryVM
import net.weero.measix.pilot.ui.pages.stats.StatsVM
import net.weero.measix.pilot.ui.pages.imggen.ImgGenVM
import net.weero.measix.pilot.ui.pages.extensions.PromptVM
import net.weero.measix.pilot.ui.pages.extensions.QuickMessagesVM
import net.weero.measix.pilot.ui.pages.extensions.skills.SkillDetailVM
import net.weero.measix.pilot.ui.pages.extensions.skills.SkillsVM
import net.weero.measix.pilot.ui.pages.extensions.workspace.WorkspaceDetailVM
import net.weero.measix.pilot.ui.pages.extensions.workspace.WorkspaceVM
import net.weero.measix.pilot.ui.pages.setting.SettingVM
import net.weero.measix.pilot.ui.pages.share.handler.ShareHandlerVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            updateChecker = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
        )
    }
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
