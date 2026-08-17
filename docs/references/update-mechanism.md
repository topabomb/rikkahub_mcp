# 版本检查与发行链路参考

本文档描述当前应用内更新检查、版本判断、下载委托与 GitHub Release 构建链路。它记录实现契约，不承担发布历史或未来规划；版本内容以 [`docs/dev/changelog.md`](../dev/changelog.md) 为准。

## 1. 组件与职责

| 组件 | 职责 |
|------|------|
| `UpdateChecker` | 请求更新 API、共享进程级检查状态、委托系统下载 |
| `Version` | 对远程版本和当前 `BuildConfig.VERSION_NAME` 做宽松的 SemVer 优先级比较 |
| `UpdateCard` | 展示检查失败或新版本入口，并通过 `AdaptiveModal` 展示详情与下载项 |
| `PlayStoreUtil` / `rememberIsPlayStoreVersion` | 判断安装来源，避免 Play Store 安装包重复显示应用内更新 |
| `Settings.displaySetting.showUpdates` | 用户是否启用更新提示；`false` 表示永久关闭（旧配置兼容） |
| `Settings.displaySetting.updateCheckDisabledUntilEpochMillis` | 更新提醒暂停到该时刻；`0` 表示未按时间暂停 |
| `DisplaySetting.areUpdateChecksEnabled()` | `showUpdates && now >= pauseUntil` |
| `Settings.ignoredUpdateVersion` | 持久化用户已关闭的远程版本 |
| `.github/workflows/release.yml` | 构建、验签、发布 GitHub Release，并通过 `repository_dispatch` 触发网站同步 `version.json` |

`UpdateChecker` 由 Koin 注册为单例并持有 AppScope 级 `updateState`。所有 `ChatVM` 共享同一 `StateFlow`，不会因切换会话或重建页面重复请求。

## 2. 客户端检查流程

```text
ChatDrawer 组合
  -> areUpdateChecksEnabled() 且安装来源不是 Play Store
  -> UpdateCard 订阅 UpdateChecker.updateState
  -> SharingStarted.Lazily 首次启动 checkUpdate()
  -> GET https://measix-pilot.weero.net/version.json
  -> 解析 UpdateInfo
  -> Version(remote) > Version(current)
  -> 展示更新卡片或保持隐藏
```

设置页把开关改成 7/14/21 天暂停对话框。暂停到期后抽屉会重新组合 `UpdateCard`。旧的 `showUpdates=false` 仍视为关闭，点“立即恢复”会写回 `showUpdates=true` 并清空暂停时间。检查流仍由单例 `UpdateChecker` 负责，不按会话重建。

请求使用全局 `OkHttpClient`，在 `Dispatchers.IO` 上执行。User-Agent 形状为：

```text
MeasixPilot <VERSION_NAME> #<VERSION_CODE>
```

远程 JSON 映射为：

```kotlin
@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>,
)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
)
```

未知 JSON 字段会被忽略；HTTP 非成功状态、网络异常或反序列化失败都转换为 `UiState.Error`，不影响聊天功能。

### 进程内共享与关闭语义

- `updateState` 使用 `stateIn(AppScope, SharingStarted.Lazily, UiState.Loading)`。首次订阅后上游只启动一次，即使订阅者暂时消失也不会重建冷 Flow。
- 检查失败卡片的关闭状态保存在单例 `UpdateChecker.errorDismissed`，只作用于当前进程；下次启动仍会重新检查。
- 新版本卡片的关闭状态写入 `Settings.ignoredUpdateVersion`。远程版本变化后会自然恢复提示。
- `UiState.Loading` 和“没有更高版本”都不渲染卡片。

## 3. 版本比较

`Version` 忽略 `+build`，按数值 core 段和 prerelease 段比较优先级：

- core 缺位按 `0` 比较；
- 有 prerelease 的版本低于同 core 的正式版；
- prerelease 数字段按数值比较，数字低于字符串；
- prerelease 前缀相同时，字段更少的版本优先级更低。

该解析器为了兼容后端输入而容忍缺失或非数字 core 段，非数字 core 会按 `0` 处理。因此它用于更新排序，不是严格的 SemVer 格式校验器。后端仍应发送规范的版本号。

## 4. UI 与下载

更新入口位于聊天抽屉，必须同时满足：

```text
DisplaySetting.areUpdateChecksEnabled()
&& !PlayStoreUtil.isInstalledFromPlayStore(context)
```

成功且发现新版本时，卡片显示版本号。点击卡片打开 `AdaptiveModal`：窄屏使用 BottomSheet，宽屏使用居中 Dialog；内容包括发布时间、Markdown changelog 和下载列表。

点击下载项后，`UpdateChecker.downloadUpdate()`：

1. 创建 `DownloadManager.Request`；
2. 保存到公共 `Downloads` 目录；
3. 允许 Wi-Fi 与移动网络，设置 APK MIME 类型；
4. 由系统通知展示下载进度和完成状态；
5. enqueue 失败时显示本地化错误并用浏览器打开下载 URL。

应用不自行维护下载进度，也不自动触发安装。下载地址和文件大小完全来自受信任的更新 API，因此后端必须只发布 HTTPS 地址和预期 APK。

## 5. Play Store 安装来源

`PlayStoreUtil` 在 Android 11 及以上使用 `PackageManager.getInstallSourceInfo()`，旧版本使用 `getInstallerPackageName()`；安装包名为 `com.android.vending` 时视为 Play Store 来源。查询异常按“非 Play Store”回退，使侧载包仍能使用应用内更新。

## 6. Release 构建契约

`app/build.gradle.kts` 的发行相关约束：

- `versionCode` 与 `versionName` 是客户端版本和 Release 命名的唯一来源；
- APK 构建启用 `arm64-v8a`、`x86_64` 与 universal 输出，App Bundle 构建时关闭 ABI splits；
- `assembleRelease` 完成后把 `app-*.apk` 重命名为 `MeasixPilot_<version>_*.apk`；
- Release 使用 AGP optimization/R8；必要 keep rules 位于 `app/src/main/keepRules/rikkahub.keep`；
- 只有 `local.properties` 同时包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 时才创建 release signing config。

没有签名配置的本地 `assembleRelease` 可以生成未签名产物，但它不构成可发布版本。

## 7. GitHub Actions 发行流程

`.github/workflows/release.yml` 支持版本 tag 和手动触发：

```text
checkout（含 submodule）
  -> JDK / Gradle
  -> 准备可选签名与 Google Services 文件
  -> 正式发布时强制校验签名 Secrets
  -> assembleRelease
  -> 正式发布时逐个 apksigner verify
  -> 从 versionName/tag 与 changelog 生成 Release 信息
  -> 发布 GitHub Release 或上传非发布 Artifact
  -> always 清理临时签名文件
```

正式发布包括 tag 触发和手动 `publish_release=true`。这两种情况必须提供 `KEY_BASE64` 与 `SIGNING_CONFIG`，并且所有 APK 必须通过 `apksigner verify` 后才能上传。手动的非发布 Artifact 构建允许不提供签名，产物不得当作正式版本分发。

Release notes 从 `docs/dev/changelog.md` 中提取与版本号匹配的 `## <version>` 段落。GitHub Release 发布后，`release.yml` 自动通过 `repository_dispatch` 触发网站仓库（`measix-pilot-website`）的 `sync-from-release` 工作流，将版本信息、changelog 和下载列表写入静态 `version.json`。App 通过请求该静态文件检查更新，无需动态后端。

### 签名配置形状

`SIGNING_CONFIG` 写入 `local.properties`，其中 `storeFile` 相对 `app` 模块解析：

```properties
storeFile=app.key
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

签名文件、`local.properties` 和可选 `google-services.json` 都是临时 CI 输入，不得提交到仓库。

## 8. 维护与验证

修改客户端更新链路时至少验证：

- `VersionTest`：正式版、预发布、build metadata、宽松 core 比较；
- `UpdateCheckerTest`：首次订阅、订阅消失后不重启、进程内共享；
- 更新卡片的持久化关闭、错误关闭和 Play Store 过滤；
- `lint` 与 `assembleDebug`。

正式发版还必须验证 Release 构建、所有 APK 签名、版本号、ABI 产物、changelog 提取结果、`repository_dispatch` 触发成功以及线上 `version.json` 内容正确。GitHub Actions 成功不替代真实安装与升级验证。

## 9. 关键文件

```text
app/src/main/java/net/weero/measix/pilot/
├─ utils/UpdateChecker.kt
├─ utils/PlayStoreUtil.kt
├─ ui/components/ui/UpdateCard.kt
├─ ui/hooks/PlayStore.kt
└─ data/datastore/PreferencesStore.kt

app/build.gradle.kts
.github/workflows/release.yml
docs/dev/changelog.md

measix-pilot-website 仓库：
.github/workflows/sync-from-release.yml
docs/.vuepress/public/version.json
docs/changelog/index.md
```
