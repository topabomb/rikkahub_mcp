# 原生企业接入资料

当前实现入口为 EnrollmentMaterialParser、EnterpriseApplicationService、PlatformEnterpriseService、LocalEnterpriseSource 和 EnterpriseSessionController。格式权威是平台架构仓库的 Control Protocol §8、Portal 产品要求 §3 和 ERX-JOIN-001/002；本次消费的是未 Freeze 工作树修订，不是生产互操作认证。

## 格式与来源

formatVersion=1；kind 只允许 PLATFORM_ENROLLMENT 或 LOCAL_EXAMPLE_ENROLLMENT。共同字段 code、expiresAt 必填；平台另需 platformUrl，本地另需 sourceNamespace/deploymentId。所有列出的字段必填，无额外字段。本地身份由已安装来源及票据验证确定，资料没有 userId。完整企业配置文件的 identity/runtimeBindings 与接入资料独立；完整文件当前使用 formatVersion=2，不能送入接入资料解析器。

解析器先限制原始 UTF-8 为 2048 字节（包含首尾空白），再去掉首尾空白；平面对象读取在建立字段 map 前拒绝重复的解码键。错误类型、未知字段/版本/kind、缺失/null、非法 UTC 时间均拒绝，错误不带原文或底层异常。三个本地标识和平台 code 按 Unicode 字符限制为 1–128；expiresAt 采用 RFC3339 UTC，内部 Instant 比较到期。

平台正式接受 HTTP/HTTPS origin，包括域名、局域网/IP、IPv6 与显式端口；拒绝 userinfo、query、fragment、非根 path、非法端口。不存在 loopback 或 Debug 专用开关。扫码与粘贴通过 EnterpriseApplicationService.join 分派来源；平台资料先产生仅含规范化 origin 与命令 ID 的 EnterpriseJoinConfirmation，不发网络请求。确认后才 Discovery/兑换/Bootstrap/同步；取消或被替换的确认不能接入。HTTP 和 HTTPS 共用一处确认，不增加证书或主机白名单。

一键示例先领取短期随机 code，再调用与粘贴/扫码解码结果相同的 enroll。LocalEnrollmentAuthority 是模拟服务票据 owner，保存摘要、可信身份、到期与消费；不保存明文 code，不管理客户端 Session。票据读写使用独立私有 AtomicFile 与互斥锁，损坏不视为空账本，成功消费不因客户端失败撤销。Session owner 串行执行冲突检查、兑换、一次客户端发布，固定锁序 Session → Local authority；配置缺失或校验错误仅在身份验证成功后发布待配置状态。

PrepareEnterpriseExampleAssets 从同一公开模板派生 enterprise.local.identity.json，首次初始化本地安装目录。原生文件导入可显式安装其他来源和主体，换主体必须先退出；短接入资料不能安装来源。目录独立保存可信身份与来源版本，配置不可读不会丢失身份；LocalEnrollmentAuthority.resolveIdentity 只读验证票据并取得其固定用户，Session 冲突检查之后由 redeem 再次验证并消费。相同 source/deployment 的多个用户不会按当前选中用户猜测身份。重新接入读取来源当前候选；没有旧 asset 与较新 Applied 的回退路径。配置缺失/损坏可进入 pending，未知身份仍拒绝。

## 平台样例与 Android 消费副本

平台消费导出目录：measix-platform-core/api/generated/android/portal。Android 原样固定该目录的 manifest 和八份输入，接入解析直接消费其中的 platform-v1.json、local-v1.json 和 cases.json；所有输入的来源和摘要统一记录于 [manifest.json](../../app/src/test/resources/contracts/portal/manifest.json)。普通 Android 构建不依赖 sibling checkout。

这些文件只用于离线契约测试，不能独立演进为第二份 canonical fixture。同步时先验证导出 manifest，再原样复制；Git 对该目录 JSON 禁用换行转换，测试验证消费副本 SHA256。平台正例摘要为 `3ac391412640d571dd03220fbfd88487e1d1e47a6b1d6f8a86ee09ae821d340a`。

EnrollmentSharedCasesTest 直接使用 cases.json 的原始 raw、固定 now 和 installedSources。invalid 由真实解析器拒绝；expired 和 unknown_source 分别调用生产到期预检查与安装来源校验。本地正例只验证格式和来源字段，不把共享测试 code 当作已安装 Android 来源的有效凭据。

Android 的代码内本地反例只证明 Android 当前行为，不算平台共享 fixture 或平台验收已完成。

## 验证边界

`PlatformWire` 由固定的 Core Client OpenAPI 生成；`PlatformWireCodec` 复用 StrictJsonValue 拒绝重复键，并按 serializer descriptor 拒绝 null、未知字段与字符串冒充布尔/数值。字段类型、必填项、枚举和基本约束来自 schema；跨资源引用与语音条件字段仍需 Snapshot 语义校验。生成入口为 `python tools/generate-enterprise-wire.py`，要求 PyYAML 6；`--check` 校验生成结果。普通构建只使用已提交 Kotlin，不读取相邻仓库。

`PlatformControlClient` 提供当前 Control HTTP 传输，禁自动重试和重定向；兑换、refresh、已应用回执及注销的请求正文使用 `withSingleAttemptBody`，避免 408/503 follow-up 重发，沿现有 `Call.readResponse` 传播取消并关闭响应。`PlatformConnection` 保存规范化 HTTP/HTTPS origin 和 Discovery，以 origin 摘要加服务端 deploymentId 确定 authority；API base/runtimePath 不得替换来源或穿越路径。快照响应校验部署/generation、带 `sha256:` 前缀的 hash 与完整 quoted ETag；304 只表示服务端未修改，调用方仍须持有原主体已验证缓存。认证状态和快照发布不归网络 client。

`PlatformEnterpriseService` 编排 Discovery、单次兑换和 Bootstrap；网络 I/O 不持 Session 写锁。`EnterpriseSessionController` 保存成功兑换后的 pendingEnrollment，Bootstrap 核对 Session/User/Device/Deployment 后才发布身份和待配置状态。Bootstrap 请求有 15 秒整体时限；临时失败从已保存的 Session 恢复，不重复兑换一次性 code；已确认的新资料遇到原 pending 的 Core `session_expired`/`session_revoked`/`invalid_credential` 或本地 Session/refresh 到期时，由 Session owner 清除终态 pending，才兑换新 code。已确认切换到不同 origin 时，先用当前 refresh 凭据向旧 Core 发起有界注销；原 Core 离线也会清除本地 pending 并继续新接入，远端结果未知通过现有反馈显示“远端注销未确认”。同 origin 的网络暂时失败仍保留 pending，不消费新 code。`EnterpriseApplicationService` 在应用恢复门禁打开后自动继续已保存的 Bootstrap，并同步已发布的平台 Session；若 Snapshot 已提交但已应用回执失败或进程中断，重启会再次提交幂等回执。网络失败只显示原诊断，不阻断个人空间恢复，用户仍可重试接入或同步。installationId 独立持久化，换用户不能改写它绕过平台限制。

refresh 凭据与待恢复的 Idempotency-Key 由 `EnterpriseAppliedStore` 写入不可变加密版本，manifest 原子选择当前版本；提交前和读取时校验摘要、解密及 Session 归属。`EnterpriseCredentialCipher` 使用 AndroidKeyStore AES-GCM，revision 绑定为 AAD，无明文降级。access token 只保留内存；重启通过同一 refresh 协议取得新的 access。网络编排串行刷新，先持久化旧 token 与本次 key，再发有 15 秒整体时限的请求，成功后提交新版本；未知结果沿用原 token/key，不能开启另一次轮换。只有无副作用 Client 读取遇到 401 invalid_credential 时允许刷新并重试一次；其他错误保留原诊断，Runtime 不使用该重试入口。

用户退出先由 `EnterpriseExitService` 和 Session owner 发布 CLOSING、停止原域工作；`PlatformEnterpriseService.logout` 在刷新互斥锁内读取 CLOSING 凭据。若刷新仍 pending，先以原 key 恢复轮换，再用确认的新 refresh token 请求 Core logout；不使用不确定的旧 token 报告成功。远端请求有界；即使网络失败，本机仍完成退出，并通过现有反馈显示远端注销未确认及原诊断。已发布的 Session 在 Core Client 读取或刷新收到 `403 session_revoked` 时，PlatformEnterpriseService 通知应用作用域的既有 `EnterpriseExitService.invalidate`，最终进入 REAUTH_REQUIRED；pending 接入的同一码交由接入恢复流程处理。

`PlatformAuthenticationTest` 覆盖凭据恢复、无明文落盘、主体匹配、旧刷新响应和密文篡改；`PlatformSessionNetworkTest` 使用真实本机 HTTP 验证 Bootstrap 临时失败后进程重建恢复、终态失败换新 code、跨 origin 离线旧 Core 的确认切换、已接入 Session 撤销通知、刷新失败后的原请求恢复及并发刷新合并。`PlatformCredentialAndroidTest` 使用真实 AndroidKeyStore 验证加密文件重读与 AAD 拒绝；`EnterprisePageAndroidTest.platformPasteShowsOriginBeforeConnectingAndCancelDoesNotEnroll` 验证确认呈现及取消/确认命令。后者替换 application service，不能替代真实 Core 用户功能验收。

PortalProtocolTest 统一核验 manifest 中全部消费输入的摘要与协议版本。EnrollmentMaterialParserTest 消费两类接入正例，测试严格字段、原始重复键、UTF-8/字符限制、UTC 与 origin。时间接受小写 t/z 与 +00:00；小数秒只能为 1–9 位，拒绝超精度、闰秒、-00:00、其他偏移和错误日期，不截断输入。输出统一大写 T/Z。LocalEnterpriseSourceTest 覆盖一键、粘贴、二维码库编码/解码、来源分流、并发/重开后的消费、到期权威、持久化失败、冲突不消费、配置待就绪与独立文件导入。

二维码库 round-trip 不是 Android 相机扫码设备验收。正式空间页已提供扫码、粘贴、来源确认和错误呈现，并将解码结果交给同一接入流程。真实 Core 部署与相机扫码的完整设备验收仍待完成，不能以本机 HTTP 组件测试或独立确认对话框测试代替。
