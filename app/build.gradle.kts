import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileInputStream
import java.util.Properties
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest

abstract class RenameApkTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:InputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun rename() {
        outputDir.get().asFile.listFiles()?.filter { it.name.startsWith("app-") && it.name.endsWith(".apk") }?.forEach { file ->
            val newName = file.name.replace(Regex("^app-"), "MeasixPilot_${versionName.get()}_")
            file.renameTo(File(file.parentFile, newName))
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

// APK semantic identity comes only from the checked-in Core export. Android owns its support promise.
val supportedPlatformContracts = listOf(2)
val contractRoot = file("src/test/resources/contracts")
fun pinnedJson(path: String): Map<*, *> {
    val source = contractRoot.resolve(path)
    require(source.isFile) { "Missing pinned Core material: $path" }
    return JsonSlurper().parse(source, "UTF-8") as? Map<*, *>
        ?: error("Pinned Core material must be a JSON object: $path")
}
fun contractHash(path: String, lf: Boolean = false): String {
    val bytes = contractRoot.resolve(path).readBytes().let {
        if (lf) it.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8) else it
    }
    return "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
fun requireContractIdentity(value: Map<*, *>) {
    val primary = value["platformContractVersion"]
    val supported = value["supportedPlatformContractVersions"] as? List<*>
        ?: error("Invalid Core supportedPlatformContractVersions")
    require(primary is Int && primary > 0) { "Invalid Core platformContractVersion" }
    require(supported.isNotEmpty() && supported.all { it is Int && it > 0 } &&
        supported == supported.filterIsInstance<Int>().distinct().sorted() && primary in supported) {
        "Invalid Core supportedPlatformContractVersions"
    }
    val baselineVersion = value["coreBaselineVersion"]
    require(baselineVersion is String && Regex("[0-9A-Za-z][0-9A-Za-z._-]{0,63}").matches(baselineVersion)) {
        "Invalid Core coreBaselineVersion"
    }
}
val pinnedBaseline = pinnedJson("platform/protocol-baseline.json").also(::requireContractIdentity)
val pinnedBaselineHash = contractHash("platform/protocol-baseline.json", lf = true)
val pinnedClientManifest = pinnedJson("platform/manifest.json").also(::requireContractIdentity)
val pinnedPortalManifest = pinnedJson("portal/manifest.json").also(::requireContractIdentity)
for ((label, manifest) in listOf("Client" to pinnedClientManifest, "Portal" to pinnedPortalManifest)) {
    for (field in listOf("platformContractVersion", "supportedPlatformContractVersions", "coreBaselineVersion")) {
        require(manifest[field] == pinnedBaseline[field]) { "$label $field differs from pinned baseline" }
    }
    require(manifest["baselineHash"] == pinnedBaselineHash) { "$label baselineHash differs from pinned baseline" }
}
require(pinnedClientManifest["generated"] == true &&
    pinnedClientManifest["source"] == "../api/client/client-control.openapi.yaml" &&
    pinnedClientManifest["format"] == "openapi-3.0.3-client-schema-only") { "Invalid Client export source/format" }
val pinnedClientHash = contractHash("platform/client-control.openapi.yaml", lf = true)
require(pinnedClientManifest["sourceHash"] == pinnedClientHash &&
    (pinnedBaseline["documents"] as? Map<*, *>)?.get("api/client/client-control.openapi.yaml") == pinnedClientHash) {
    "Client sourceHash differs from schema/baseline"
}
require(pinnedPortalManifest["bridgeVersion"] == 3) { "Invalid Portal bridgeVersion" }
val portalSources = mapOf(
    "portal-contract.openapi.json" to "api/portal/portal-contract.openapi.json",
    "client-feed.schemas.json" to "api/portal/client-feed.schemas.json",
    "native-vectors.json" to "api/fixtures/portal/native-vectors.json",
    "feed-vectors.json" to "api/fixtures/portal/feed-vectors.json",
    "platform-v1.json" to "api/fixtures/enrollment/platform-v1.json",
    "cases.json" to "api/fixtures/enrollment/cases.json",
)
val portalArtifacts = pinnedPortalManifest["artifacts"] as? Map<*, *> ?: error("Missing Portal artifacts")
require(portalArtifacts.keys == portalSources.keys) { "Incomplete Portal artifacts" }
for ((name, source) in portalSources) {
    val artifact = portalArtifacts[name] as? Map<*, *> ?: error("Invalid Portal artifact: $name")
    require(artifact["source"] == source && "sha256:${artifact["sha256"]}" == contractHash("portal/$name")) {
        "Portal artifact source/hash mismatch: $name"
    }
}
require(supportedPlatformContracts.isNotEmpty() && supportedPlatformContracts.all { it > 0 } &&
    supportedPlatformContracts == supportedPlatformContracts.distinct().sorted() &&
    pinnedBaseline["platformContractVersion"] in supportedPlatformContracts) { "Invalid Android contract support promise" }

android {
    namespace = "net.weero.measix.pilot"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.weero.measix.pilot"
        minSdk = 26
        targetSdk = 37
        versionCode = 20
        versionName = "0.0.20"

        buildConfigField("int", "PLATFORM_CONTRACT_VERSION", pinnedBaseline["platformContractVersion"].toString())
        buildConfigField("String", "CORE_BASELINE_VERSION", JsonOutput.toJson(pinnedBaseline["coreBaselineVersion"]))
        buildConfigField("String", "PLATFORM_CONTRACT_BASELINE_HASH", JsonOutput.toJson(pinnedBaselineHash))
        buildConfigField("int[]", "SUPPORTED_PLATFORM_CONTRACT_VERSIONS",
            supportedPlatformContracts.joinToString(prefix = "new int[]{", postfix = "}"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        // Room MigrationTestHelper（androidTest 插桩迁移测试）需要 schema JSON 作为插桩测试 assets
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    // Direct instrumentation can target a fixed signed Release APK as well as a Debug candidate.
    testBuildType = providers.gradleProperty("androidConsumerVariant").orElse("debug").get().also {
        require(it in setOf("debug", "release")) { "androidConsumerVariant must be debug or release" }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }

        val storeFilePath = localProperties.getProperty("storeFile")
        val storePasswordValue = localProperties.getProperty("storePassword")
        val keyAliasValue = localProperties.getProperty("keyAlias")
        val keyPasswordValue = localProperties.getProperty("keyPassword")

        if (
            !storeFilePath.isNullOrBlank() &&
            !storePasswordValue.isNullOrBlank() &&
            !keyAliasValue.isNullOrBlank() &&
            !keyPasswordValue.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
            testProguardFiles("src/androidTest/keepRules/release-test.keep")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = true
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // JUnit 5 (pulled by androidTest deps) ships duplicate META-INF license files.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
            )
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// Rename APK output: app-arm64-v8a-release.apk → MeasixPilot_<version>_arm64-v8a-release.apk
tasks.register<RenameApkTask>("renameReleaseApk") {
    versionName.set(android.defaultConfig.versionName ?: "unknown")
    outputDir.set(layout.buildDirectory.dir("outputs/apk/release"))
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy("renameReleaseApk") }

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

androidComponents.finalizeDsl {
    // Baseline Profile copies release source sets during DSL finalization; configure the workload after that copy.
    it.sourceSets.getByName("benchmarkRelease").apply {
        assets.srcDirs("$projectDir/schemas")
        manifest.srcFile("$projectDir/src/benchmarkRelease/AndroidManifest.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Unit tests execute the desktop binding; packaged Android variants retain the Android native library.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.quickjs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.snakeyaml)
    implementation(libs.re2j)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.json.canonicalization)
    implementation(libs.modelcontextprotocol.kotlin.sdk)
    implementation(libs.kotlin.logging)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation("io.mockk:mockk-agent-jvm:1.14.5")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    // SLF4J simple for JVM tests: MockK uses SLF4J, but slf4j-android provider
    // cannot initialize in JVM test context. slf4j-simple provides a lightweight fallback.
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
