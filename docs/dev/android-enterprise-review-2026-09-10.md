# Android 企业域 0.0.20 实现复审

审查基线为 `da33b8cf2`。依据本期实施方案、真实接入 roadmap、当前参考文档和代码进行审查；UI 交互由独立子代理检查，数据/备份由另一独立子代理检查。真实平台 roadmap 不作为本期模拟交付的前置验收。

## 结论与修复

原“完整完成”结论遗漏了收藏隔离和 Memory Seed 实际消费，不能仅凭旧门禁通过维持该结论。整体架构的三类配置、Session/Applied owner、原会话 Turn 链和文件 owner 可以继续使用；本次修复沿这些边界完成，没有引入第二套存储或执行链。

| 问题 | 用户影响 | 修复和验证入口 |
| --- | --- | --- |
| 收藏仍使用无域旧链 | 企业标题/预览进入个人收藏；个人备份包含企业收藏但不包含企业会话，闭合图校验失败 | FavoriteService 的目录按完整主体查询；原会话 owner 在命令锁内提供 durable node；Adapter 显式保存 scope；删除、undo 和导航复验原页面/Session。FavoriteServiceTest、PersonalBackupGraphAndroidTest |
| 企业 Seed 没有 UI 和模型消费者 | 企业固定上下文虽成功导入，助手实际请求完全没有收到；用户也无法查看 | ResolvedConfiguration 按企业助手固定绑定解析；使用页只读展示；主/子 START 经原 Disclosure 提交完整 Seed。ConfigurationResolverTest、ConversationDisclosureSnapshotServiceTest |
| 执行和 MCP 目录等待期间授权到期 | 入锁前有效、取得配置后已过期，仍可进入外部请求回调；目录回执期间到期仍可激活 Ready | ModelExecutionService 在配置等待后复验原授权；ConfigurationQueryService 和企业 MCP 同样在等待后复验。目录激活前复验并沿原回执回滚。ModelExecutionServiceTest、McpServerRuntimeAuthorizationTest、McpCatalogLifecycleTest |
| 切入企业等待宿主关闭期间到期 | 原宿主清理结束后仍发布已过期企业选择 | 切域 owner 在发布前检查期限，到期沿原 CLOSING 收口。EnterpriseSessionControllerTest |
| 企业缺模型误导配置个人 API | 企业策略禁止个人 Provider 时，聊天仍提示填写个人 key | 企业使用原就绪原因和正式空间恢复入口；个人保留原配置引导 |
| 共享用户定义影响说明缺失 | 从企业相关页面编辑 Provider/助手/语音时，用户可能误以为只影响本域 | 原用户管理页补齐五语共享影响说明，不锁死个人管理、不复制企业版用户定义 |

收藏的取消、与节点删除并发、旧选择往返失效、读取期间到期、失败 undo 重试均有针对性测试。Undo 在写成功后消费 token，不覆盖后来创建的收藏。旧的无域 Repository/DAO 用户入口已删除；会话删除仍由原事务按唯一会话/节点 ID 清理投影。

Seed 与可变记忆分开：企业资源引用不是 memory_tool 的整数 ID，关闭可变记忆不删除 Seed。新 Disclosure 使用 format 2 的只读 section，企业基线之前已有的个人历史 format 1 按原形状读取，不重写旧 entry。Transition 测试验证旧格式到新格式只追加一次 baseline，后续相同内容不重复追加；同 Turn、审批和工具续轮不刷新前缀。未改变 Room schema、Settings 文档结构或版本号。

## 审查范围

- 配置与执行：五项用户准入、引用失效、企业固定字段、辅助模型、主/子捕获、MCP/Gateway、Speech 和 source generation 边界。
- 生命周期：接入、同步、切域、到期、退出与启动恢复；Portal 原文档、媒体和关闭所有权。
- 数据：会话/记忆/收藏、文件引用、个人备份冷合并、清除示例数据、共享 Workspace 与 /upload 输入。
- UI：空间入口、配置/动态场景、助手本域使用与共享定义、模型/语音/MCP 目录、记忆、收藏、Portal 权限和恢复路径。

代码审查没有确认其他同等级问题。该结论不等同于穷尽所有组合的实机交互验收。

## 验证记录

定向 JVM 106 项通过、无失败或跳过；两个到期反例在修复前均失败，修复后通过。日志及 XML 归档位于 `build/reports/enterprise/review-20260910/`。

完整串行门禁通过（9 分 54 秒）：

```text
gradlew.bat test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest --no-parallel --max-workers=1
```

- JVM：全部模块 2,691 项，0 失败，11 项 Windows 条件跳过；App 2,194 项。Debug/Release 构建、R8 和 lint 均通过。
- Android 17 x86_64 / Pixel_10_Pro_Fold：App 207 项、Speech 14 项、Workspace 报告 12 项（1 项硬链接条件跳过），无失败。
- 门禁发现并修复两处旧测试假设：Windows file URI 夹具改用合法 URI，并断言确实解析到待拒绝文件；Room Disclosure 测试使用当前格式，同时实际落盘并读取历史 format 1 原文。
- 本轮 Release APK 升级安装后，正式助手使用页验证只读 Seed、关闭可变记忆后 Seed 仍显示；提供商页验证共享影响说明；实际企业消息加入收藏后，在企业列表可见，切回个人列表为空；核查结束后通过正式滑动删除交互移除本次新增收藏，并恢复企业空间。
- 本轮没有人工重跑全部语言、屏幕尺寸、无模型企业配置及既有 Portal 交互组合；这些区别于代码审查和自动设备门禁覆盖。

最终日志为 `build/reports/enterprise/review-20260910/full-gate-final.log`；分模块 XML、Release UI 截图和层次 XML、APK SHA256 与本轮元信息保存在同目录。改动未提交，未修改版本、changelog 或持久化 schema。

本次不代表实体手机、真实平台、用户私有服务或 OEM 系统迁移验收；这些边界仍按原 roadmap 独立处理。

## 最终差异复审后的修复

复审以 `c2745a1ddbfb607dd3577020f4fab95afe21b467` 到当前工作区的完整差异为范围。前一轮通过的门禁没有覆盖全部消费者组合，不能据此认定实施完整。新增发现已沿既有 owner 修复：

- Provider 的完整响应消费统一使用 `Call.readResponse`，取消覆盖响应头和阻塞读体，包含非流式模型、图片生成/编辑/下载及同类目录、余额、embedding 和凭据交换；不添加退出超时补丁。
- 建议生成读取原域的 `ResolvedConfiguration.selections.enableSuggestion`，共享提示词仍归用户定义。
- 系统分享直接使用现有助手目录及其原 `RealmSelection`，显示本域助手和禁用原因，只预填 Draft，不自动发送或修改默认助手。
- Settings 域偏好写入必须显式提供提交校验，包括资源选择、收藏、建议、Gateway 和默认助手。Memory 事务内写前/写后复验；UI 编辑及工具卡片删除使用原选择和页面，执行工具继续使用原 Session。

独立复审进一步收紧了延迟订阅时的原页面版本，以及工具卡片删除的原页面授权。没有新增 resolver、持久化表、企业迁移或旁路。企业功能未发布：Package、manifest、Bridge 和接入资料各自只接受一个当前固定版本；不同载体的编号不代表支持多个企业历史版本。Disclosure format 1 是企业基线之前 `v0.0.19` 中已有的个人会话数据格式，其读取保全不作为旧企业发布协议兼容。

回归覆盖真实 HTTP 响应体读取期间取消、企业与个人建议开关相反、旧分享目录、Settings 等待中到期、记忆旧编辑器/延迟订阅、原工具卡片，以及实际 Room 写入后失去授权的事务回滚。最后一项需要设备测试，不用 JVM 通过代替。

Release 分享入口已在 Android 17 模拟器实测：企业空间显示企业助手，选择企业工作助手后停留在本域新聊天，分享文本仅预填输入框，没有自动发送。核查后清空测试文本。当前示例策略允许用户助手，因此本次人工场景未覆盖禁用助手展示；旧选择拒绝由定向测试覆盖。截图、UI 层次及安装包摘要归档在 `build/reports/enterprise/final-fixes-20260910/`。

最终源码完整串行门禁通过（19 分 59 秒），命令仍为上述 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest`。JVM 共 2,699 项、0 失败、11 项 Windows 条件跳过；App 2,200 项。最终设备 XML：App 208 项、Speech 14 项、Workspace 12 项，均无失败，Workspace 有 1 项硬链接条件跳过。Debug/Release、R8 与 lint 均通过。人工分享核查的 Release APK SHA256 与最终构建一致。日志、分模块 XML 压缩包、APK 摘要和结果 JSON 位于 `build/reports/enterprise/final-fixes-20260910/`；该记录对应最终工作区，先前门禁单独保留。`git diff --check` 通过，版本、changelog 和 schema 未变，未提交。

## 企业配置界面回归复核

按企业实施文档初始提交 f49f86f9f 至当前工作树，复查聊天快捷配置、助手本域使用编辑、模型/助手/MCP/Workspace 选择、原生接入与导入反馈。两名独立审查分别检查布局/交互与命令失败反馈；本轮不重做已完成的持久化迁移，也不把历史无关问题归为企业域回归。

- b3ad2af8f 引入的聊天本地能力弹窗直接承载整页 AssistantLocalToolContent，缺少标题和关闭栏。现固定标题、助手名称与关闭，列表在剩余高度滚动。
- a447d6864 引入的本域使用编辑头部把长业务按钮与关闭放在一行，窄屏/大字体可挤掉关闭。现关闭固定在标题栏，名称单行省略，业务操作换行。
- 助手、MCP、Workspace 选择器补齐明确关闭；Workspace 列表按剩余高度滚动，管理入口保留在列表之外。这是相关既有体验缺口，不伪称均由企业改造引入。
- ChatVM 配置命令过去捕获失败只返回 Boolean，并把错误放到被弹层遮盖的聊天列表；模型选择进一步丢失原因。现返回 Result<Unit>，保留原始异常及 Workspace 部分失败说明，取消继续传播。模型使用既有选择器错误区，其他操作由当前 target 的局部失败对话框显示，失败保持原选择，不新增配置 owner 或全局错误状态。
- 原生导入明确说明版本倒退/同版本冲突及更新 configuration.generation 的方式；结果反馈按页面既有滚动容器进入可见区域，不改变版本校验或发布协议。

实际 Android 17 模拟器通过覆盖安装验证展开屏及 360dp/1.3 倍字体的本地工具、企业助手和个人助手本域编辑；长列表可滚到末尾，标题关闭固定，两个管理动作换行可达。助手/MCP/Workspace 选择器的关闭入口分别验证。显示参数和当前企业工作助手已恢复；保留第 5 版企业配置和现有聊天，不运行会清除 Debug 数据的 connectedDebugAndroidTest。

本轮 App JVM 完整测试 2,202 项全部通过，包含配置命令原始失败返回、无背后聊天错误和取消传播。Android 正式页面验证版本拒绝反馈自动进入视口，当前配置版本与同步时间保持原值；截图与定向/完整检查日志分别位于 build/reports/enterprise/configuration-*。未把 JVM 结果称为 instrumentation 验收，本轮未重跑 Release 或真实平台互操作。

最终串行 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug 全部通过（8 分 54 秒），git diff --check 通过；最终 Debug APK 已覆盖安装，用户数据保留。版本号未变，未提交。
