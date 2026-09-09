package net.weero.measix.pilot.di

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import net.weero.measix.pilot.BuildConfig
import net.weero.measix.pilot.data.ai.RequestLoggingInterceptor
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.LocalEnterpriseSource
import net.weero.measix.pilot.data.enterprise.LocalEnrollmentAuthority
import net.weero.measix.pilot.data.enterprise.LocalEnterpriseConfigurationStore
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import net.weero.measix.pilot.AppScope
import java.io.File
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.ai.mcp.OAuthCallbackKeepAlive
import net.weero.measix.pilot.data.ai.mcp.NetworkMonitor
import net.weero.measix.pilot.service.McpApplicationService
import net.weero.measix.pilot.service.McpOAuthCallbackKeepAlive
import net.weero.measix.pilot.service.McpQueryService
import net.weero.measix.pilot.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import net.weero.measix.pilot.data.sync.S3Sync
import net.weero.measix.pilot.data.sync.BackupArchiveService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import me.rerere.common.http.excludingPrivateRequests
import me.rerere.common.http.PrivateRequestBoundaryInterceptor
import org.koin.dsl.module

import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        EnterpriseAppliedStore(File(get<Context>().noBackupFilesDir, "enterprise"))
    }
    single { EnterpriseSessionController(get()) }
    single {
        val context = get<Context>()
        LocalEnterpriseSource(
            openExample = { context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET) },
            openIdentity = { context.assets.open(LocalEnterpriseSource.IDENTITY_ASSET) },
            sessions = get(), enrollmentAuthority = get(), configurations = get(),
        )
    }
    single { LocalEnrollmentAuthority(File(get<Context>().noBackupFilesDir, "local_enterprise_service")) }
    single { LocalEnterpriseConfigurationStore(File(get<Context>().noBackupFilesDir, "local_enterprise_service")) }
    single { EnterpriseSynchronizationService(get(), get(), get<AppScope>()) }
    single {
        SettingsStore(appContext = get(), scope = get())
    }

    single {
        val context: Context = get()
        createAppDatabase(context, "measix_pilot")
    }

    single {
        PebbleEngine.Builder()
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().artifactDao()
    }

    single {
        get<AppDatabase>().artifactReferenceDao()
    }

    single {
        get<AppDatabase>().systemMetaDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().turnExecutionDao()
    }

    single {
        get<AppDatabase>().toolExecutionDao()
    }

    single {
        get<AppDatabase>().conversationModelContextDao()
    }

    single {
        MessageFtsManager(get())
    }

    single {
        McpCatalogStore(context = get(), scope = get(), settingsStore = get())
    }

    single { net.weero.measix.pilot.data.enterprise.LocalEnterpriseMcpService(get(), get()) }
    single {
        val context = get<Context>()
        net.weero.measix.pilot.data.enterprise.LocalEnterpriseSpeechService(get(), get()) {
            context.assets.open("enterprise/speech-example.mp3").use { it.readBytes() }
        }
    }
    single {
        val local = get<net.weero.measix.pilot.data.enterprise.LocalEnterpriseSpeechService>()
        net.weero.measix.pilot.data.enterprise.EnterpriseSpeechTransport(local::execute)
    }


    single<OAuthCallbackKeepAlive> { McpOAuthCallbackKeepAlive() }

    single {
        McpRuntimeCoordinator(
            settingsStore = get(),
            sessions = get(),
            localMcp = get(),
            synchronization = get(),
            catalogStore = get(),
            appScope = get(),
            artifactStore = get(),
            networkMonitor = get(),
            oauthCallbackKeepAlive = get(),
        )
    }

    single { NetworkMonitor(get()) }

    single { McpApplicationService(coordinator = get(), settingsStore = get()) }

    single { McpQueryService(settingsStore = get(), coordinator = get(), configurationQueries = get(), sessions = get(), scope = get()) }

    single {
        TurnRunner(
            context = get(),
            providerManager = get(),
            json = get(),
            attachmentResolver = get(),
            toolOutputStore = get(),
            artifactStore = get(),
        )
    }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(PrivateRequestBoundaryInterceptor())
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "MeasixPilot-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }.excludingPrivateRequests())
            .build().also { SearchService.init(it, get()) }
    }

    single {
        ProviderManager(client = get(), context = get())
    }

    single {
        BackupArchiveService(
            context = get(),
            settingsStore = get(),
            mcpCatalogStore = get(),
            json = get(),
            database = get(),
            artifactStore = get(),
            generatedMediaStore = get(),
        )
    }

    single {
        WebDavSync(
            context = get(),
            httpClient = get(),
            archiveService = get(),
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            context = get(),
            httpClient = get(),
            archiveService = get(),
        )
    }

}
