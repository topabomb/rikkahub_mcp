# 版本检查与自动更新机制

> 本文档以 Measix Pilot 当前代码为准，详细记录版本检查、SemVer 比较、更新下载、
> UI 交互、CI 构建与发行版托管的完整链路。
> 上游 RikkaHub 的客户端实现结构完全一致，差异仅在 API 端点、User-Agent、包名和 CI 策略。
> 代码变更时应同步更新本文档。

## 目录

1. [模块概览](#模块概览)
2. [核心组件与职责](#核心组件与职责)
3. [网络交互](#网络交互)
4. [SemVer 版本比较](#semver-版本比较)
5. [UI 交互流程](#ui-交互流程)
6. [Play Store 安装来源检测](#play-store-安装来源检测)
7. [CI 构建与发行版托管](#ci-构建与发行版托管)
8. [与上游 RikkaHub 的差异](#与上游-rikkahub-的差异)
9. [架构注意事项](#架构注意事项)

---

## 模块概览

应用内置轻量的版本检查与更新机制，在用户打开聊天抽屉时自动向后端 API 发起一次请求，
获取最新版本信息（版本号、发布时间、changelog、下载列表），通过 SemVer 比较判断是否有新版本。
如果有，在抽屉顶部展示可关闭的更新卡片；用户点击卡片后弹出 BottomSheet 展示完整 changelog 和
下载选项，选择后委托系统 DownloadManager 在后台下载 APK。

整体设计原则：

- **非阻断**：更新检查失败或无新版本时，用户完全无感知，不影响正常使用
- **可关闭**：更新卡片有关闭按钮，用户可以 dismiss 当前版本的提醒
- **可禁用**：设置页有"显示更新"开关，Play Store 安装的版本自动隐藏
- **委托系统下载**：不自行管理下载进度，使用 Android DownloadManager 统一处理

### 文件结构

```
app/                                    # 应用模块
├── utils/
│   ├── UpdateChecker.kt              # 核心逻辑：API 请求、下载委托、Version 值类
│   ├── PlayStoreUtil.kt              # 安装来源检测
│   └── ContextUtil.kt                # openUrl 扩展（下载失败时的浏览器兜底）
├── ui/
│   ├── components/ui/
│   │   └── UpdateCard.kt             # 更新卡片 + BottomSheet 详情 UI
│   └── hooks/
│       └── PlayStore.kt              # rememberIsPlayStoreVersion() Composable
├── di/
│   └── AppModule.kt                  # UpdateChecker 单例注册
├── data/datastore/
│   └── PreferencesStore.kt           # showUpdates 布尔设置项
└── build.gradle.kts                  # ABI splits、签名配置、版本号

.github/workflows/
└── release.yml                       # 手动触发的 Release 构建 CI
```

---

## 核心组件与职责

| 组件 | 所在文件 | 职责 |
|------|----------|------|
| `UpdateChecker` | `utils/UpdateChecker.kt` | 发起版本检查 HTTP 请求；解析 JSON 响应；委托 DownloadManager 下载 APK |
| `Version` | `utils/UpdateChecker.kt` | SemVer 值类，封装版本号字符串并提供 `Comparable` 比较 |
| `UpdateInfo` / `UpdateDownload` | `utils/UpdateChecker.kt` | 序列化数据模型，描述远程版本信息 |
| `UiState<T>` | `utils/UiState.kt` | 通用 UI 状态密封类（Idle / Loading / Success / Error） |
| `UpdateCard` | `ui/components/ui/UpdateCard.kt` | Compose UI：更新通知卡片 + 详情 BottomSheet |
| `PlayStoreUtil` | `utils/PlayStoreUtil.kt` | 检测 APK 是否由 Play Store 安装 |
| `rememberIsPlayStoreVersion` | `ui/hooks/PlayStore.kt` | Compose 侧的 Play Store 安装检测 Hook |

### 依赖注入

```kotlin
// AppModule.kt
single {
    UpdateChecker(get())  // 注入全局 OkHttpClient 单例
}
```

`UpdateChecker` 作为 Koin 单例注册，在 `ChatVM` 构造时注入。

---

## 网络交互

### API 端点

```kotlin
// Measix Pilot（当前 Fork）
private const val API_URL = "https://measix.weero.net/mobile/"

// 上游 RikkaHub
private const val API_URL = "https://updates.rikka-ai.com/"
```

### 请求

- **方法**：HTTP GET
- **User-Agent**：`MeasixPilot ${VERSION_NAME} #${VERSION_CODE}`
  - 示例：`MeasixPilot 0.0.11 #11`
  - 上游：`RikkaHub 2.4.3 #171`
- **客户端**：复用全局 `OkHttpClient` 单例（与 AI Provider、搜索等功能共享连接池）
- **线程**：`flowOn(Dispatchers.IO)`

### 响应模型

```kotlin
@Serializable
data class UpdateInfo(
    val version: String,         // 最新版本号，如 "0.0.11"
    val publishedAt: String,     // ISO 8601 发布时间
    val changelog: String,       // Markdown 格式的变更日志
    val downloads: List<UpdateDownload>  // 下载选项列表
)

@Serializable
data class UpdateDownload(
    val name: String,   // 文件名，如 "MeasixPilot_0.0.11_arm64-v8a.apk"
    val url: String,    // 下载地址
    val size: String    // 文件大小描述，如 "45.2 MB"
)
```

### 状态流

```kotlin
fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
    emit(UiState.Loading)
    emit(
        UiState.Success(
            data = try {
                // 执行 HTTP 请求并解析 JSON
                ...
            } catch (e: Exception) {
                throw Exception("Failed to fetch update info", e)
            }
        )
    )
}.catch {
    emit(UiState.Error(it))
}.flowOn(Dispatchers.IO)
```

`UiState` 是项目通用的 UI 状态密封类：

```kotlin
sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val error: Throwable) : UiState<Nothing>()
}
```

### 在 ChatVM 中的消费

`UpdateChecker` 是 Koin 单例，`updateState` 绑定在 AppScope 上，所有 ChatVM 共享同一 StateFlow：

```kotlin
// UpdateChecker.kt
val updateState: StateFlow<UiState<UpdateInfo>> by lazy {
    checkUpdate().stateInOnce(appScope, UiState.Loading)
}

// stateInOnce 使用 Lazily 策略
internal fun <T> Flow<T>.stateInOnce(scope: CoroutineScope, initialValue: T): StateFlow<T> =
    stateIn(scope, SharingStarted.Lazily, initialValue)

// ChatVM.kt — 直接访问单例的 updateState
val updateState = updateChecker.updateState
```

- **Lazily**：首次有订阅者（即 `UpdateCard` 组合且用户开启 `showUpdates`）时才发起请求
- **初始值**：`UiState.Loading`
- **生命周期**：绑定 `appScope`（进程级），不随 ViewModel 销毁而取消
- **共享**：同一 App 进程内最多请求一次；切换会话或 ViewModel 重建不会重复请求
- **错误关闭**：`errorDismissed` 是 `UpdateChecker` 单例上的进程级状态，跨会话不重复打扰，但下次启动会重试

### APK 下载

```kotlin
fun downloadUpdate(context: Context, download: UpdateDownload) {
    runCatching {
        val request = DownloadManager.Request(download.url.toUri()).apply {
            setTitle(download.name)
            setDescription("正在下载更新包...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }.onFailure {
        Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
        context.openUrl(download.url)  // 兜底：浏览器打开下载链接
    }
}
```

委托给系统 `DownloadManager`：

- 下载到公共 `Downloads` 目录
- 通知栏显示进度，完成后保留通知
- 支持 WiFi 和移动网络
- MIME 类型设为 APK，完成后可直接点击安装
- 下载失败时通过 `context.openUrl()` 用 Chrome Custom Tabs 打开下载链接兜底

---

## SemVer 版本比较

`Version` 是一个 `@JvmInline value class`，实现 `Comparable<Version>`，支持完整的
[SemVer 2.0.0](https://semver.org/) 规范。

### 解析

```text
输入: "1.2.3-alpha.1+build.42"

1. 去掉 build metadata: "1.2.3-alpha.1"
2. 分离 core 和 prerelease:
   - core = [1, 2, 3]
   - prerelease = ["alpha", "1"]
```

### 比较规则

1. **主版本号**：逐段比较数值，缺位补 0
   - `1.2` < `1.2.1`（等价于 `1.2.0` < `1.2.1`）
2. **预发布标识符**：有 prerelease 的版本 < 无 prerelease 的版本
   - `1.0.0-alpha` < `1.0.0`
3. **预发布段逐个比较**：
   - 数字段按数值比较：`alpha.1` < `alpha.2`
   - 字符串段按字典序比较：`alpha` < `beta` < `rc`
   - 数字段优先级低于字符串段：`1` < `alpha`
   - 字段少的优先级低：`alpha` < `alpha.1`

### 在 UI 中的使用

```kotlin
// UpdateCard.kt
val current = remember { Version(BuildConfig.VERSION_NAME) }   // 当前安装版本
val latest = remember(info) { Version(info.version) }           // 远程最新版本
if (latest > current && !dismissed) {
    // 显示更新卡片
}
```

---

## UI 交互流程

### 展示条件

```kotlin
// ChatDrawer.kt
val isPlayStore = rememberIsPlayStoreVersion()
if (settings.displaySetting.showUpdates && !isPlayStore) {
    UpdateCard(vm)
}
```

需要同时满足两个条件：

1. `showUpdates == true`（设置 > 通知 > 显示更新，默认开启）
2. 非 Play Store 安装（Play Store 版本由商店自动更新，无需应用内检查）

### 三种 UI 状态

#### 1. 检查失败（`UiState.Error`）

展示一个简单的错误卡片，显示错误标题和消息。

#### 2. 有新版本（`UiState.Success` + `latest > current`）

展示可关闭的更新通知卡片，预览 changelog（最多 200dp 高）。

- 点击卡片 → 打开详情 BottomSheet
- 点击关闭按钮 → 关闭卡片（`dismissed = true`，本次会话不再显示）

#### 3. 详情 BottomSheet

- 展示版本号、发布时间和完整 changelog（Markdown 渲染，300dp 可滚动区域）
- 下载列表使用 `OutlinedCard` + `ListItem`，每个条目显示文件名和大小
- 点击下载项 → 调用 `downloadUpdate()`，关闭 BottomSheet，显示 toast 提示

### 防抖

下载按钮使用 `useThrottle(500)` 防抖，500ms 内只响应一次点击，避免重复触发下载。

---

## Play Store 安装来源检测

```kotlin
// PlayStoreUtil.kt
object PlayStoreUtil {
    fun isInstalledFromPlayStore(context: Context): Boolean {
        return try {
            getInstallerPackageName(context) == "com.android.vending"
        } catch (e: Exception) {
            false
        }
    }

    fun getInstallerPackageName(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+：使用 getInstallSourceInfo
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                // API < 30：使用已废弃的 getInstallerPackageName
                @Suppress("DEPRECATION")
                context.packageManager
                    .getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }
    }
}
```

```kotlin
// PlayStore.kt — Compose Hook
@Composable
fun rememberIsPlayStoreVersion(): Boolean {
    val context = LocalContext.current
    return remember { PlayStoreUtil.isInstalledFromPlayStore(context) }
}
```

检测安装来源为 `com.android.vending`（Play Store 的包名）。Play Store 安装的版本由商店
自动更新，应用内更新检查被跳过，避免重复提醒。

---

## CI 构建与发行版托管

### 构建配置（`app/build.gradle.kts`）

#### ABI Splits

```kotlin
splits {
    abi {
        val isBuildingBundle = gradle.startParameter.taskNames
            .any { it.lowercase().contains("bundle") }
        isEnable = !isBuildingBundle
        reset()
        include("arm64-v8a", "x86_64")
        isUniversalApk = true
    }
}
```

构建 APK 时按 ABI 拆分为 `arm64-v8a`、`x86_64` 和 universal 三个变体；
构建 AAB（bundle）时禁用拆分，由 Play Store 处理 ABI 分发。

#### 签名配置

```kotlin
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

    if (!storeFilePath.isNullOrBlank() && !storePasswordValue.isNullOrBlank() &&
        !keyAliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()) {
        create("release") {
            storeFile = file(storeFilePath)
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }
}
```

签名信息从 `local.properties` 读取，四项完整时才创建 `release` 签名配置。
普通开发环境（无签名信息）可生成 unsigned release，CI 环境通过 Secrets 注入。

#### Release 构建特征

- `optimization { enable = true }`：使用 AGP 9 统一启用 R8 代码优化、混淆与资源裁剪
- `app/src/main/keepRules/rikkahub.keep`：只保留反射、按名称加载和 JNI 所需的运行时规则；mapping 用于还原混淆堆栈
- `gradle.properties` 中 `android.r8.strictFullModeForKeepRules=false`：回退到 AGP 8 的
  keep rules 处理行为，兼容部分依赖库的 consumer ProGuard 规则

### 当前项目 CI（`.github/workflows/release.yml`）

```yaml
name: Release Build
on:
  push:
    tags:
      - 'v*.*.*'            # 推送版本 tag 触发正式发布
  workflow_dispatch:           # 手动触发（可选发布 Release 或仅构建 Artifact）
    inputs:
      publish_release:
        description: '发布到 GitHub Releases（否则仅上传 Artifact）'
```

流程：

1. **Checkout**：`fetch-depth: 0` 获取完整历史 + `submodules: recursive`（含 `material-color-utilities` 子模块）
2. **JDK**：Temurin 17
3. **Gradle Setup**：`gradle/actions/setup-gradle@v4`（自动缓存 + config-cache 兼容）
4. **准备签名文件**：
   - `secrets.KEY_BASE64` → base64 解码 → `app/app.key`
   - `secrets.SIGNING_CONFIG` → 写入 `local.properties`
   - `secrets.GOOGLE_SERVICES_JSON` → 写入 `app/google-services.json`
5. **构建**：`./gradlew assembleRelease`（R8 裁剪 + 资源裁剪 + ABI 拆分）
6. **提取版本号与 changelog**：
   - tag 触发时从 `GITHUB_REF_NAME` 提取版本号（`v0.0.12` → `0.0.12`）
   - 手动触发时从 `app/build.gradle.kts` 的 `versionName` 提取
   - 用 `awk` 从 `docs/dev/changelog.md` 提取对应版本段落作为 Release notes
7. **发布 GitHub Release**（tag 触发或手动选择发布时）：
   - `softprops/action-gh-release@v3` 自动创建 GitHub Release
   - 上传 `app/build/outputs/apk/release/*.apk`（arm64-v8a、x86_64、universal 三个变体）
   - Release body 使用从 changelog.md 提取的内容
   - `make_latest: true` 标记为最新版本
8. **上传 Artifact**（手动触发且未选择发布时）：
   - `actions/upload-artifact@v4` 上传 APK，供手动下载
9. **清理**：构建后删除签名文件，减少残留风险

安全措施：

- **最小权限**：`permissions: contents: write`，仅授予发布 Release 所需权限
- **`.gitignore` 补全**：`*.jks`、`*.keystore`、`*.key`、`app/app.key`、
  `app/google-services.json` 全部忽略，防止误提交
- **签名文件清理**：`if: always()` 确保 CI 结束后删除临时签名文件

产物以 GitHub Releases 形式自动托管，用户可通过应用内更新检查下载。
后端 API 版本信息仍需手动维护（或后续通过 CI webhook 自动更新）。

### Submodule 注意事项

项目通过 git submodule 引入 `material-color-utilities` 库（`material3/material-color-utilities/`），
CI checkout 时必须配置 `submodules: recursive`，否则 `DynamicSchemeExt.kt` 会因找不到
`dynamiccolor` 包而编译失败。

submodule 指向的 commit 必须在上游仓库的分支/tag 上可达。如果对 submodule 做了本地修改
（如移除不安全的 `!!` 断言），该 commit 不会被推送到上游仓库，CI 将无法 fetch。解决方式：

1. 将修改推送到自己 fork 的 submodule 仓库，并更新 `.gitmodules` 的 URL 指向 fork
2. 或者直接将修改提交到上游 PR，合并后更新 submodule 指向
3. 或者 vendor 源码（移除 submodule，将文件直接提交到主仓库）

当前 submodule 指向上游 `material-foundation/material-color-utilities` 的 `main` 分支最新 commit。

### GitHub Secrets 配置

CI 需要以下 GitHub Secrets（仓库 **Settings → Secrets and variables → Actions**）：

| Secret 名 | 说明 | 格式 |
|-----------|------|------|
| `KEY_BASE64` | keystore 文件的 base64 编码 | `base64 -w0 keystore.jks` 的输出 |
| `SIGNING_CONFIG` | 写入 `local.properties` 的签名配置 | `storeFile=app.key` + 密码/别名（多行文本） |
| `GOOGLE_SERVICES_JSON` | google-services.json 全文（可选） | JSON 文件内容 |

`SIGNING_CONFIG` 的内容格式（注意 `storeFile` 是相对路径 `app.key`，不是本地绝对路径）：

```properties
storeFile=app.key
storePassword=<keystore 密码>
keyAlias=<key 别名>
keyPassword=<key 密码>
```

> 项目未使用 Firebase/Google Services，`GOOGLE_SERVICES_JSON` 可不配置，
> workflow 会自动跳过该步骤。

签名 Secrets 存为 Repository secrets（仓库级，所有 workflow 可访问）。
使用 `gh` CLI 配置示例：

```bash
# 生成 keystore 的 base64
base64 -w0 keystore.jks | gh secret set KEY_BASE64 --repo <owner>/<repo>

# 写入签名配置
echo -e "storeFile=app.key\nstorePassword=xxx\nkeyAlias=xxx\nkeyPassword=xxx" | gh secret set SIGNING_CONFIG --repo <owner>/<repo>
```

### 正式发版操作流程（Release SOP）

#### 1. 更新版本号

编辑 `app/build.gradle.kts`：

```kotlin
versionCode = 12          // 递增
versionName = "0.0.12"    // 与 tag 对应
```

#### 2. 更新 changelog

在 `docs/dev/changelog.md` 顶部新增版本条目：

```markdown
## 0.0.12（versionCode 12）— 2026-07-30

### 新增
- xxx

### 修复
- xxx

### 变更
- xxx
```

> changelog 的版本段落会被 CI 自动提取为 GitHub Release 的 body。

#### 3. 提交并打 tag

```bash
git add app/build.gradle.kts docs/dev/changelog.md
git commit -m "release: 0.0.12"
git tag v0.0.12              # tag 必须以 v 开头，格式 v*.*.*
git push origin master --tags
```

#### 4. CI 自动执行

推送 tag 后 GitHub Actions 自动触发：

1. 构建 release APK（R8 裁剪 + 签名 + ABI 拆分，约 8 分钟）
2. 从 `GITHUB_REF_NAME` 提取版本号（`v0.0.12` → `0.0.12`）
3. 从 `changelog.md` 提取 `## 0.0.12` 段落作为 Release notes
4. 使用 `softprops/action-gh-release@v3` 创建 GitHub Release
5. 上传 3 个 APK 变体（`arm64-v8a`、`x86_64`、`universal`）

#### 5. 验证 Release

打开仓库 **Releases** 页面，确认：

- Release 标题：`MeasixPilot 0.0.12`
- Release body：changelog.md 中 `## 0.0.12` 段落内容
- 附件：3 个 APK 文件

#### 6. 更新后端 API（手动）

GitHub Release 的 APK 下载 URL 格式固定：

```text
https://github.com/{owner}/{repo}/releases/download/v{version}/MeasixPilot_{version}_{abi}-release.apk
```

在后端服务中更新 `UpdateInfo`，`downloads[].url` 指向 GitHub Release 的 asset 下载链接。

### 上游 RikkaHub CI（`.github/workflows/daily-build.yml`）

```yaml
name: Daily Build
on:
  schedule:
    - cron: '0 18 * * *'      # 每天 UTC 18:00（北京时间次日 02:00）
  workflow_dispatch:           # 也支持手动触发
```

上游采用每日自动构建策略，流程：

1. **提交检查**：定时触发时检查过去 24 小时是否有新提交，无则跳过；手动触发时无条件构建
2. **Checkout**：`fetch-depth: 0` + `submodules: recursive`
3. **JDK**：Temurin 17
4. **pnpm + Node**：上游包含 `web-ui/` 前端模块（React Router 前端），构建前需
   `pnpm install --frozen-lockfile` + `pnpm run build`
5. **准备签名文件**：与当前项目相同的三组 Secrets
6. **构建**：`./gradlew assembleRelease`
7. **发布 Prerelease**：使用 `softprops/action-gh-release@v2` 发布到 GitHub Releases：
   - 固定 tag `nightly`，每晚覆盖同一个"最新每日构建"
   - `prerelease: true`，标记为预发布
   - body 注明 commit SHA 和"开发版本，可能不稳定，仅供测试"
   - files 直接上传 `app/build/outputs/apk/release/*.apk`

### 上游的更新后端

上游 API 端点 `https://updates.rikka-ai.com/` 返回的 `UpdateInfo.downloads[].url` 指向
GitHub Releases 的 nightly 预发布 APK 下载链接。因此上游的完整更新链路是：

```text
daily-build CI → GitHub Releases (nightly tag) → updates.rikka-ai.com API → 客户端检查
```

### 当前项目的更新后端

当前项目 API 端点 `https://measix.weero.net/mobile/` 由独立的后端服务维护。
CI tag 驱动构建后自动发布到 GitHub Releases，但后端 API 的版本信息仍需手动更新。
`downloads[].url` 指向 GitHub Release 的 asset 下载链接，格式固定。

### APK 产物命名

```kotlin
// app/build.gradle.kts
android.applicationVariants.configureEach {
    outputs.configureEach {
        val fileName = (this as ApkVariantOutput).outputFileName
        (this as ApkVariantOutput).outputFileName =
            fileName.replace(Regex("^app-"), "MeasixPilot_${versionName.get()}_")
    }
}
```

Release APK 命名格式：`MeasixPilot_{versionName}_{abi}.apk`，
如 `MeasixPilot_0.0.11_arm64-v8a.apk`。

---

## 与上游 RikkaHub 的差异

### 客户端代码

Fork 时完整保留了上游的版本检查机制，仅做了以下适配：

| 维度 | Measix Pilot（当前 Fork） | 上游 RikkaHub |
|------|--------------------------|---------------|
| API 端点 | `https://measix.weero.net/mobile/` | `https://updates.rikka-ai.com/` |
| User-Agent | `MeasixPilot {version} #{code}` | `RikkaHub {version} #{code}` |
| 包名 | `net.weero.measix.pilot` | `me.rerere.rikkahub` |
| 代码逻辑 | 完全一致 | — |
| 数据模型 | 完全一致 | — |
| UI 组件 | 完全一致 | — |
| Version 比较逻辑 | 完全一致 | — |
| Play Store 检测 | 完全一致 | — |

### CI 与发行版托管

| 维度 | Measix Pilot（当前 Fork） | 上游 RikkaHub |
|------|--------------------------|---------------|
| CI 文件 | `release.yml` | `daily-build.yml` |
| 触发方式 | tag 驱动 (`push: tags: v*.*.*`) + 手动 | 每日定时 + 手动 |
| 前端模块 | 无（精简移除） | `web-ui/`（pnpm + React Router） |
| 子模块 | `material-color-utilities`（`submodules: recursive`） | `submodules: recursive` |
| 产物托管 | GitHub Releases（正式版）+ Artifacts（手动构建） | GitHub Releases（nightly 预发布） |
| Release notes | 从 `docs/dev/changelog.md` 自动提取 | commit SHA + 固定文案 |
| 后端 API | `measix.weero.net`（独立维护） | `updates.rikka-ai.com`（自动关联 Releases） |
| 签名 Secrets | `KEY_BASE64` / `SIGNING_CONFIG` / `GOOGLE_SERVICES_JSON` | 相同 |
| 签名配置容错 | 四项完整才创建 release 配置 | 始终创建 release 配置（可能为空） |
| 权限收敛 | `permissions: contents: write` | 默认 |
| 签名清理 | `if: always()` 删除临时文件 | 无 |

---

## 架构注意事项

### 当前设计的局限

1. **错误 dismiss 非持久化**：`errorDismissed` 是 `UpdateChecker` 单例上的进程级状态，跨会话不重复打扰，但重启应用后会重试。成功版本的关闭状态写入 `Settings.ignoredUpdateVersion`，只有版本变化后才再次提示。

2. **下载完成无自动安装**：DownloadManager 下载完成后仅在通知栏提示，用户需手动点击通知
   安装 APK。未监听下载完成广播来弹出自定义安装引导。

3. **错误状态无重试**：检查失败时仅展示静态错误卡片，无重试按钮。

4. **无强制更新**：所有更新都是可选的，用户可以永久忽略。没有 minimumVersion 机制来
   强制用户升级到安全版本。

5. **下载无 SHA 校验**：CI 构建后未生成 APK 的 SHA-256 校验文件，用户无法
   验证下载完整性。依赖 HTTPS 传输保证安全性。后续可在 Release 中附带 `.sha256` 文件。

6. **后端 API 未自动更新**：CI 已自动发布到 GitHub Releases，但后端 API
   （`measix.weero.net/mobile/`）的版本信息仍需手动维护。后续可通过 CI webhook
   在发版后自动通知后端更新 `UpdateInfo`。

### 演进方向

- **下载完成监听**：注册 `DownloadManager.ACTION_DOWNLOAD_COMPLETE` 广播，下载完成后
  弹出安装确认对话框
- **CI 自动发布**：CI 已实现 tag 驱动自动发布到 GitHub Releases；后续可扩展
  为发版后自动触发后端 API webhook 更新版本信息，实现全链路自动化
- **增量更新**：考虑接入 APK 增量更新（如 bsdiff/delta）减少下载体积
- **多渠道分发**：支持 GitHub Releases、自建 CDN 等多下载源，提供备选链接
