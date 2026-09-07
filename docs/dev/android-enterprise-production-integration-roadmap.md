# Android 真实企业服务接入规划

> 状态：后续规划，不是 0.0.20 的前置条件或验收标准。
> 当期唯一权威：[Android 企业域本期实施方案](android-enterprise-integration-plan.md)。本文只规定正式功能如何连接真实平台，不重复本期 UI、迁移和三类配置规则。

## 1. 替换范围

| 本期来源 | 后续来源 |
| --- | --- |
| 本地接入资料/验证 | Discovery/Enrollment、服务端 Device/User/Session |
| 本地企业配置/场景更新 | Managed State、版本化 Snapshot/Release/generation |
| 确定性企业服务/私有直连 binding | Runtime Relay、企业 Model/Speech/Direct MCP/Gateway |
| 本地动态/HTML | Feed 与批准 origin 的远端 Portal |
| 本地网页会话 | 服务端 grant/exchange、受限 Web Session |

不重建 Chat/Assistant/Turn/Settings/备份或 UI。用户资源仍用原用户凭据和连接；企业准入由真实 policy 提供。本地模拟身份不能升格真实授权，退出示例后重新接入真实企业；示例历史保持原 scope。

## 2. 合同前提

平台来源为 `D:/RustProject/measix/measix-architecture`。路线图/术语/生命周期、Runtime Foundation、S0 合同、Control Protocol/测试形成权威链。实际接入固定已提交 architecture/core commit、OpenAPI/DTO/fixture hash、profile 与 Freeze evidence，不使用 floating latest 或未提交草案宣称兼容。

当前架构修订提出 v4（Experience+五项准入）、v5（再加 Gateway）。旧 v1/v2/v3 的 allowLocal=true 不等于新用户原配置复用授权。正式接入必须明确 supported schemas、旧 Release/客户端升级、五项缺失值、canonical bytes/hash；本地资料 formatVersion 不冒充平台版本。

接入前固定：

- 五项准入与清单外配置规则，默认与兼容明确；
- Managed Model/TTS/HTTP-ASR/Direct MCP/Assistant/Seed/Starter/Gateway 的完整定义/引用/可变性；
- Enrollment、Refresh rotation/single-flight/idempotency/丢响应恢复、七天 rolling idle 与服务端时钟；
- 本机退出、远端撤销未确认、重新接入及数据保留边界；
- Portal origin/grant/Cookie/CSRF、媒体 bridge typed contract 和受限句柄；
- whole-state Snapshot、ETag/hash、generation barrier、不重放错误；
- 任何新增用户组/导出/保留策略进入正式合同，不能把本地示例决定冒充企业治理。

## 3. 认证、下发与 Runtime

扫码/粘贴共用原生解析器，经 secure platform origin 的 Discovery/Enrollment 建立服务端身份；installation 只是关联值。Refresh Credential 安全存储，Access Token 仅内存；pending refresh key 和轮换响应遵守正式原子持久化/恢复协议。只有 authenticated Refresh 续期，不后台 heartbeat 保活。

切域保留登录；网络失败不退出但不得绕过需权威验证的企业执行；Portal 过期不等于母 Session 失效。退出先收口本机授权/任务/网页，尝试远端撤销，失败明确报告，不以断网阻止本机退出。

新 Managed 顶层操作按正式 trigger 检查 Managed State，不用 TTL 跳过正确性；下载候选校验 source/deployment/schema/引用/profile/path/hash，整体替换 Applied State。用户定义/偏好不回写 Snapshot。无 active Release 不造 generation-0 快照，LKG 不越过撤销或新 generation。

Managed 只走平台 resource runtime path；上游秘密/route/内部地址不下发。私有本地直连 binding 不能进入正式 Managed wire。用户资源执行叠加企业准入，不发送企业 token 到个人 endpoint。

Direct MCP 自动启用只读、按助手绑定工具；Gateway 标准 tools/list 验证 pair/surfaceHash，toolRef 限当前 interaction/generation，不持久复用、不 Direct fallback。REQUIRED/可控偏好沿用本期语义。

每次执行捕获不可变域/主体/generation/资源输入。明确 managed_snapshot_required 且 forwarded=false 时终止、同步后等新用户动作；auth/network uncertainty 不自动重放。Speech/独立 MCP 同样有明确 owner/context。

## 4. 远端 Portal

替换本地 HTML source 为批准 origin，服务端短期 grant 建立网页会话，JS 不取得 Refresh Credential。沿正式 bridge 使用状态/关闭/刷新/退出/相机/麦克风；每次复核 origin、document、realm、user/session 与 OS 权限，导航/切域/退出/过期取消并丢弃迟到结果。

普通外链/模型生成 HTML 无企业能力。Feed revision 独立于配置 generation。网页不可用时原生状态、恢复和退出入口仍工作。媒体归原主体，不自动上传，不暴露宿主路径或无限文件系统。

## 5. 执行阶段与证据

| 阶段 | 工作 | 证据 |
| --- | --- | --- |
| P1 固定合同 | 上位文档/generated schema/fixture/兼容与本期 source 模型核对 | 无未解释冲突，版本明确 |
| P2 身份/下发 | 真实扫码接入、刷新、Snapshot、退出恢复 | 真机/服务端并发、丢响应、过期、撤销、重启 |
| P3 Runtime | Model/TTS/HTTP-ASR/Direct MCP/Gateway 经 Relay | profile 互操作、428/取消/未知结果、不重放、来源隔离 |
| P4 Portal | 远端 HTML/Web Session/Feed/手机能力 | 设备权限、Cookie/过期、媒体与文档生命周期 |
| P5 完整回归 | 个人/示例/真实企业与平台系统场景 | 同一冻结基线 Android/Hub/Relay/Gateway/Portal 证据 |

按平台有效 Entry/Freeze 推进，不倒置 S0.2 与 S0.3/S0.4 的依赖。Android 完整构建、instrumentation、真实场景分别验收，本地 Mock Green 不能抵扣真实系统验证。

User Sync、Experience Contribution、受管 Skill、Agent Space/Runtime/Fleet、组织权限和数据治理各属后续阶段，不提前创建无消费者 outbox、兼容 facade 或第二 Runtime。
