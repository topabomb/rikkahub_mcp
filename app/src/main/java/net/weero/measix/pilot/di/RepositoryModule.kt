package net.weero.measix.pilot.di

import android.content.Context
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.DatabaseTransactionRunner
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FavoriteRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single<DatabaseTransactionRunner> { RoomDatabaseTransactionRunner(get()) }

    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single { ArtifactPayloadStore(get()) }

    single { ArtifactSettingsCoordinator(get()) }

    single {
        ArtifactStore(
            payloadStore = get(),
            artifactDAO = get<ArtifactDAO>(),
            artifactReferenceDAO = get(),
            systemMetaDAO = get(),
            conversationDAO = get(),
            messageNodeDAO = get(),
            settingsCoordinator = get(),
            transactionRunner = get(),
        )
    }

    single {
        SkillManager(get(), get())
    }
}
