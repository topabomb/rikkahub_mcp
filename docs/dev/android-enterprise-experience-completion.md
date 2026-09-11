# 企业常用入口与助手记忆补充实施

前置审查修复已提交为 `abec8255a`，完整门禁和正式 Release 使用证据见 [复审记录](android-enterprise-review-2026-09-11.md)。本文记录已完成的追加范围与验收，当前事实已同步至参考文档。

## 协议核对

依据本机 `measix-architecture` 的 `measix-s0-enterprise-realm-experience-contract-spec.md` §4.1–4.3、§9，以及 `measix-s0-enterprise-portal-product-requirements.md` §7、§9：

- Starter 仅包含企业助手引用、标题、提示词、可选说明、顺序和启用状态。点击选择相应助手并打开预填 Draft，允许修改，由用户发送；不自动执行工具，不引入表单、附件或 workflow 语义。
- 企业 Memory Seed 是随配置 generation 原子切换的只读经验；运行记忆是当前企业主体的用户数据。工具及用户编辑只修改运行记忆，配置更新不覆盖它，Seed 不写入 MemoryEntity。
- 企业公开定义可查看，运行连接及凭据不可披露。当前 Bridge v3 没有 Starter/打开聊天方法；工作台的原生宿主提供常用入口，不向网页新增数据库、配置或聊天写权限。

## 实现边界

| 范围 | 实施方式 |
| --- | --- |
| 工作台常用入口 | 原生工作台入口展示可展开的 Starter 选择，保留网页完整内容。标题、助手和说明用于选择，提示词可预览；用当前配置和原 RealmSelection 复验后创建新的非持久化 Draft，不覆盖已有聊天草稿 |
| Starter 目录 | ConfigurationResolver 统一过滤启用与助手准入、按顺序/ID 排序；聊天输入模板和工作台共用规则。ConversationApplicationService 沿原 Session → Settings 准入创建请求，拒绝旧选择和变更后的定义 |
| 示例差异 | 企业工作助手工具查询与子助手协作、企业写作助手周报预填与记忆偏好四类入口；排序不依赖文件排列。模拟模型沿标准 memory_tool 创建运行记忆，真实文本生成质量不由示例保证 |
| 企业助手查看 | 从企业空间和助手设置目录直接查看公开定义，含名称/说明、固定模型/提示词/MCP、子助手绑定、Seed 和使用中的记忆；不为查看配置创建持久会话，不把企业 ID 交给个人定义编辑器 |
| 记忆管理 | 复用 MemoryService 原主体、选择版本和 Room 写协议。下发 Seed 摘要可展开、无编辑动作；运行记忆可新增/编辑/删除，标明助手专用或空间共享。关闭记忆不删除数据，更新 Seed 不覆盖运行数据 |
| 设置范围 | 共享助手默认设置与当前空间运行记忆分别说明；语音区分当前空间默认选择和共享服务编辑；搜索、提示词、快捷消息补共享范围说明。应用级主题/通知不重复加空间标记 |

Starter 的本地完整资料保持独立于平台 Snapshot。新增可选说明、排序和启用字段以明确默认值扩展现有本地表示；不新增 Room/DataStore 表、运行记忆副本、Seed 编辑协议或旧字段别名。原配置读取保留既有语义，测试验证缺省值及再编码的数据保全。

## 验证与完成条件

- 定向验证 Starter 排序/禁用/不同助手、陈旧配置和切域往返拒绝、新 Draft 不建库不自动发送。
- 验证 Seed 更新后运行记忆保留、主/子助手专用与空间共享隔离，以及旧页面/Session 编辑拒绝；复用已有 owner 测试，不重复实现 SQL 字符串断言。
- 使用唯一 UI 子代理复审所有设置页面，主代理核查最终正式 App。覆盖窄屏大字体、长 Seed 展开、运行记忆编辑和 Starter 返回原聊天流程。
- 串行运行风险匹配的完整 JVM/Debug/lint/Release/设备门禁，记录实际覆盖和实体设备/真实平台限制；不升级版本或发布。

## 实施与审查结果

上述入口、投影、准入及示例已实现。唯一 UI 子代理复审全部设置页面及相关助手、扩展、备份入口；根代理修正 Starter 路由编码并保留原聊天导航项，默认模型的提示词标签补共享范围说明。

| 设置范围 | 最终边界 |
| --- | --- |
| Provider 与详情、搜索、辅助提示词、扩展提示词、快捷消息 | 编辑共享用户定义，不按选中企业复制或覆盖 |
| 默认模型、TTS/ASR 选择 | 原 RealmSelection 下的当前空间偏好；用户语音服务定义仍共享 |
| MCP 与 Gateway | 企业公开定义只读，Gateway 使用偏好沿原选择提交；运行连接不进入公开配置 |
| 助手 | 共享定义、本域使用偏好、企业只读定义分别进入对应入口 |
| 记忆 | Seed 配置只读，运行记录标明实际地址与启用状态；数据写入继续归 MemoryRepository |
| 文件与备份 | 文件操作绑定原空间与行；备份明确个人范围 |
| 外观、语言、通知、常规、字体、关于 | 应用级设置，不重复加企业标记 |

定向 JVM 通过 Starter 编解码、筛选排序、原选择/配置拒绝、Draft 不建库、Seed 更新保留运行记录和模拟模型标准工具调用。新增测试没有创建第二份记忆或 Draft owner。

全量回归同时修正两类测试时序：真实 DataStore/Session 用例不让虚拟调度器提前推进到期计时；真实键盘动画中的 TTS 布局断言在同一 UI 帧读取两组坐标，保留原不遮挡与间距要求。示例 MCP 测试改为按工作助手 ID 取目标，不再假定示例只有一个主助手。TTS 定向设备回归通过。

独立 Android 17 模拟器的正式 Debug 应用在 360dp 宽度和 1.3 字体下实际完成：

- 工作台 Starter 列表、预览、正确助手与中文提示词预填，等待用户发送。
- 带 `draft-retention.txt` 附件的周报草稿进入另一助手的 Starter，系统返回后原文本和附件保留。
- 企业助手直接查看公开配置、只读 Seed；运行记忆新增、编辑、确认删除均成功。键盘弹出时保存可用；删除后 Seed 保留。

最终串行 `test assembleDebug lintDebug assembleRelease connectedDebugAndroidTest --no-parallel --max-workers=1` 全部通过，耗时 16 分 42 秒，完整日志为 `build/reports/enterprise/experience-delivery-gate.log`。
全模块 JVM 2,710 项（App 2,211 项），零失败、零错误，Workspace 保留 11 项 Windows 条件跳过；App lint 零错误、294 项警告、6 项提示。
设备 XML 报告为 App 209 项、Speech 14 项全部通过，Workspace 12 项中 1 项硬链接条件跳过，其余通过。JVM 快照、设备 XML、lint 和 APK SHA-256 均保留在同一报告目录。

最终 Release x86_64 APK 在独立 Android 17 模拟器以中文、360dp 宽度和 1.3 字体实际接入示例，打开工作台、选择记忆 Starter、确认预填后手动发送。
标准 memory_tool 成功创建记录；强制停止并重启应用后，从企业助手目录仍可读取该助手的运行记忆，企业写作 Seed 单独保留。证据为 `experience-release-*.xml`、`experience-release-memory.png`。
Debug 的附件草稿返回与记忆 CRUD、Release 的真实工具写入及重启读取分别记录，不互相代替覆盖。实体手机、真实企业服务和用户私有端点不由本地结果代替。
