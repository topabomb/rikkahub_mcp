# 原生企业接入资料

当前接入只有 Core 平台来源。实现入口为 `EnrollmentMaterialParser`、`EnterpriseApplicationService`、`PlatformEnterpriseService` 与 `EnterpriseSessionController`；协议权威是平台架构仓库的 Control Protocol §8。

## 格式与信任

Enrollment material 使用 `formatVersion=1`、`kind=PLATFORM_ENROLLMENT`，必填 `platformUrl`、`code`、`expiresAt`，拒绝未知字段、重复解码键、null、未知版本/kind 和非法时间。原始 UTF-8 输入最多 2048 字节；code 为 1–128 个 Unicode 字符。`expiresAt` 接受当前 RFC3339 UTC 子集并按 `Instant` 判断到期。

`platformUrl` 必须是规范化的 HTTP/HTTPS origin，可使用域名、局域网 IP、IPv6 和显式端口；拒绝 userinfo、query、fragment、非根 path 与非法端口。扫码、相册和粘贴都进入同一解析器。解析成功只产生带规范化 origin 的 `EnterpriseJoinConfirmation`，用户确认后才执行 Discovery、Enrollment exchange、Bootstrap 与 Snapshot 同步；取消或被替换的确认不发网络请求。

Core 区分两个 409：一次性码已使用为 `enrollment_already_used`；本机 installation 已绑定另一用户为 `installation_user_conflict`，且不消费新码。Android 必须显示稳定、可操作的诊断。换用户需要用户在“重置与数据处置”中重置企业连接，使 `EnterpriseAppliedStore.resetLocalState()` 删除本机 installation ID；仅撤销 Core 设备不会改变手机 installation 身份。

## Session 与恢复

`PlatformEnterpriseService` 编排网络 I/O，`EnterpriseSessionController` 是身份、pending enrollment 和 Applied 状态的串行写 owner。兑换成功后先保存 pending；Bootstrap 核对 Session/User/Device/Deployment 才发布企业身份。临时网络失败保留 pending 并在应用恢复后继续 Bootstrap，不重复兑换一次性 code。

installation ID 在一次本机企业连接生命周期内稳定，不能为绕过跨用户限制而自动更换。refresh credential 与 pending idempotency key 由 `EnterpriseAppliedStore` 加密、原子持久化；access token 只在内存。退出或 reset 的具体边界见 [Android 配置架构](android-configuration-architecture.md)。

## 契约同步与验证

Core `api/generated/android/portal/` 是唯一消费输入，Android 在 `app/src/test/resources/contracts/portal/` 固定其 manifest 和当前六份 artifact：Portal contract、Client Feed schema、native/feed vectors、platform enrollment fixture 与 enrollment cases。普通 Android 构建不依赖 sibling checkout；消费副本不得独立演进。

`EnrollmentMaterialParserTest` 验证严格字段、重复键、UTF-8/字符限制、UTC 与 origin；`EnrollmentSharedCasesTest` 直接消费 Core 共享 raw cases；`PlatformSessionNetworkTest` 使用真实本机 HTTP 验证兑换、Bootstrap 恢复、刷新与撤销。二维码库 round-trip、JVM 和本机 HTTP 都不能替代真实 Core + Android 相机/相册/发行版设备验收。
