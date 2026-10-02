# 历史 Android 客户端的 Core 兼容探针

此目录保存针对 **Android `29ecc109335c536c6f2b60841e3d4aace35b0dd3`** 的设备探针。
它不属于当前 App 测试源集，不应改为使用当前 DTO 后再声称验证了旧客户端。
执行顺序由本文维护，验证分层见 [测试策略](../../docs/references/testing-strategy.md)。
通过与否以当次 APK、Core 身份及实际运行报告为准，不将历史结果作为当前验收。

## 构建旧客户端

1. 在独立 checkout 检出上述提交，初始化 `material3/material-color-utilities` 的锁定提交，配置本机 SDK。
2. 将本目录 Kotlin 文件复制到旧 checkout 的
   `app/src/androidTest/java/net/weero/measix/pilot/data/enterprise/`。
3. 旧 `SettingMcpPageAndroidTest` 两处 `McpServerPresentation` fixture 缺少必填参数；仅在这两个
   `status = McpStatus.Idle` 后增加 `sessionCallable = false`，使原测试源集可以编译。
   不修改旧生产代码、wire、mapper、hash 校验或同步状态机。
4. 串行执行 `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-parallel --max-workers=1`。
   保存旧 APK/测试 APK SHA-256，并检查生产源代码 diff 为空。
5. 只在专用模拟器安装。旧数据库不能直接覆盖当前版本测试数据库；仅允许清理已确认属于本次验证的
   独立测试应用，不得清除用户手机或共享 demo 数据。

## 服务端与设备场景

使用隔离 Core、独立数据库和端口。由旧 Core `1b70fcb` 的正式 Draft/Publish 创建 v4，再将原库迁移并
交给新 Core。保留其 release ID/hash；由新 Core 分别正式发布有 Starter 和无 Starter 的 v5。
不能将当前 v5 JSON 手工删除字段、改成 v4 当作历史发布证据。无需有效模型供应商密钥：本探针验证
配置和执行准入，不调用模型供应商。历史 Republish 会生成新的 release/generation，不能沿用旧数字。

通过 stdin 将下列 JSON 写到应用私有 `cache/compatibility-input.json`，权限仅限应用；不得将真实
enrollment 写入源码、命令参数、报告或 Git。`enrollment` 是完整接入资料的 **JSON 字符串**，包含
`formatVersion/kind/platformUrl/code/expiresAt` 五个字段，仅 join 场景需要。

```json
{
  "scenario": "v4_join",
  "expectedGeneration": 1,
  "expectedStarterCount": 3,
  "expectedReason": "unknown_platform_field",
  "enrollment": "<调用方提供的完整接入资料>"
}
```

执行入口：

```text
adb -s <专用设备> shell am instrument -w -r
  -e class net.weero.measix.pilot.data.enterprise.PreContextCoreCompatibilityAndroidTest
  -e coreCompatibilityInput /data/user/0/net.weero.measix.pilot.debug/cache/compatibility-input.json
  net.weero.measix.pilot.debug.test/androidx.test.runner.AndroidJUnitRunner
```

上例为便于阅读换行，实际命令须按当前 shell 续行。每次运行前 force-stop 应用，使重开检查真正使用新
进程；只有新安装/新接入的隔离场景清应用数据。每次结束删除私有输入文件，并同时检查输出含
`OK (1 test)` 且无失败；不能只看 adb 退出码。

| 顺序 | 场景 | 服务端及断言 |
| --- | --- | --- |
| 1 | `v4_join` → `v4_reopen` | 新 Core 提供真实 v4；READY、Starter 预填、执行准入通过，重开保留配置 |
| 2 | `v5_sync_reject` → `v5_reopen_reject` | 发布有 Starter 的 v5；拒绝 `unknown_platform_field`，v4 Applied/配置摘要不变，执行拒绝、重开诊断可见 |
| 3 | 同上两个场景 | 发布无 Starter 的 v5；明确传入 `invalid_platform_ManagedSnapshot_schemaVersion`，其余保全断言相同 |
| 4 | `v5_join_reject` → `v5_pending_reopen` | 分别对两种 v5 在空测试应用接入；指定对应 reason，无 Applied，CONFIGURATION_PENDING |
| 5 | `v4_recover` | 步骤 4 保留原绑定；Core Republish 历史 v4 后同步，传入实际新 generation；无需重新接入，恢复 READY、预填、执行准入且清除恢复诊断 |

步骤 1 将比较基线保存在私有 cache；步骤 4 将待恢复 session 基线保存在私有 cache。步骤 2/3 不可清数据，
步骤 4 至步骤 5 之间也不可清数据。外部 files 下的 `core-compatibility-evidence/*.json` 仅记录
schema/generation/phase/reason、数量和布尔断言，不含接入凭据或原文。

探针调用旧 `EnterpriseApplicationService`、同步 owner、配置 query 和会话 Starter 用例，不旁路写 DB，
不自建简化解码器。它不是供应商调用成功、完整聊天历史保全、OEM 真机或 Release 混淆验收。


## 当前 Android 的真实 Core 兼容探针

当前生产代码的 opt-in 测试为
`app/src/androidTest/java/net/weero/measix/pilot/data/enterprise/PlatformSnapshotCompatibilityLiveAndroidTest.kt`。
它与上面的旧客户端探针相互独立，不修改旧 APK、旧探针或任何生成 DTO。

输入通过 stdin 写入专用设备应用私有 cache，instrumentation 参数名为 `snapshotCompatibilityInput`。
凭据仅放输入文件，不放命令行、日志或提交；完成后删除输入文件。

```json
{
  "scenario": "join",
  "expectedSchema": 4,
  "expectedGeneration": 1,
  "expectedReleaseId": "<实际 release ID>",
  "expectedSnapshotHash": "<实际 sha256:hash>",
  "expectedStarterCount": 3,
  "enrollment": "<完整接入资料 JSON 字符串>"
}
```

- `scenario` 为 `join`、`sync`、`reopen`、`reject` 或 `recover`。
- 成功场景必填 `expectedSchema`（4 或 5）和 `expectedGeneration`；建议传入真实
  `expectedReleaseId`、`expectedSnapshotHash` 和 `expectedStarterCount` 作精确断言。
- `join` 必填 `enrollment`；`seedHistory` 默认 true，通过现有命令 owner 创建明确属于本企业主体的一条
  USER/ASSISTANT 两节点历史 fixture，不调用供应商，可显式传 false 禁用。
  `reject` 如带 `enrollment`，表示空应用首次接入失败；不带则检查已有绑定。
- `reject` 必填 `rejectedSchema`（6、7 或 3）；6/7 要求 `UPDATE_APP`，3 要求 `UPDATE_PLATFORM`。
  这些值是隔离 HTTP 故障注入场景，不是 Core 正式发布的协议版本。
- `reopen` 必须先 force-stop 再运行，保持服务端发布与基线一致；探针先核对本地恢复，不调用同步来掩盖恢复故障，然后验证执行准入。`recover` 不得清数据或重新接入。

```text
adb -s <专用设备> shell am instrument -w -r
  -e class net.weero.measix.pilot.data.enterprise.PlatformSnapshotCompatibilityLiveAndroidTest
  -e snapshotCompatibilityInput /data/user/0/net.weero.measix.pilot.debug/cache/snapshot-compatibility-input.json
  net.weero.measix.pilot.debug.test/androidx.test.runner.AndroidJUnitRunner
```

建议顺序：真实历史 v4 的 `join` → `reopen`；新 Core 正式发布 v5 的 `sync` → `reopen`；
再正式发布新 generation，通过独立代理仅改 Snapshot 外层版本为 6 并保留新增字段，运行 `reject`；
切换为版本 7、3 分别运行 `reject`；关闭注入后同 Session `recover`。另外在全新独立测试身份下重复
`reject`（带 enrollment）→ 重开后的 `reject`（不带 enrollment）→ `recover`，验证首次无 Applied 的企业空间。
代理必须转发到隔离 Core，并准确记录注入范围，不得把构造的未来版本当作 Core 正式发布证据。

每个场景验证企业 → 个人 → 企业往返、同步状态和执行准入。支持版本验证本地保存的真实 Snapshot
版本、generation、release/hash，以及 v4 Starter 无开场、v5 Starter 有开场和真实预填用例。
拒绝场景比较绑定、Applied、配置摘要、域内历史数量和受管助手历史列表摘要，且拒绝执行；恢复沿用
原 Session。若 join 创建了历史 fixture，还经 Session 授权和会话 owner 核验其主体，再读取完整持久
节点序列并比较规范序列化 SHA-256；拒绝、重开与恢复均验证其 USER/ASSISTANT 内容及节点身份未变。
未发起模型供应商调用。

### v4 APK 覆盖安装

`PlatformCoverInstallationLiveAndroidTest.kt` 位于当前 `app/src/androidTest/.../data/enterprise/`。
将同一源文件复制到上文固定旧 checkout 的对应测试目录；只修正文已列出的旧测试 fixture，旧生产源码保持不变。
使用新的专用设备先安装旧 Debug APK 及其测试 APK，通过 `v4_join` 接入真实历史 v4 发布。
以 `-e coverInstallation seed` 执行该类，沿原 owner 保存企业与个人各一条两节点历史、个人主题和禁用的个人 MCP 定义。
然后 force-stop，使用 `adb install -r` 覆盖安装当前 Debug APK 及当前测试 APK，不清数据、不重新接入；
以 `-e coverInstallation verify` 执行同一类。先比较本地恢复的 Session、Applied、节点身份/正文和个人设置，
再显式同步并检查执行准入。旧本地执行描述没有 Snapshot 版本字段，不能伪造版本来跳过完整同步。

保留两份 APK 的 SHA-256、旧源码/子模块提交、生产源文件未修改的审计结果及真实 release/generation/hash。
私有 `cache/cover-installation-baseline.json` 跨安装保留，外部 `cover-installation-seed.json` 与
`cover-installation-verify.json` 只输出断言结果。该验证覆盖 Debug 安装升级，不代表生产签名或 OEM 真机验证。

基线保存于私有 `cache/snapshot-compatibility-baseline.json`，跨场景保留；接入凭据不写入基线。
非敏感结果位于外部 files 的 `snapshot-compatibility-evidence/<scenario>.json`。
runner 应每次收集后按运行顺序另存，避免同名场景覆盖证据，并检查 `OK (1 test)` 和结果 `passed=true`。
历史证据记录实际数量和 `historyFixtureNodeCount/historyFixtureDigest`：空历史的摘要验证不能表述为
非空聊天正文保全；两节点 fixture 仅证明该真实企业历史的节点保全，不代替全库、附件、数据库迁移或
OEM 真机验收。测试通过与否应以本次 APK、Core 及实际运行报告为准。
