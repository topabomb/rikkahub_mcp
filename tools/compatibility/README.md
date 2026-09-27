# 文档创建前 Android 的 Core 兼容探针

此目录保存针对 **Android `29ecc109335c536c6f2b60841e3d4aace35b0dd3`** 的设备探针。
它不属于当前 App 测试源集，不应改为使用当前 DTO 后再声称验证了旧客户端。
实际结果与发布顺序见 [研究文档第 14.9 节](../../docs/dev/context-injection-and-enterprise-starter-research-2026-09-25.md)。

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
