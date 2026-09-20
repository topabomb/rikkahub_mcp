# MEASIX Pilot Android 自主通知接收 V1 方案

研究日期：2026-09-18。
Android 审查基线：`topabomb/rikkahub_mcp`，`master`，commit `5de1b084516798ee8eda971ff3340c5608dc95cc`。

本文是针对已读取代码和官方平台文档制定的实现设计，不是已经完成的实现或真机性能报告。本次未修改远程仓库、未编译新功能、未执行真机测试。服务端的内容是接口与持久化契约设计，不声称已经审查或确认现有服务端拥有这些能力。

## 1. 决策与范围

V1 采用：用户主动开启的 `specialUse` 前台服务 + 单条独立 OkHttp WSS 连接 + 服务端持久化顺序消息流 + Room 收件箱和游标 + 本地系统通知。

暂不接入 FCM、手机厂商 SDK、聚合推送和 UnifiedPush 外置接收器；不依赖第三方 App 拉活。不增加 MQTT broker、Kafka、Redis、多通道插件框架、独立 Android 进程或新 Gradle 模块。不把 WorkManager/AlarmManager 当成周期性复活器。

“自主实现”指在现有成熟网络与数据库库之上实现自己的接收协议和业务接入，不是自行实现 WebSocket、TLS、重连线程池或数据库。

发布基线是开发者直接分发的 Android APK。未来发布 Google Play 时，specialUse 的用途申报与审核必须独立核实，不能视为已获准。

可交付目标：满足运行和网络条件时及时提醒；网络/进程中断后，在保留期内恢复完整接收；重复传输不形成重复消息记录；开关、失败与能力状态透明。不能承诺：用户强行停止或 ROM 持续禁止运行后仍由远程消息自主唤醒；通知关闭/勿扰时强制提醒；没有电池优化豁免时所有设备深度待机都实时到达。

## 2. 代码审查结论

下列 Kotlin 路径均以 `app/src/main/java/net/weero/measix/pilot/` 为前缀。

| 文件 | 已确认的当前实现 | V1 决策 |
|---|---|---|
| `app/build.gradle.kts` | minSdk 26；compileSdk/targetSdk 37 | 测试覆盖 26、33、35、37；不按旧 Android 后台规则实现 |
| `app/src/main/AndroidManifest.xml` | 已有 FGS/specialUse/通知权限；生成服务为 dataSync；OAuth 回调服务为 specialUse；应用允许明文流量 | 新增独立接收 Service/恢复 Receiver；通知端点强制 WSS，不能继承明文宽松策略 |
| `service/ChatGenerationForegroundService.kt` | 只观察 RESPONSE_GENERATION；START_NOT_STICKY；生成结束即停；FGS ID 9001 | 保持生命周期，不改成全天接收服务 |
| `service/ChatNotificationManager.kt` | 订阅 AppEventBus 中本地生成事件；发布前校验原 RealmAccess；完成通知 ID 固定为 1 | 不注入远程 ChatGenerationEnded；新通道使用独立 tag 命名空间 |
| `utils/NotificationUtil.kt` | 可复用构建 DSL；notify 返回 Boolean；只用数值 ID | 新增带 tag 的发送入口与可诊断结果，保留旧调用兼容；返回值不能称为用户已看到 |
| `di/DataSourceModule.kt` | 全局 OkHttp 带 ProviderSessionHeaderInterceptor、RequestLoggingInterceptor 等 | 单独构建并命名注入通知 OkHttp，不直接 newBuilder 继承模型请求拦截器 |
| `data/ai/mcp/NetworkMonitor.kt` | isOnline 依赖 INTERNET && VALIDATED；DefaultNetworkCallback | 保留 MCP 现有语义；新增默认网络身份/变更代际投影，通知连接不以 VALIDATED 为唯一硬门禁 |
| `data/db/AppDatabase.kt` | Room schema v12；要求 additive migration、schema 和迁移测试 | 增加两张表；基于当前版本做 12→13，实施时若版本推进则顺延 |
| `service/ApplicationRecoveryCoordinator.kt` | 所有 durable write 共用 ApplicationRecoveryGate | Service 先进入前台，再 awaitReady，之后才接收写库；不能反向让启动恢复等待接收连接 |
| `MeasixPilotApp.kt`、`di/AppModule.kt` | 启动时初始化 QuickJS、建立 Koin、启动恢复与部分 eager 单例 | 不增加 eager 网络连接；测试后台冷启动耗时，先不拆进程 |
| `service/EnterpriseSynchronizationService.kt` | 同步当前 Core Managed State / Snapshot | 当前主动同步不是远程通知接口；通知仍需独立授权、游标与恢复合同 |
| `service/EnterpriseExitService.kt` | 串行收口企业退出、取消资源；启动恢复期间也可能执行 | 加入停止旧绑定及取消通知的收口；恢复路径不得 awaitReady 造成死锁 |
| `RouteActivity.kt` | 冷启动/新 Intent 以 conversationId 打开本地会话；新 Intent 取得当前域 | 新建 notification_id 打开入口；读取本地记录的原主体后授权，不把远程资源 ID 当本地 UUID |
| `data/sync/BackupArchiveService.kt`、`BackupDataGraph.kt` | 应用自建备份；重建数据库并按个人/企业域合并 | 接收身份、游标不随备份迁移；同设备恢复要保留最新实时接收状态，不回滚到备份时刻 |

## 3. 最小架构

```text
业务事件（已授权发送）
    │ 在业务数据库事务内登记通知
    ▼
服务端通知消息表 ── 顺序读取/补发 ── WSS 443
                                      │
Android RemoteNotificationService      │
    └── RemoteNotificationController ◄─┘
          ├── 接收状态机与唯一连接
          ├── Room：消息 + 游标原子提交
          ├── ACK：仅确认持久接收
          └── RemoteNotificationRenderer
                  └── 系统通知 → RouteActivity → 本地通知详情
```

主要职责只分四块：Service 管 Android 生命周期；Controller 管唯一连接和接收流程；Repository 管事务/游标；Renderer 管展示。实体/DAO、协议数据类、一个恢复 Receiver、一个设置页属于必要配套，不再拆成多层通用推送平台。

V1 每次启用一组服务端地址与接收绑定，不做任意数量服务端同时订阅。一台设备只允许一个有效通知连接代际。前后台切换不创建第二条连接。

收件箱独立于 Conversation/Turn/Tool。通知不是执行指令，收到通知不能启动 Agent、批准工具、修改企业配置或绕过原有权限与恢复门禁。

## 4. Android 生命周期与后台条件

### 4.1 启用

用户在通知设置页输入或导入服务端专用接收配置，确认主体，授权系统通知并主动开启持续接收。保存 `enabled=true` 后，在可见 Activity 中启动前台服务。

服务启动顺序：

1. 尽快建立低重要度的持续接收前台通知，不等数据库全量恢复或网络握手。
2. 在 Service 自己的 CoroutineScope 中等待 ApplicationRecoveryGate 就绪。
3. 读取最新接收绑定，确认仍启用、凭据可用、主体仍被授权。
4. 连接 WSS、续传、恢复未完成展示。

`enabled` 是持久化用户意图；`Connected` 是内存中的实际连接状态，两者不能互相代替。启动失败时可以保留用户意图，但必须显示阻止原因，不能伪报已连接。

### 4.2 FGS 类型

新增 Service 使用 `specialUse`，说明真实用途，例如 `User-enabled persistent connection for receiving service notifications from the configured server`。API 34+ 使用相应 service type/permission；较旧 API 走其支持的 startForeground 路径。

不复用 dataSync，也不把普通服务器通知错误解释成 remoteMessaging 的跨设备消息连续性用途。不使用静音音频、虚假定位、无障碍、设备管理员、通知监听权限或全屏 Intent 保活。

示意声明：

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".service.notification.RemoteNotificationService"
    android:exported="false"
    android:stopWithTask="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="User-enabled persistent connection for receiving service notifications from the configured server" />
</service>
```

现有 FGS、specialUse 和通知权限复用，不重复新增。WAKE_LOCK 只用于已经收到事件后的短时间处理，不是永久 CPU 保活。

### 4.3 停止与恢复

用户点击应用开关或前台通知中的停止：先持久化 enabled=false，再撤销当前连接代际、关闭 socket/重试任务并 stopForeground/stopSelf。Service 停止不能取消本地聊天生成。

接收服务在正常启用期间返回 START_STICKY；收到 null Intent 必须重读持久配置。它表示允许系统尝试恢复，不能承诺特定重启时间。原 ChatGenerationForegroundService 继续 START_NOT_STICKY。

恢复 Receiver 仅处理 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED。先检查用户已经开启，再尝试启动；用户未开启不启动。V1 不做首次解锁前接收，不处理 LOCKED_BOOT_COMPLETED。遇到后台启动拒绝记录原因，待用户交互恢复，不套娃拉起。

从最近任务划掉不主动停服务，但各 ROM 的清理行为需要实测。用户从系统 Active apps 停止或执行 Force stop 后，不用 Worker/闹钟/其他 App 抢着复活。两种系统停止语义不完全相同，测试分开。再次出现用户交互时提示或恢复其明确允许的状态。

### 4.4 Doze 和耗电

前台服务不能自动获得 Doze 网络豁免。持续后台接收的启用说明必须引导用户允许电池优化豁免，并通过 isIgnoringBatteryOptimizations 读取结果。V1 优先打开通用系统设置页，不建设厂商私有 Activity 跳转表；无法程序化确定的“自启动/后台运行”设置明确标为需用户确认。

未豁免仍允许用户试用，但状态应为“后台接收可能延迟”，不能标成可靠后台就绪。豁免也不等于 ROM 永不杀进程。

不持有整夜 PARTIAL_WAKE_LOCK、不锁屏幕、不使用几秒一次心跳。已进入 onMessage 的事件处理可使用有上限（例如 10 秒）的短 partial wake lock，finally 释放；不覆盖等待消息、重连退避或长时间磁盘恢复。短锁用于完成已被调度的处理，不能把未获得调度的进程唤醒。

## 5. 网络实现

独立 `OkHttpClient.Builder()`，通过 Koin 命名限定符注入。复用依赖但不继承全局模型 HTTP client 的拦截器。禁用携带鉴权请求的自动重定向；默认正常证书校验，不接受 trust-all 或任意自签名证书绕过。自建主机使用正常可验证证书，通知端点强制 wss/https。

网络变化由现有 NetworkMonitor 的增强投影驱动，保留其 MCP isOnline API。新增投影至少含默认网络身份和单调 revision；不能只依赖可能被 StateFlow 合并的 false→true 瞬时变化。收到网络切换后撤销旧连接代际，再建立新连接。

VALIDATED 是系统一般联网验证，不是对自建端点的验证。缺失 VALIDATED 的网络可以做有限的实际端点握手尝试；无默认网络时暂停重连定时器，网络回来再尝试。TLS错误、401/403 与普通网络故障分开，不对错误凭据无限高频重试。

初始参数（设计默认值，不是跨机型最优实测值）：

| 项目 | 默认 |
|---|---|
| 连接/握手超时 | 20 秒 |
| 服务端应用层 heartbeat | 120 秒；携带 server_time/head_seq |
| 客户端响应 | 收到 heartbeat 后立即发送 pong 与本地持久 cursor |
| 失活判定 | 360 秒没有对端有效活动；本地检测需获得 CPU 调度 |
| 重连退避 | 2、4、8…至 300 秒，加随机抖动；网络切换可立即触发一次 |
| 重试计数复位 | 连续稳定在线 5 分钟后，不在握手刚成功时复位 |
| 单事件大小 | 上限 8 KiB；拒绝或隔离超限事件 |
| 服务端未确认窗口 | 最多 32 条；超时或积压关闭，后续从持久 cursor 续传 |
| 本地回调队列 | 有界；满时关闭连接而不是丢弃事件继续 ACK |

初始采用服务端 heartbeat JSON + 客户端 pong，便于客户端记录端到端处理时间；不另加一组高频 OkHttp ping 定时器。WebSocket ping/pong 能证明传输活动，但业务 ACK 必须仍以 Room 提交为准。

服务端定时器不会唤醒被系统禁止运行的客户端；360 秒也不是深度睡眠下客户端保证执行的精确时钟。恢复运行后按真实连接状态重连，不靠补发大量心跳追赶时间。

## 6. 身份与最小服务端

不新增完整账号系统。V1 由可信服务端管理入口/CLI 签发专用接收凭据，客户端手动导入；不是把发布密钥放进 APK。服务端维护接收凭据哈希与撤销状态，客户端使用 Android Keystore 保护加密后的凭据。

每个接收绑定确定一台安装实例、一个服务端和一个不可变主体。企业消息必须与现有有效 RealmAccess 对应；个人消息只能进入明确的个人范围。服务端字段不能自行授予客户端企业访问权限。本地示例 enrollment 不能当作真实远程服务授权。

V1 建议在现有业务服务中加入一个 notification 模块，不先拆微服务。已有业务库增加两张表即可（每个 receiver 独立流）：

```text
notification_receiver
  receiver_id PK
  principal_id
  token_hash
  revoked_at
  stream_id
  next_seq
  last_stored_ack

notification_message
  receiver_id
  seq
  event_id
  kind
  title
  body
  resource_type
  resource_id
  created_at
  notify_until
  PRIMARY KEY(receiver_id, seq)
  UNIQUE(receiver_id, event_id)
```

同用户多设备由服务端向各 receiver 登记小事件；每个设备独立确认，A 设备的 ACK 不能导致 B 设备丢失未读流。V1 每台客户端仍只启用一个接收绑定。

写入规则：在事务中锁定/串行更新 receiver 顺序，检查 event_id 幂等，分配 seq、写消息并提交；同一业务数据库内与业务终态事务一起完成。不要把“分配的自增 ID”无条件视作跨并发事务的提交顺序。对于同事件重试，应返回已有记录而不是再次递增插入。

这张消息表同时承担可重放发送队列，不额外再堆一套 outbox 与 broker。若真实业务跨多个独立服务/数据库，才需要在产生事件的一侧增加最小本地 outbox；不能用一个无持久化 HTTP 调用宣称跨服务原子一致。

发布权限与接收权限分离。客户端不能调用任意收件人的发布接口。自测接口只生成发给当前绑定的小型测试事件，受限流保护。

## 7. 协议 V1

端点建议：

```text
WSS /api/notification-stream/v1
POST /api/notifications/test    # 仅当前接收绑定的自测
```

收到业务消息后的其他数据读取，继续使用现有业务 API，不在通知通道搬运模型输出或完整会话。

握手通过 `Authorization: Bearer <receiver-token>`，不把凭据放进 URL/普通日志。

握手后客户端发送：

```json
{"type":"resume","v":1,"stream_id":"stream_abc","after_seq":"41"}
```

服务端在认证主体匹配后返回 ready，随后从数据库依序回放 seq > 41，再继续直播。seq 使用十进制字符串表示 64 位序号，避免多语言数字精度歧义。

示例事件：

```json
{
  "type":"notification",
  "v":1,
  "stream_id":"stream_abc",
  "seq":"42",
  "event_id":"evt_abcdef",
  "kind":"task.completed",
  "title":"任务已完成",
  "body":"结果已准备好，点击查看。",
  "resource":{"type":"task","id":"task_123"},
  "created_at":"2026-09-18T08:00:00Z",
  "notify_until":"2026-09-19T08:00:00Z"
}
```

客户端完成 Room 事务后：

```json
{"type":"ack","stream_id":"stream_abc","stored_seq":"42"}
```

ACK 只表示消息及 cursor 已持久提交，不表示系统展示、用户点击或已读。服务端断线重发安全，不要求网络传输 exactly-once。

客户端恢复时以自己的持久 cursor 请求，不直接采用服务端记录的 ACK 覆盖本地状态；服务端 ACK 用于运行观察与窗口控制，不用于立即删除离线补发记录。被恢复/损坏的旧客户端 cursor 较小，保留期内允许重放；未知 stream 或 cursor 越界必须明确报错。

回放/直播不能有空隙：服务端注册唤醒观察后查询数据库；所有发送都来自 `seq > cursor` 的数据库读取。内存通知仅用于促使查询，不是唯一事实。heartbeat 时也检查数据库 head。慢客户端不堆无限内存，断开后续传。

重大协议版本不兼容、凭据撤销、流变化、历史过期有明确类型：`UPGRADE_REQUIRED`、`AUTH_REVOKED`、`STREAM_RESET`、`HISTORY_GAP`。客户端不能收到这些错误后擅自把 cursor 跳到最新并宣称同步完整。

同版本新增未知 kind：保存受限的原始字段，显示通用安全通知或收件箱条目；不得因为一个暂时未知业务类型永久阻塞后续事件。严重非法/超限载荷由服务端拒绝；客户端记录诊断并隔离处理，不能悄悄漏掉却发送已完整持久化的 ACK。

## 8. Room 持久化与系统展示

客户端增加两张表：

```text
remote_notification_state
  binding_id PK
  endpoint
  principal_scope
  authorization_binding
  encrypted_receiver_credential
  stream_id
  stored_seq
  enabled

remote_notification
  binding_id
  stream_id
  seq
  event_id
  kind/title/body/resource
  created_at/notify_until
  display_state       # PENDING / SUBMITTED / SUPPRESSED
  display_reason
  read_at
  PRIMARY KEY(binding_id, stream_id, event_id)
  UNIQUE(binding_id, stream_id, seq)
```

enabled 的事实只保存在一处；设置页通过 Controller/Repository 修改，不再在 SettingsStore 另存一份同义开关。诊断中的 Connected/WaitingNetwork 只在 Controller 内存 StateFlow 派生，重启不能直接恢复成在线。

接收过程必须串行：

```text
校验连接代际、绑定、主体和流
    → 校验事件和顺序
    → Room 事务：幂等写消息 + 更新最高连续 stored_seq
    → commit
    → ACK
    → 按 display_state= PENDING 发布通知
    → 持久记录提交/抑制状态
```

磁盘满、迁移失败、权限失效时，不推进 cursor、不发送成功 ACK。相同 seq/event_id 的内容若冲突，报告协议错误，而非覆盖已提交记录。

通知展示与 Room 无法做一个原子事务，采用稳定 `(tag,id)` 和 pending 修复：

```text
notificationTag = "remote:<binding_id>:<stream_id>:<event_id>"
notificationId = 1
```

配合 onlyAlertOnce 降低更新重响。若在 notify 后、SUBMITTED 写回前进程崩溃，恢复时可能再次提交；如果用户恰好已划掉通知，不能绝对保证不再出现。可用系统当前活动通知帮助减小窗口，但不能据此承诺严格 exactly-once 展示。

通知接口命名采用 `Submitted`、`Suppressed(reason)`、`Failed(reason)`，不能命名成 `DeliveredToUser`。检查 Android 13+ POST_NOTIFICATIONS、应用全局通知开关与频道状态；提交成功也不代表用户看见。用户关闭权限/频道时消息照常入箱，记录抑制，不在后来授权时把所有旧消息重新鸣响。

新频道只需两个：`remote_connection`（LOW）与 `remote_events`（DEFAULT，由用户调整）。现有聊天频道保留。持续通知提供停止/诊断入口，不借机展示聊天或执行进度。非必要不强制响铃、不绕过勿扰、不请求全屏或通知监听权限。

正常新消息及时提醒；积压回放在短窗口内合并成一条摘要，完整条目保留在消息中心。提醒有效期默认 24 小时（业务可缩短）；服务端历史初始保留 30 天。超过保留期返回 HISTORY_GAP，并提供明确缺口说明，不能冒称消息从未丢失。

## 9. 点击、会话隔离和退出

PendingIntent 直接指向 RouteActivity，immutable，使用稳定唯一 data URI/事件键区分，避免 requestCode/hash 碰撞；不通过 Service/Receiver 先做业务再转 Activity。

冷启动与 onNewIntent 都进入统一通知打开处理，等待导航和 ApplicationRecoveryGate 准备好；navStack 未建立时保留待处理请求，不静默丢弃。根据本地存储 notification_id 找到其原 binding/主体，再经现有权限判断后显示。

远程 task_id/conversation_id 不一定是本地 Conversation UUID。V1 先打开本地消息中心/通知详情；只有存在经过验证的映射，才打开现有 Chat，否则显示详情或调用正规业务页面。没有登录到正确主体时提示，不自动切换域或创建会话。

企业退出应加入 `closeBindingAndAwait` 及按 binding tag 取消通知，复用 EnterpriseExitService 的收口。原会话进入 closing/授权失效后，旧 socket 回调即使迟到也无权写入或展示。原 RealmAccess 和连接 epoch 每次处理重校验；注销不是等待网络注销成功才在本地撤权。

UI 从企业切换到个人，不等同企业会话注销。按现有权限模型决定是否继续接收，不能每次使用“当前选中的域”重新标记消息归属。

普通退出保留本地历史但不可越权读取；明确删除企业数据时才删除其收件箱。服务器推送内容默认为简短非敏感摘要，不携带访问令牌、业务秘密或任意执行参数。

## 10. 备份与恢复

当前应用自建备份，不应因为 manifest allowBackup=false 就忽略此项。

明确规定 remote_notification_state、凭据和游标不进入可迁移的用户备份；V1 也不把远程通知收件箱作为聊天备份资产导出，避免历史授权和设备接收状态混杂。

现有 BackupDataGraph 是显式复制表并重建数据库，因此新增表需要决定两个方向：

- 导出个人备份：两表保持空，不导出设备接收身份。
- 同设备恢复个人聊天备份：从最新实时数据库保留两表，不从旧备份读取；保留现有仍合法的设备绑定，不把 cursor 回滚。

新设备/重装无旧状态时重新签发接收绑定；不能把两台设备都恢复成同一 receiver 身份。整个流程加备份导出、同机合并恢复、新机恢复测试。密钥仍留在系统 Keystore，不导出。

## 11. 部署配置

服务端模块与业务服务同进程、同数据库先运行。沿用已有启动与日志管理方式，不因该功能引入新的编排系统。

公网提供一个国内和国外实际可达的 TLS 443 域名；手机仅主动连接服务器，无手机入站端口。国内 Wi-Fi/移动网、国际网络分别验收，不能以服务器位置或 TCP 端口代替可达性测试。

采用 Nginx 时明确转发 WebSocket Upgrade/Connection，建议显式 HTTP/1.1；该路径 proxy_read_timeout/proxy_send_timeout 初始 600s，服务端 heartbeat 120s。Nginx 默认无上游数据 60s 会关闭连接，不能保留默认超时同时使用 120s 心跳。

服务器健康检查应包含数据库可写状态和接收连接数；定期一致性备份。不得通过日志输出 Authorization、接收 token 或完整消息正文。服务端重启保留消息库和 stream 身份；恢复旧数据库时做明确 STREAM_RESET/缺口处理。

API 37 的局域网权限：现有项目 target37，仅声明 ACCESS_LOCAL_NETWORK 不等于获得运行时授权。访问家中/Spark 上的 LAN 端点需要相应授权；正常公网端点不额外索要局域网权限。

## 12. 状态诊断

设置页只需：持续接收开关、服务端与主体摘要、连接状态、最后 heartbeat/事件时间、系统通知权限/频道状态、电池优化状态、一键测试、停止。

区分：未启用、恢复中、等待网络、连接中、已连接、重试中、凭据失效、协议需升级、持久化失败、系统启动受限。连接正常但通知被禁止时要同时显示两项事实，不能用一个绿色“正常”概括。

记录少量结构化诊断：event_id/seq、server_created_at、client_stored_at、notification_submitted_at、connection_reason。跨设备墙钟可能不同，服务端收到 ACK 的往返时间可作独立指标；不把未校时的时间差直接当精确端到端延迟。

## 13. 必须通过的测试

### 自动化

1. 同 event_id 重复发送、ACK 丢失、服务端重启：本地一条记录，正常路径不重复提醒。
2. 客户端提交前崩溃、提交后 ACK 前崩溃、ACK 后 notify 前崩溃：均有补发或 pending 修复。
3. notify 后状态写回前崩溃：验证幂等 tag 和可接受的展示不确定窗口。
4. 回放结束瞬间新增消息：不能丢在历史/直播切换缝隙。
5. 网络快速切换、旧 socket 迟到回调、新旧重试同时触发：至多一个有效代际。
6. 授权过期、退出、相同账号重新绑定：旧代际不能入库/发通知。
7. 错误/过期凭据不无限高频重试；日志无秘密。
8. 通知权限/频道关闭、未知业务 kind、超限载荷、序号缺口、流重置分别覆盖。
9. 磁盘满或 Room 事务失败：不 ACK，不推进 cursor，诊断可见。
10. 12→13 additive migration 与新版本 schema 导出；旧聊天/业务测试回归。
11. 冷/热启动点击、恢复未就绪、错误主体、无本地会话映射：不丢路由、不越权、不崩溃。
12. 备份不复制 receiver；同机恢复不回退 cursor；新机恢复不自动开始接收。

### 真机

至少覆盖一个 AOSP/国际设备和准备正式支持的国内 ROM；原生鸿蒙设备不在本 Android APK 方案范围。

在 API 26/33/35/37 的可用设备/模拟器做平台覆盖，国内 OEM 行为必须真机。每个计划支持的 ROM 至少完成两轮 8 小时熄屏、不充电、无调试器会话；分别测有/无电池优化豁免。

对比普通返回后台、最近任务划掉、普通进程回收、系统 Active apps 停止、Force stop；不能把这几种操作统称为“杀后台”。同时测试重启首次解锁后、APK 覆盖升级、Wi-Fi↔蜂窝、无网络恢复、门户 Wi-Fi、通知权限关闭。

实验性指标：在线且允许运行的测试环境中，服务端登记到客户端持久 ACK 的 p95 目标小于 3 秒；测试消息在保留期内恢复后的条目完整率 100%；正常重投不重复入箱；不以崩溃边界中的展示不确定性冒充 exactly-once。电量按同一手机接收开/关 A/B 测量，未实测前不宣传固定每小时耗电。

无白名单/厂商限制的失败应正确进入有限能力状态并补偿，不要求用违规拉活把它变成假成功。

## 14. 实施次序与交付边界

1. 先实现真实 Service + WSS + 系统通知的纵向最小样例，在目标手机熄屏/Doze 条件下验证能否满足运行前提；不先写通用推送框架。
2. 加服务端消息事务、Room 两表、seq/ACK/replay、pending 展示修复与故障测试。
3. 接入企业退出、应用恢复门禁、冷/热点击、备份合并与诊断设置页。
4. 完成目标 ROM 长时测试，只有有证据的组合才标为支持；其余明确显示限制。

不改写 ConversationRuntime、Turn/Tool 执行、MCP 生命周期和模型缓存逻辑。整体是一个局部通知接收功能，不是一次全应用架构重构。

## 15. 官方研究来源

- Android Doze 与 App Standby：https://developer.android.com/training/monitoring-device-state/doze-standby
- 前台服务类型与 specialUse：https://developer.android.com/develop/background-work/services/fgs/service-types
- dataSync 等类型时限：https://developer.android.com/develop/background-work/services/fgs/timeout
- 后台启动 FGS 限制：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Service 与 START_STICKY：https://developer.android.com/reference/android/app/Service
- 用户停止前台服务应用：https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- Force stop 状态：https://developer.android.com/about/versions/15/behavior-changes-all
- Wake lock 最佳实践：https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices
- Android 17 target 行为与 LAN 权限：https://developer.android.com/about/versions/17/behavior-changes-17
- Android 网络状态：https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- 通知权限：https://developer.android.com/develop/ui/compose/notifications/notification-permission
- 通知频道：https://developer.android.com/develop/ui/compose/notifications/channels
- ntfy Android 自主接收模式（实践模式参考，不作为 OEM 必达证明）：https://docs.ntfy.sh/subscribe/phone/
- Nginx WebSocket 反向代理：https://nginx.org/en/docs/http/websocket.html

仓库代码的全部事实绑定上述 commit，代码入口：
https://github.com/topabomb/rikkahub_mcp/tree/5de1b084516798ee8eda971ff3340c5608dc95cc

各文件完整路径可由第 2 节路径加在该 commit 的 blob 路径后定位。
