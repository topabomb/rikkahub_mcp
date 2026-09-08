# 原生企业接入资料

当前实现入口为 EnrollmentMaterialParser、LocalEnterpriseSource、LocalEnrollmentAuthority 和 EnterpriseSessionController.enrollLocal。格式权威是平台架构仓库的 Control Protocol §8、Portal 产品要求 §3 和 ERX-JOIN-001/002；本次消费的是未 Freeze 工作树修订，不是生产互操作认证。

## 格式与来源

formatVersion=1；kind 只允许 PLATFORM_ENROLLMENT 或 LOCAL_EXAMPLE_ENROLLMENT。共同字段 code、expiresAt 必填；平台另需 platformUrl，本地另需 sourceNamespace/deploymentId。所有列出的字段必填，无额外字段。本地身份由已安装来源及票据验证确定，资料没有 userId。完整企业配置文件的 identity/runtimeBindings 与接入资料独立；完整文件当前使用 formatVersion=2，不能送入接入资料解析器。

解析器先限制原始 UTF-8 为 2048 字节（包含首尾空白），再去掉首尾空白；平面对象读取在建立字段 map 前拒绝重复的解码键。错误类型、未知字段/版本/kind、缺失/null、非法 UTC 时间均拒绝，错误不带原文或底层异常。三个本地标识和平台 code 按 Unicode 字符限制为 1–128；expiresAt 采用 RFC3339 UTC，内部 Instant 比较到期。

平台只接受 HTTPS origin，拒绝 userinfo、query、fragment、非根 path、非法端口；支持显式 loopback HTTP 测试策略，正式 LocalEnterpriseSource 未开启。本期平台资料返回 platform_enrollment_not_supported，无网络客户端或来源注册路径。真实接入时的目标 origin 展示、Discovery/Enrollment 同源与重定向控制仍待网络接入实现，不能用纯 URL 验证测试替代。

一键示例先领取短期随机 code，再调用与粘贴/扫码解码结果相同的 enroll。LocalEnrollmentAuthority 是模拟服务票据 owner，保存摘要、可信身份、到期与消费；不保存明文 code，不管理客户端 Session。票据读写使用独立私有 AtomicFile 与互斥锁，损坏不视为空账本，成功消费不因客户端失败撤销。Session owner 串行执行冲突检查、兑换、一次客户端发布，固定锁序 Session → Local authority；配置缺失或校验错误仅在身份验证成功后发布待配置状态。

PrepareEnterpriseExampleAssets 从同一公开模板派生 enterprise.local.identity.json，首次初始化本地安装目录。原生文件导入可显式安装其他来源和主体，换主体必须先退出；短接入资料不能安装来源。目录独立保存可信身份与来源版本，配置不可读不会丢失身份；LocalEnrollmentAuthority.resolveIdentity 只读验证票据并取得其固定用户，Session 冲突检查之后由 redeem 再次验证并消费。相同 source/deployment 的多个用户不会按当前选中用户猜测身份。重新接入读取来源当前候选；没有旧 asset 与较新 Applied 的回退路径。配置缺失/损坏可进入 pending，未知身份仍拒绝。

## 平台样例与 Android 消费副本

平台消费导出目录：measix-platform-core/api/generated/android/portal。Android 原样固定该目录的 manifest 和八份输入，接入解析直接消费其中的 platform-v1.json、local-v1.json 和 cases.json；所有输入的来源和摘要统一记录于 [manifest.json](../../app/src/test/resources/contracts/portal/manifest.json)。普通 Android 构建不依赖 sibling checkout。

这些文件只用于离线契约测试，不能独立演进为第二份 canonical fixture。同步时先验证导出 manifest，再原样复制；Git 对该目录 JSON 禁用换行转换，测试验证消费副本 SHA256。平台正例摘要为 `3ac391412640d571dd03220fbfd88487e1d1e47a6b1d6f8a86ee09ae821d340a`。

EnrollmentSharedCasesTest 直接使用 cases.json 的原始 raw、固定 now 和 installedSources。invalid 由真实解析器拒绝；expired 和 unknown_source 分别调用生产到期预检查与安装来源校验。本地正例只验证格式和来源字段，不把共享测试 code 当作已安装 Android 来源的有效凭据。

Android 的代码内本地反例只证明 Android 当前行为，不算平台共享 fixture 或平台验收已完成。

## 验证边界

PortalProtocolTest 统一核验 manifest 中全部消费输入的摘要与协议版本。EnrollmentMaterialParserTest 消费两类接入正例，测试严格字段、原始重复键、UTF-8/字符限制、UTC 与 origin。时间接受小写 t/z 与 +00:00；小数秒只能为 1–9 位，拒绝超精度、闰秒、-00:00、其他偏移和错误日期，不截断输入。输出统一大写 T/Z。LocalEnterpriseSourceTest 覆盖一键、粘贴、二维码库编码/解码、来源分流、并发/重开后的消费、到期权威、持久化失败、冲突不消费、配置待就绪与独立文件导入。

二维码库 round-trip 不是 Android 相机扫码设备验收。正式扫码/粘贴 UI、页面错误呈现、真实 Discovery/重定向/Enrollment 与平台互操作尚未交付，验收须分别报告。
