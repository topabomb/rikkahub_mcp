# Measix Pilot 子助手 V1 实现设计

> 本文档是子助手功能的最终实现基线。方案已按 `Assistant`、`Conversation`、`ChatService`、
> `GenerationHandler`、`Tool`、Room 持久化和 Compose 消息渲染链路核对；工作区中的阶段性实现若与本文
> 冲突，应在对应阶段收敛到本文约束，而不是反向保留临时代码行为。
> “子助手”即通常所说的 sub-agent；产品文案统一使用“子助手”，模型侧只在相关 Tool/Catalog
> 的首句用简短的 “sub-assistant (sub-agent)”帮助理解概念。

## 1. 目标与边界

V1 解决以下问题：

- 用户可以把现有 Assistant 显式开放为子助手，并为模型提供简短、可路由的能力描述；
- 每个 Assistant 可以显式限定自己能够管理和调用的子助手；工具创建的子助手自动归入创建者的
  允许列表，全局可见子助手则对所有启用相关工具的 Assistant 开放；
- 主助手可以创建、维护可访问的子助手、查看其局部记忆，也可以向其发送自包含请求；
- 子助手拥有独立且可持续的工作上下文，不自动读取主助手的会话历史；
- 子助手执行过程可在主聊天中实时观察，只有终态结果进入主助手模型上下文；
- 子助手调用默认 `ask_user` 时把问题桥接到主聊天的子助手卡片，用户回答后恢复同一 Child run；
- 分支、Fork、停止、进程重启和配置撤销均有确定行为，不留下隐藏审批或串线上下文。

V1 明确不做：

- 后台异步任务、任务队列、mailbox、semantic progress 或预计完成时间；
- 子助手主动打断主助手；
- 子助手继续调用子助手、A2A 网络协议或跨设备执行；
- 在只读详情页继续输入、单独审批工具或启动新一轮；
- 把 Target 生成的图片、音频或文档提升为 Master Tool Result；V1 只返回最终文本，非文本内容仍可在
  Child 详情查看。Target 显式调用 `text_to_speech` 仍按现有后台播放语义执行，但音频不进入 Tool Result；
- 新增子助手并发数、预览长度、记忆模式等用户配置。

调用模型保持为**单层、同步、顺序执行**：`assistant_call` 返回前，主助手当前 Tool Loop
不会继续。Provider 一次返回多个工具调用时，本批先收齐所有审批/回答；存在 Pending 时不执行本批自动
工具。全部决策完成后才按 Tool part 的原始 ordinal 串行执行，因此后续 `assistant_call` 或其他工具不会
越过正在运行、等待回答或尚未完成的前序调用。

## 2. 核心决策

| 维度 | V1 决策 |
|------|---------|
| 用户术语 | 子助手；模型侧描述简短补充 sub-agent |
| 子助手类别 | `Assistant.allowAsSubAssistant`；普通 Assistant 默认 `false`，工具创建时为 `true` |
| 显式允许列表 | `Assistant.allowedSubAssistantIds`，管理与调用共用，默认空 |
| 全局可见 | `Assistant.isSubAssistantGloballyVisible`，仅对子助手有效，默认 `false` |
| 路由信息 | `Assistant.description`，只描述擅长领域和适用时机 |
| 权限分组 | “管理子助手”与“调用子助手”使用独立 Local Tool 选项，均默认关闭 |
| 助手发现 | 启用相关工具时动态注入表头—行形式的紧凑 JSON Catalog，不提供 `assistant_list` |
| 委托方式 | 同步 `assistant_call`，主助手只收到终态 Tool Result |
| 子助手上下文 | 持久化 Child Conversation；按主会话当前分支的调用 lineage 复用 |
| 访问范围 | Tool Catalog 只包含显式允许或全局可见的子助手；执行时从最新 Settings 重新校验 |
| 记忆 | `assistant_memory_list` 只读查看可访问 Target 的 Local Memory；只有 Target 可通过自己的记忆工具维护 |
| 子助手工具 | 默认与普通 Assistant 一致；禁止 Assistant Tools；`ask_user` 显式桥接主聊天，其余 HITL 不进入隐藏 Pending |
| TTS | Target 可使用已启用的 TTS；顺序开关按单次生成生效，播放来源切换时强制中断旧队列 |
| 运行展示 | 主聊天中的独立 `SubAssistantCallCard`，展示有界的最新文本尾部 |
| 详情展示 | 只读复用现有消息渲染管线，只展示本次调用范围 |
| 停止 | 取消主 Job 会级联取消 Target；保存协议有效的 stopped Tool Result，但不重启主循环 |
| App 重启 | 不重放旧任务；恢复为 `stopped/app_restarted` |

## 3. 架构约束与必要改造

基线主链不能只增加 Assistant Tools 就完成该功能，原因如下：

- `Tool.execute` 只有终态返回值，执行期间无法把 Target 输出回写到主消息；
- 原有 `GenerationHandler` 只发出 `GenerationChunk.Messages`，缺少可复用的 phase/checkpoint 事件；
- `ChatService` 在普通生成成功后才统一落库，App 异常退出时不能保证保留正在执行的
  `assistant_call`；
- `ChatService` 同时负责 Session、工具装配、生成副作用和持久化，直接让 Target 再调用
  `ChatService` 会形成递归职责和依赖环；
- `Conversation` 支持消息分支。固定使用“Master Conversation × Target = 唯一 Child”会把
  已经切走分支上的 Target 历史带入新调用；
- `UIMessagePart.Tool.metadata` 已保存 Gemini 等 Provider 的不透明关联数据，子助手 metadata
  不能整体覆盖它；
- Assistant 配置保存在整份 Settings 中；创建子助手与更新 creator 允许列表、关闭类别与反向授权清理、
  删除时的撤权与 cleanup tombstone 必须分别在各自的一次 Settings 原子变换中完成，不能拆成多次整份读写
  后互相覆盖；Room 和文件清理则在提交后幂等执行；
- Conversation 列表、搜索、FTS、统计、删除、Fork 当前都把所有会话视为普通用户会话。

因此实现应先抽取通用执行原语，再增加子助手业务。禁止在 `GenerationHandler` 中按
`toolName == "assistant_call"` 堆叠特殊分支，也不应让 Compose 通过轮询数据库猜测运行状态。

## 4. 数据模型与兼容性

### 4.1 Assistant

`Assistant` 新增：

```kotlin
val description: String = ""
val allowAsSubAssistant: Boolean = false
val isSubAssistantGloballyVisible: Boolean = false
val allowedSubAssistantIds: Set<Uuid> = emptySet()
```

`Settings` 另有内部、默认空且不进入 UI 的 `pendingAssistantDeletions`，用于跨进程重试已经撤权但尚未完成的
Room/文件清理；它不是 Assistant 权限或用户配置。每项只保存清理所需的 Assistant ID 与资源 URI 快照，
不保存完整 Assistant 或 prompt。

`description` 是路由描述，不是 System Prompt。保存时执行以下规范化：

- 去除首尾空白，把换行和连续空白折叠为单个空格；
- 最大保留 240 个 Unicode code point，不能截断 surrogate pair；
- 内容应简短回答“是什么角色或擅长什么、何时适合调用”，不放具体角色指令、工具说明或提示词模板。

字段语义：

- `allowAsSubAssistant` 同时表示“属于子助手类别”与“可被 `assistant_call` 调用”；V1 不再增加另一套
  类型字段；
- `allowedSubAssistantIds` 存在于 caller Assistant 上，是管理与调用工具共用的显式允许列表；
- `isSubAssistantGloballyVisible` 是工具访问权限，不是普通 UI 的显示偏好。开启后，所有启用管理或
  调用工具的 Assistant 都能在 Catalog 中看到该 Target，并执行各自工具允许的操作；管理修改和删除
  仍逐次审批，局部记忆仍只有只读查看能力；
- caller 对 Target 的有效访问条件统一为：

```text
Target.allowAsSubAssistant
&& Target.id != Caller.id
&& (Target.id in Caller.allowedSubAssistantIds || Target.isSubAssistantGloballyVisible)
```

兼容与维护规则：

- 历史配置缺字段时按 `false`、`false`、空集合解码，内部删除 tombstone 也默认为空；升级不会自动分类、
  授权或删除现有 Assistant；
- UI 新建 Assistant 默认为普通 Assistant；`assistant_manage(CREATE)` 创建的 Assistant 显式归为
  子助手、保持非全局可见，并在同一个 Settings 原子更新中加入 creator 的允许列表；
- 只有用户 UI 可以修改子助手类别、全局可见和既有 Target 的允许关系；唯一例外是
  `assistant_manage(CREATE)` 在创建私有子助手的同一原子变换中，把该新 ID 加入 creator 的允许列表。
  Assistant Tools 不能把其他既有 Target 加入允许列表，也不能修改全局可见；
- 开启时 `description` 必须非空；Target 未绑定模型时 UI 显示“调用时继承 caller 模型与生成参数”的说明，
  只有显式绑定失效，或 Target 未绑定且 caller 也没有有效模型时 Runtime 才阻断；
- 关闭子助手类别时同时关闭全局可见，并从所有 Assistant 的允许列表移除其 ID，避免以后重新开启时
  静默恢复旧授权；删除 Assistant 时执行相同的允许列表清理；
- 允许列表中的缺失 ID、普通 Assistant ID 和 caller 自身 ID 在读取时忽略，不进入 Catalog；
- Settings 每次写入都执行同一规范化函数：非子助手强制 `isSubAssistantGloballyVisible = false`，允许列表
  去重但不依赖集合迭代顺序；Catalog 与 UI 始终按 `Settings.assistants` 的顺序解析 ID；
- UI 克隆保留来源 Assistant 的类别和一般能力配置，但把全局可见重置为 `false`、显式允许列表重置为空；
  克隆不是工具创建行为，不自动加入任何其他 Assistant 的允许列表。

### 4.2 Child Conversation

`Conversation` 与 `ConversationEntity` 新增 nullable 字段：

```kotlin
val parentConversationId: Uuid? = null
```

- `null`：普通用户 Conversation；
- 非 `null`：子助手 Child Conversation，值为 Master Conversation ID；
- Child 的 `assistantId` 是 Target Assistant ID；
- Child 的 title 只保存 Target 名称快照用于诊断，不触发标题模型；folder、tags、pinned、suggestions、对话级
  System Prompt、对话级 Prompt Injection 与 workspace cwd 均保持空/关闭；
- 首次创建时只写入 Target 的 preset messages，随后追加本次请求。

不能建立 `(parentConversationId, assistantId)` 唯一约束。一个 Master 在不同消息分支上可能对
同一 Target 形成多个合法 Child lineage。数据库为 `parent_conversation_id` 建普通索引，并同时补齐现有
按 Assistant 查询所需的 `assistant_id` 普通索引；不增加组合唯一约束或自引用外键。Child 只能指向顶层
Master 的约束由 Repository 写入入口校验，Master 删除由 Repository 在同一 Room 事务中删除 Child 与相关
MessageNode。所有删除入口必须经过 Repository，orphan recovery 只作为异常恢复兜底。

数据库升级使用显式 `Migration_3_4`，新增 v4 Room schema；同时保留并显式注册历史
`Migration_1_2`、`Migration_2_3`，保证 v1-v3 任一已发布数据库都能连续升级到 v4。已有 v1-v3 导出 schema 必须保留，不能为了
只看最新结构而删除，否则 MigrationTest 和历史升级证据会失效。schema 只保存在 KSP 按数据库完整类名
生成的 canonical 目录，不再维护另一份短名 `AppDatabase/` 副本。迁移只执行
`ALTER TABLE ... ADD COLUMN parent_conversation_id TEXT DEFAULT NULL`，再创建 parent 与 assistant 普通
索引，不重建 Conversation 主表。Migration SQL 的索引名必须与 Room 导出 schema 完全一致：
`index_ConversationEntity_parent_conversation_id` 与 `index_ConversationEntity_assistant_id`，不能另写一套大小写
不同的名字。这样不会触碰既有 MessageNode 外键或历史数据。迁移测试验证原有会话、节点、这些索引和默认
`null` 均保持正确。

### 4.3 调用 metadata

每个 `assistant_call` 的 `UIMessagePart.Tool.metadata` 在既有 JsonObject 下增加嵌套字段：

```json
{
  "sub_assistant_call": {
    "schema_version": 1,
    "run_id": "...",
    "previous_run_id": "...",
    "target_assistant_id": "...",
    "target_name_snapshot": "Android 分析助手",
    "child_conversation_id": "...",
    "child_task_node_id": "...",
    "state": "running",
    "phase": "tool_executing",
    "active_tool_name": "workspace_read_file",
    "preview": "...",
    "reason": null
  }
}
```

约束：

- `run_id` 是 App 生成的 UUID，不依赖 Provider 可能重复或缺失的 `toolCallId`；
- `previous_run_id` 指向主会话当前分支中同一 Target 的最近一次调用；
- `target_name_snapshot` 保证 Target 改名或删除后历史卡片仍可读；头像优先实时解析，失败时用默认头像；
- `child_task_node_id` 是本次 Child USER request 的起点；范围终点是下一个 USER request 之前；
- `preview` 只保存有界 UI 预览，不保存 reasoning、工具原始输出或完整 Child 历史；
- 所有状态和 reason code 使用稳定英文枚举，显示文案在 Compose 层本地化；
- 同一 run 的 metadata 只能由 `SubAssistantRunStateReducer` 按 `copy` 语义更新并输出完整快照；phase、
  preview 和终态不能各自重新构造对象，否则会丢失 `previous_run_id`、Child link 或已写入字段；
- `active_tool_name` 只在 `phase == tool_executing` 时保留；进入其他 phase 或任一终态时清空。Reducer 忽略
  终态之后迟到的 phase/preview，避免历史 Card 继续显示“正在使用工具”；
- 写入时只 merge `sub_assistant_call` 这个 key，绝不能替换整个 `Tool.metadata`，否则会丢失
  Gemini `functionCallId` / `thoughtSignature` 等协议状态；
- Provider 序列化只读取自己认识的 metadata 字段，`sub_assistant_call` 不进入 API 请求。

状态为：

```text
starting → running → completed
                   ↘ failed
                   ↘ stopped
starting ──────────→ unavailable | failed | stopped
```

终态后不可回到 running。

## 5. Assistant 配置 UI

所有新增用户可见字符串同步到 English、Chinese、Japanese、Korean、Russian 资源。

### 5.1 创建与类别

Assistant 创建 Bottom Sheet 增加：

- “能力描述”多行输入框，显示简短示例和剩余长度；
- “作为子助手”开关，默认关闭；描述为空时不能开启，并显示原因；
- 开启“作为子助手”后显示“全局可见”，默认关闭。

UI 创建仍以普通 Assistant 为主；`assistant_manage(CREATE)` 创建的条目直接属于子助手类别、非全局可见，
因此默认不会出现在普通助手列表中。子助手类别不禁止用户直接聊天，用户在显示全部后仍可主动选择它；
这种直接聊天创建的是普通顶层 Conversation，不复用或暴露 Child Conversation。

### 5.2 助手切换与管理列表

`AssistantPickerSheet` 与 `AssistantPage` 采用相同的本地筛选语义：

- 默认只显示 `allowAsSubAssistant == false` 的普通 Assistant；
- 提供“显示子助手”FilterChip，开启后显示全部 Assistant；该筛选不持久化为用户设置；
- 当前会话正在直接使用子助手时，打开切换 Sheet 默认开启该 FilterChip，保证当前项可见；
- 当一个普通 Assistant 都不存在时，Picker 与设置列表也默认开启该 FilterChip，避免出现无法选择或管理
  Assistant 的空页面；
- 类型筛选先执行，再叠加现有 name/description 搜索和 Tag 筛选；
- 全局可见只影响 Assistant Tools，不绕过这里的普通 UI 类型筛选。

左侧栏“切换助手”的 `AssistantPickerSheet`：

- 名称下方优先显示 `description`，最多两行；没有描述时保持当前紧凑布局；
- 子助手显示紧凑“子助手”Tag，普通 Assistant 不增加 Tag；
- 头像、名称和内容区域保持一个点击目标，编辑按钮继续独立，避免子组件吞掉切换点击；
- 选择子助手仍沿用现有 `Conversation.withAssistant` 语义，不产生后台 `assistant_call`。

设置中的 `AssistantPage`：

- 搜索框下方先显示“显示子助手”FilterChip，再显示现有 Tag 过滤器；
- 开启后，子助手显示“子助手”Tag；全局可见的子助手再显示“全局”Tag，普通 Assistant 不增加视觉噪声；
- description 仍作为名称下方的路由摘要，搜索同时匹配 name 与 description；
- 空结果若存在被类型筛选隐藏的匹配项，提供“显示子助手”操作，不把隐藏误报为不存在；
- 排序实现必须按 Assistant ID 映射回 `Settings.assistants`，不能把过滤后 Lazy 列表索引直接当作源索引。

### 5.3 基础设置页

`AssistantBasicPage` 的身份 Card 顺序调整为：

```text
头像
助手名称
能力描述
Tags
Workspace
作为子助手
全局可见（仅子助手显示）
使用助手头像
```

“作为子助手”的 supporting text：

> 允许其他助手调用它处理请求，包括任务、问题或角色对话。它使用独立工作上下文，不会自动看到主对话。

“全局可见”的 supporting text 必须明确它是权限开关而不只是展示开关：

> 允许所有启用子助手工具的助手发现、调用和管理它。修改和删除仍需确认；启用管理工具的助手也可查看其局部记忆。

辅助状态：

- description 为空：阻止开启；已经是子助手时也不能把空描述提交保存，必须先补充描述或关闭类别，并定位
  到描述输入框；
- 关闭“作为子助手”：在同一次原子更新中关闭“全局可见”并清理所有显式允许关系；
- 当前没有有效 Chat Model：允许保存，但显示“调用时将无法启动”；
- Target 使用 Global Memory：显示“被调用时会使用共享全局记忆”的非阻断警告，避免把
  Local Memory 隔离误解为强制覆盖用户设置。

`AssistantDetailPage` 头像下方改为显示 description，而不是截取 systemPrompt。System Prompt
仍只在 Prompt 页面编辑。

### 5.4 Local Tools 页

`AssistantLocalToolPage` 顶部新增独立 CardGroup“助手协作”，避免与时间、剪贴板、日历等设备工具混杂：

```text
管理子助手
允许该助手创建子助手，并管理已显式允许或全局可见的子助手。修改和删除需要确认；只能查看目标的局部记忆。

调用子助手
允许该助手向允许列表或全局可见的子助手发送请求。
```

对应配置：

```kotlin
LocalToolOption.AssistantManagement
LocalToolOption.AssistantDelegation
```

任一工具开启时，在同一 CardGroup 下显示“子助手访问范围”入口，副标题为“选择显式允许的子助手；
全局可见的子助手始终可用”。点击进入可搜索的多选 Sheet，避免在设置页直接铺开长列表：

- 候选项是除当前 Assistant 外的全部子助手；默认没有显式选中项；
- 勾选结果写入当前 Assistant 的 `allowedSubAssistantIds`，同时约束管理、记忆查看和调用；
- Checkbox 只表达“显式允许”；全局可见子助手即使未勾选也会生效，行内显示“全局可用”状态，避免把
  未勾选误解为当前不可访问；仍允许显式勾选，supporting text 说明这会在以后关闭全局可见时保留访问；
- `assistant_manage(CREATE)` 创建成功后，新 ID 自动显示为已选中；
- 关闭管理或调用开关不清空允许列表，避免临时撤销工具后丢失用户选择；
- 没有显式允许项且没有全局可见 Target 时，调用 Catalog 为空；管理工具仍可 CREATE。

管理与调用工具对历史 Assistant、UI 新建 Assistant 和工具创建的 Assistant 均默认关闭。Target Run 即使在
配置中开启了它们，也会被 Runtime 强制过滤，保证 V1 只有一层调用。

## 6. 工具集与 Assistant Catalog

### 6.1 Tool 分组

| 配置 | 实际注册工具 | 是否审批 |
|------|--------------|----------|
| `AssistantManagement` | `assistant_manage`、`assistant_memory_list` | manage 始终审批；memory list 自动执行 |
| `AssistantDelegation` | `assistant_call` | 自动执行 |

Assistant Tool 不继续放入 `LocalTools`。新增独立 `AssistantToolFactory`，因为这些工具依赖
Settings、Memory、Conversation、当前 Master 上下文和 `SubAssistantCoordinator`，塞入
`LocalTools` 会造成职责膨胀或 DI 环。

### 6.2 精简后的 Tool descriptions

`assistant_call`：

> Delegate a self-contained request to a catalog sub-assistant (sub-agent). Do not prescribe how it must work.

`assistant_manage`：

> Create, update, or delete a sub-assistant (sub-agent). New ones join your allowed list.

`assistant_memory_list`：

> List the local memories of a sub-assistant in the catalog. This is read-only; only the target can change returned memories while using its local memory tools. Global memory is never returned.

描述只说明能力、边界和关键前置条件，不重复写成长篇提示。静态类型、enum 和无条件 required 放在
JSON Schema；`assistant_manage` 随 action 变化的必填组合以及规范化后的非空校验由执行器完成，因为现有
`InputSchema.Obj` 不表达条件分支。

### 6.3 动态 Catalog

不增加 `assistant_list`。模型在决定是否调用之前就需要知道候选项，额外 list round-trip
只增加延迟和上下文噪声。

Catalog 继续使用 `Tool.systemPrompt(model, contextMessages)`，每个 Master LLM step 从最新
`SettingsStore.settingsFlow.value` 构建；不在 Conversation 创建时缓存。列表保持 Settings 中的
用户顺序以稳定 prompt prefix。

根据结构化返回压缩方案，只对重复的同构列表使用标准 JSON 的 `header + rows`。Catalog 的字段少且
含义直观，不增加 `summary`、`field_desc`、`enum_map` 或自定义格式；单对象 Tool Result 继续使用具名
JSON，避免为很小的数据引入比重复 key 更大的说明开销。实际注入和 Tool Result 使用 compact JSON，
示例中的换行只服务文档阅读。

Catalog 的数据集合始终使用同一有效访问公式，只包含 caller 显式允许或全局可见的子助手。普通
Assistant、caller 自身、失效 ID 和未授权子助手均不出现。

三种 mode 使用同一前缀，隔离与能力说明写在对应工具字段上，不在 Catalog 复读：

```text
<sub_assistant_catalog>
Sub-assistants (sub-agents).
{"header":["id","name","description"],"rows":[["...","Android 分析","Analyze Android, Kotlin, Gradle, and app architecture issues."]]}
</sub_assistant_catalog>
```

构建规则：

- 始终排除当前 Assistant；
- 只包含 `allowAsSubAssistant == true` 且满足有效访问公式的 Target；
- 显式允许与全局可见具有相同的工具能力，不在 Catalog 增加来源列；权限来源只在用户配置 UI 显示；
- 运行时不能仅信任 Catalog；`assistant_manage`、`assistant_memory_list` 和 `assistant_call` 执行前都从
  最新 Settings 确认 caller 仍存在、对应 LocalToolOption 仍启用，并重算访问范围；
- 空列表仍输出相同 `header` 与空 `rows`，不再追加解释性文案；
- 使用 kotlinx.serialization 生成 JSON，禁止字符串拼接；用于 XML-like prompt 边界时，再把 `<`、`>`、`&`
  编码为合法 JSON Unicode escape，避免不可信 name/description 形成伪造闭合标签；
- name 与 description 作为不可信数据；description 已在写入时规范化和限长；
- management 存在时由 `assistant_manage.systemPrompt()` 注入；仅有 delegation 时由
  `assistant_call.systemPrompt()` 注入，避免重复 Catalog。

### 6.4 `assistant_manage`

参数：

```text
action: CREATE | UPDATE | DELETE
assistant_id?: String
name?: String
description?: String
instructions?: String
```

JSON Schema 字段描述保持简短且适合角色扮演与通用场景：

| 字段 | description |
|------|-------------|
| `action` | `CREATE, UPDATE, or DELETE.` |
| `assistant_id` | `Required for UPDATE and DELETE.` |
| `name` | `Display name.` |
| `description` | `Specialty and when to call it. Not a system prompt.` |
| `instructions` | `System prompt for the sub-assistant: role, method, output style. Do not invent tools or skills.` |

规则：

- CREATE 要求 name、description、instructions 均非空；创建目标固定为子助手、非全局可见，并把新 ID
  原子加入 caller 的 `allowedSubAssistantIds`；不得接受 caller 指定的 `assistant_id`；
- UPDATE 要求 assistant_id，Target 必须在当前 Catalog 有效范围内，且
  name/description/instructions 至少提供一个；
- CREATE 的所有文本字段、UPDATE 实际提供的字段在规范化后不得为空；Target 的 description 始终必须
  保持非空，并继续遵循路由描述的长度与内容边界；
- `instructions` 映射 `Assistant.systemPrompt`；
- UPDATE 不允许修改 model、工具、Memory、头像、Tag、背景、子助手类别、全局可见和允许列表；
- DELETE 除 action 外只接受 assistant_id，Target 必须在当前 Catalog 有效范围内；删除后从所有 Assistant 的允许
  列表清理其 ID；不能删除当前 caller，也不能删除最后一个 Assistant；
- 所有 action 的 `needsApproval` 恒为 `true`；
- 审批等待期间权限可能变化。真正执行时必须在同一个 Settings 原子变换内重新确认 caller 仍存在、
  `AssistantManagement` 仍启用、Target 仍满足有效访问公式，再完成 CREATE/UPDATE/DELETE 的 Settings
  变更；审批前的 Catalog 和参数预览不能作为授权快照；
- `SettingsStore` 增加共享的原子 transform API，由内部 Mutex 串行化“读取最新值 → 修改 → DataStore
  提交”；只有 DataStore 提交成功后才发布新 `settingsFlow` 并返回成功，transform 内禁止执行文件或
  Room 副作用。`AssistantManagementService`、Assistant 编辑页和 UI 删除均使用该入口，避免工具操作
  与用户设置并发时由整份旧 Settings 覆盖新值；编辑页也必须在原子块内按 Assistant ID 对最新对象应用
  字段 delta，不能拿页面早先缓存的整个 Assistant 替换后覆盖新加入的允许关系或 tombstone；
- UI 删除复用 `AssistantManagementService`，避免两套清理逻辑。

专用审批摘要只展示用户做决定所需的信息：CREATE 显示名称、路由描述和可展开的 instructions；UPDATE
显示 Target 与实际变更字段；DELETE 显示 Target 名称，并明确会立即撤销访问、停止相关运行及删除其普通
会话和局部记忆，其他主对话里的历史调用详情保留。摘要不展示 Catalog、内部权限集合或未变更配置。

工具创建的 Assistant 使用显式模板；Local Tools 与 UI 新建的默认 Assistant 共用
`DEFAULT_ASSISTANT_LOCAL_TOOLS`，其余扩展能力保持关闭：

```text
allowAsSubAssistant = true
isSubAssistantGloballyVisible = false
allowedSubAssistantIds = emptySet()
chatModelId = null                  # 调用时继承 caller 的有效模型与模型执行参数
enableMemory = true
useGlobalMemory = false
localTools = [TimeInfo, Tts, AskUser] # 与普通 Assistant 默认工具配置一致
enableWebSearch = false
enableRecentChatsReference = false
mcpServers = emptySet()
workspaceId = null
enabledSkills = emptySet()
modeInjectionIds = emptySet()
quickMessageIds = emptySet()
presetMessages = emptyList()
regexes = emptyList()
customHeaders = emptyList()
customBodies = emptyList()
messageTemplate = "{{ message }}"
enableTimeReminder = false
allowConversationSystemPrompt = false
allowConversationPromptInjection = false
AssistantManagement = disabled
AssistantDelegation = disabled
```

Target Run 实际保留这三个默认工具：`get_time_info`、`text_to_speech`、`ask_user`。`ask_user` 进入 Pending
后由 Coordinator 按 Child `messageId + toolOrdinal` 持久化定位器，并把问题写入当前 `assistant_call`
metadata；主聊天卡片使用独立视觉区域收集回答，不能与主助手自己的提问卡片混淆。回答写回同一个 Child
Tool part 后继续该 run。`AssistantManagement` 与 `AssistantDelegation` 仍默认关闭，Target Run 也会再次
过滤全部 Assistant Tools。builder 对其他会带入上下文、网络请求或工具能力的字段继续显式关闭；头像、
主题、采样参数等纯呈现/生成偏好才可沿用普通产品默认值。

`chatModelId = null` 不会把模型写死到工具创建的 Assistant，也不再直接依赖全局默认模型。每次
`assistant_call` 构造只存在于内存的 `SubAssistantRunSpec`：Target 显式绑定有效 Chat Model 时使用 Target
模型和 Target 生成参数；Target 未绑定模型时使用 caller 当前有效模型，并继承 caller 的
temperature/topP/maxTokens/reasoning、stream/context limit 与 Assistant 级 custom headers/bodies。后者只影响
本次运行，不回写 Target。显式绑定但失效时返回 `target_model_unavailable`，不得静默换模型；Target 未绑定且
caller 也没有有效模型时返回 `caller_model_unavailable`，并在创建 Child 或取得 lease 前结束。配置页对未绑定
模型显示继承说明，只对显式绑定但失效的模型显示错误。

UI 新建普通 Assistant 仍沿用现有普通默认能力，只新增 `allowAsSubAssistant = false`。

CREATE 与 UPDATE 的成功结果保持紧凑，并返回变更后的最小快照；`instructions` 不回显：

```json
{"action":"update","assistant":{"id":"...","name":"审稿人","description":"Review arguments and evidence."}}
```

DELETE 返回删除前的身份快照：

```json
{"action":"delete","assistant":{"id":"...","name":"审稿人"}}
```

Settings 中的权限撤销和 durable cleanup tombstone 提交后，DELETE 即视为业务删除成功；Room/文件等幂等
清理若未在本次调用完成，结果额外带 `"cleanup_pending":true`，否则省略该字段。该告警不恢复 Target，
启动恢复会继续消费 tombstone。

校验失败只返回稳定原因，例如 `{"error":"assistant_not_found"}` 或
`{"error":"invalid_arguments","field":"description"}`；不再同时返回含义重复的 `status`、`success`
和自然语言 message。缺失目标用 `assistant_not_found`，caller 自身用 `target_is_caller`，普通 Assistant
或越权子助手统一用 `target_not_allowed`；执行时 caller 消失或工具已关闭统一用 `tool_not_permitted`，
最后一个 Assistant 用 `last_assistant`，提交失败用 `operation_failed`。已知 ID 不能绕过 Catalog，内部异常
和 Settings 内容不进入结果。

### 6.5 `assistant_memory_list`

参数只有 `assistant_id`，其 Schema description 为
`Catalog id.`。Target 必须属于当前 Catalog 有效范围；local 模式读取
`assistant.id.toString()` 对应的 Local Memory namespace：

```json
{"assistant":{"id":"...","name":"Android 分析助手"},"active_memory":"local","header":["id","content"],"rows":[[12,"项目使用 JDK 17"]]}
```

`active_memory` 为 `disabled | local | global`，反映 Target 被调用时实际采用的 Memory 配置。只有
`active_memory == local` 时读取 Target 的 Local namespace；`global` 或 `disabled` 均返回相同 `header`
与空 `rows`，既不暴露共享 Global Memory，也不把当前不会生效的旧局部记录误报为该角色正在使用的记忆。
每行 `id` 是稳定的记忆记录 ID，用于准确引用，不代表 caller 获得了修改权限。

不增加 memory get/update/delete。caller 只能查看；若同时拥有 `assistant_call`，可把
具体 `id` 放入 `request`，由 Target 判断并使用自己的 `memory_tool` 维护。若 `active_memory` 为 `global`
或 `disabled`，caller 只能看到空表与模式，不能声称已经读取或修改该角色的局部记忆。
Target 的 `memory_tool` 对 edit/delete 必须把 owner namespace 与记录 ID 一起交给 Repository；DAO 使用
`WHERE id = :id AND assistant_id = :ownerId` 并检查受影响行数，不能先按全局主键取记录再无条件更新或
删除。ID 只是定位符，不是跨助手修改权限。
Target 缺失、传入 caller 自身或不在有效访问范围时，分别返回稳定的 `assistant_not_found`、
`target_is_caller` 或 `target_not_allowed`；caller 消失或工具已关闭返回 `tool_not_permitted`，读取失败返回
`operation_failed`。全局可见 Target 与显式允许 Target 使用相同只读规则。

### 6.6 `assistant_call`

参数：

```text
assistant_id: String
request: String
```

JSON Schema 字段描述：

| 字段 | description |
|------|-------------|
| `assistant_id` | `Catalog id.` |
| `request` | `It cannot see this chat. Include facts, constraints, and the expected deliverable.` |

`assistant_id` 必须来自当前 Catalog。`request` 可以是任务、问题、角色对话或其他请求，但必须自包含。
Target 按自己的角色、System Prompt
和能力执行；它不自动读取 Master 的 system prompt、普通消息、reasoning 或工具历史，只能看到自身
Child lineage 中此前收到的请求以及本次 `request`。

JSON 结构无效时返回 `invalid_arguments`；缺少或无法解析 `assistant_id` 时分别返回
`assistant_id_required`、`invalid_assistant_id`；缺少或空白 `request` 时返回 `request_required`。这些参数错误
不创建 run Card。语义参数通过后立即创建 run metadata；
若 preflight 失败，写入 `unavailable` metadata 后返回 Tool Result。这类调用以及后续的 `target_busy`
都没有本次 request 的 Child link，Card 显示原因且不可进入详情，不能挂接到另一个仍在执行的 run。

终态 Tool Result：

```json
{"status":"completed","assistant_name":"Android 分析助手","content":"Target final answer"}
```

```json
{"status":"unavailable","reason":"target_model_unavailable"}
```

```json
{"status":"failed","reason":"provider_error"}
```

```json
{"status":"stopped","reason":"user_cancelled"}
```

Tool Result 不包含内部 Exception、Child 全历史、实时 preview 或 UUID 调试信息。最终结果仍遵循
现有通用 Tool output 大小策略；“只有 final 进入 Master Context”不等于绕过全局输出保护。
Target 没有最终文本但包含非文本 part 时仍可 `completed`，`content` 为空并附带
`has_non_text_output: true`；该字段为 `false` 时省略。`assistant_name` 是本次调用快照，帮助通用协作和
角色扮演场景保留回答归属；输入已经包含 `assistant_id`，结果不重复返回。

`content` 优先取本次 run 最后一个 Target ASSISTANT step 中、最后一个**工作工具**之后的所有顶层
可见 `Text` part，按原顺序拼接。`text_to_speech` 等副作用工具不算工作工具，不能挡住答案。
若最后一步只有 Reasoning 或空白 Text，回退到更早 step 的 post-tool 文本；仍为空时取该范围内最后
一段顶层 Text island，避免主助手拿到空 `content`。不是只取最后一个 Text part，也不把 reasoning
或 Tool output 算进答案。之后再统一经过既有 Tool output 大小保护。
`has_non_text_output` 只检查最后一个 ASSISTANT step 的顶层可见非文本 part，不把 Tool output 或
`text_to_speech` 后台副作用算进去。

稳定 reason code：

| status | reason |
|--------|--------|
| `unavailable` | `tool_not_permitted`、`assistant_not_found`、`target_not_allowed`、`target_model_unavailable`、`caller_model_unavailable`、`target_busy` |
| `failed` | `provider_error`、`runtime_error`、`step_limit_reached` |
| `stopped` | `user_cancelled`、`app_restarted`、`target_removed`、`target_disabled`、`target_access_revoked`、`target_model_unavailable`、`caller_model_unavailable`、`child_missing` |

## 7. Runtime 职责划分

### 7.1 新增/调整组件

| 组件 | 职责 |
|------|------|
| `ConversationSessionRegistry` | 唯一持有 Session/Job/StateFlow 生命周期，供 Master 和 Child 共用；禁止 `ChatService` 保留第二份 sessions map |
| `GenerationToolSetFactory` | 按 Assistant、资源和 Run Mode 先装配带稳定 capability key 的注册项，再投影为 Search/Local/Conversation/Workspace/Skill/MCP Tool；Memory Tool 仍由 `GenerationHandler` 按 owner namespace 构建 |
| `AssistantToolFactory` | 构建 Assistant Tools 及 Catalog；捕获当前 Master Conversation 上下文 |
| `SubAssistantAccessPolicy` | 统一计算显式允许与全局可见的有效集合，供 UI、Catalog、Tool 执行和运行中撤权复用 |
| `AssistantManagementService` | Assistant CRUD、Tool 子助手范围校验、允许列表原子维护，以及“先撤权和停 run、后幂等清理”的删除编排 |
| `SubAssistantLineageResolver` | 根据当前 Master 分支选择、复用或克隆 Child lineage |
| `SubAssistantRunStateReducer` | 串行维护单次调用的完整 metadata 快照并校验状态单向转换 |
| `SubAssistantCoordinator` | Readiness、Child run lease 与 caller/Target 二级索引、活跃 run 的 Settings 撤权监听、RunSpec、Child checkpoint、Target Generation、进度桥接、终态与恢复；索引不另存第二份 Session/Job |
| `GenerationHandler` | 保持协议无关的模型/工具循环；用 `channelFlow` 安全承接可取消子 Job 的 metadata 事件，并提供精确 Tool locator、phase/checkpoint/finished 与 contextual tool execution |
| `ChatService` | 仅编排普通用户会话、副作用和终态后处理；不递归承担 Child 业务 |

依赖方向：

```text
ChatService
  ├─> ConversationSessionRegistry
  ├─> GenerationToolSetFactory
  ├─> AssistantToolFactory
  └─> GenerationHandler

AssistantToolFactory
  ├─> AssistantManagementService
  └─> SubAssistantCoordinator
          ├─> SubAssistantAccessPolicy
          ├─> SubAssistantLineageResolver
          ├─> GenerationToolSetFactory
          ├─> ConversationSessionRegistry
          ├─> ConversationRepository
          └─> GenerationHandler

AssistantManagementService
  ├─> ConversationSessionRegistry（停止普通顶层 generation）
  └─> SubAssistantCoordinator（按 caller/Target 停止相关活跃 run）
```

`SubAssistantCoordinator` 不依赖 `ChatService`，从而避免 `ChatService → Tool → ChatService` 循环。
`ConversationSessionRegistry` 必须满足以下不变量：同一 Conversation ID 只有一个 Session；加载持久化会话
使用显式 `open(conversation)`，不得先创建空 Session 再遮蔽 Room 快照；已有活跃 Job 时 Room 旧快照
不能覆盖内存状态；Child Session 必须由真实 Child Conversation 初始化，不能使用默认 Assistant fallback。
Coordinator 在 Child Session 上登记结构化子 Job，并在 `finally` 清除；详情页按现有 acquire/release 维护
引用。只想查询状态的路径使用非创建型 `getExisting()`，不能因为渲染一张历史 Card 而生成空 Session。

### 7.2 通用 Tool 执行上下文

保留现有 `Tool.execute`，以可选且 `@Transient` 的 `contextualExecute` 做增量扩展，避免迫使全部工具和
序列化结构同步改签名：

```kotlin
data class ToolExecutionContext(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val toolCallId: String,
    val reportMetadata: suspend (patch: JsonObject, checkpoint: Boolean) -> Unit,
)

val execute: suspend (JsonElement) -> List<UIMessagePart>

@Transient
val contextualExecute:
    (suspend ToolExecutionContext.(JsonElement) -> List<UIMessagePart>)? = null

suspend fun executeWithContext(
    context: ToolExecutionContext,
    args: JsonElement,
): List<UIMessagePart>
```

`GenerationHandler` 只能通过 `executeWithContext()` 执行工具；普通 Tool 自动回退 `execute`。
`assistant_call` 的普通 `execute` fallback 只返回稳定 `context_required` 错误，不能在缺少真实
locator/reportMetadata 时启动 Child，避免产生无法关联和恢复的隐藏运行。

`messageId + toolOrdinal` 是本次执行在当前 ASSISTANT message 中的内部精确 locator；`toolOrdinal` 是该
message 内按原顺序从零开始的 Tool part 序号，而不是所有 part 的绝对下标。输出 Transformer 可以拆分
Text/Reasoning，但必须保持 Tool part 的数量与相对顺序，因此该 locator 在 raw、visual 和持久化投影中
稳定。`toolCallId` 继续保留给 Provider 协议，但不能作为内存更新或 durable finalizer 的唯一键，因为不同
step 可能复用 ID，流式早期还可能出现空 ID。`reportMetadata` 由 `GenerationHandler` 实现：

1. 按 locator 找到当前未完成 `UIMessagePart.Tool`，并核对其 `toolCallId/toolName`；
2. merge metadata patch，保留 Provider 不透明字段；
3. 通过 `channelFlow.send` 发送更新后的 `GenerationChunk.Messages`；
4. `checkpoint == true` 时再发送 `GenerationChunk.Checkpoint`。

普通 `flow { emit(...) }` 不允许 `assistant_call` 的可取消子 Job 跨协程回写；否则首个 Running Card 更新会
触发 Flow invariant，后续同批工具再触发 exception-transparency 连锁错误。`generateText()` 因此使用
`channelFlow`，所有 GenerationChunk 均用线程安全的 `send`；工具执行顺序仍由外层 ordinal 循环决定，
`channelFlow` 不表示并行执行 ToolCall。

这里的 patch 只负责顶层 `Tool.metadata` merge。`SubAssistantCoordinator` 必须先让
`SubAssistantRunStateReducer` 基于上一份完整状态生成下一份 `sub_assistant_call` 快照，再作为单个 key
提交；Reducer 以 Mutex/单 actor 串行处理 phase、preview 和 terminal，并作为唯一
`reportMetadata` 调用者。禁止多个 collector 并发改 `GenerationHandler.messages`，也禁止 phase collector 和
preview collector 各自用默认值拼一份新对象。

contextual execute 返回后，`GenerationHandler` 必须按同一 locator 从最新 `messages` 重新取得 Tool，
再 copy terminal output；不能基于执行前捕获的旧 Tool copy，否则会把执行期间已经写入的
run/preview/provider metadata 覆盖掉。当前 step 的 approval、拒绝、执行结果合并和 interrupted cleanup
也必须使用 locator；禁止任何 `find { it.toolCallId == ... }` 式的首项匹配。

Tool output 截断文件等本地执行产物也使用 App 生成、文件名安全且由 locator 唯一确定的 execution ID；
不能直接把 Provider `toolCallId` 当文件名，否则空值、跨 step 复用或路径字符会造成覆盖与越界风险。

这是一项通用 Tool 能力，不把 App 的 Conversation 类型引入 `ai` 模块。`assistant_call` 用它回写
run link、phase、preview 和正常终态；取消与进程恢复所需的持久化终态由 `SubAssistantCoordinator`
直接更新 Master Session/Repository，不能依赖已经取消的 Flow 继续 emit。

### 7.3 Generation 事件

`GenerationChunk` 扩展为：

```text
Messages(messages)
Phase(phase, toolName?)
Checkpoint(kind)
Finished(reason)
```

稳定 phase：

```text
preparing
model_waiting
reasoning_streaming
answer_streaming
tool_executing
between_steps
```

phase 不使用本地化字符串。执行每个具体 ToolCall 前必须发出
`Phase(tool_executing, registeredToolName)`，不能只在整批工具前发一次无名称事件。

`CheckpointKind` 至少区分 `STEP_COMPLETED`、`TOOL_STATE_CHANGED` 和
`TOOL_RESULT_COMPLETED`。`reportMetadata(checkpoint = true)` 使用 `TOOL_STATE_CHANGED`，不能把尚未完成的
start link 伪装成 `TOOL_RESULT_COMPLETED`。`Finished.reason` 为
`completed | awaiting_approval | step_limit_reached`；达到最大 step 时不能让 Flow 静默正常结束，否则
Coordinator 会把未完成运行误记为 completed。

消费规则：

- Master 与 Target collector 都用 Messages 更新各自 Session；
- Master 可以忽略 Phase，但必须消费 Checkpoint 并保存当前 Master，保证 Child link 在运行中可恢复；
- Target 用 Phase 更新 Card，用 Checkpoint 保存 Child，不对每个 token 写 Room；
- Target 收到 `step_limit_reached` 映射为 `failed/step_limit_reached`；`awaiting_approval` 只允许由
  `ask_user` 产生，Coordinator 将其转为 `awaiting_user` 并等待主聊天回答；找不到对应 Pending
  `ask_user` 时按 `approval_blocked` 收口。

### 7.4 Tool provider 与非交互策略

当前实现中，`GenerationHandler.generateText()` 接收 `toolProvider`，并在每个 LLM step 开始时重建一次工具集；
该 step 返回的整批 ToolCall 使用这份工具快照按 ordinal 串行执行。Target 重建时复用调用开始时的
Assistant 配置快照和最新 Settings/资源状态，并复用整轮生成的 TTS 顺序播放状态。调用开始时的
`SubAssistantRunSpec` 已稳定 Target/caller 模型优先级和模型执行参数；配置切换只影响下一次调用，只有本次
快照实际使用的模型被删除、禁用或不再是 Chat Model 时才停止。Target 删除或禁用、caller delegation
关闭和访问撤销也由运行期监听器立即取消。

完整的“每个 ToolCall 真正执行前再次解析”、稳定 capability key 和覆盖全部能力的 `AssistantRunSpec` 交集
尚未落地；因此 LocalToolOption 等 Assistant 配置变更从下一次 `assistant_call` 生效，而不是改变当前 step。
下面关于 `ResolvedTool` 与完整 `AssistantRunSpec` 的内容是后续安全加固目标；不要与已经落地、只负责模型
来源与生成参数的 `SubAssistantRunSpec` 混为一谈。

稳定 key 不能临时从最终注册名反推。`GenerationToolSetFactory` 在 App 层先产生类似
`ResolvedTool(capabilityKey, tool)` 的内部注册项，完成冲突处理、RunSpec 交集和 Run policy 过滤后，才把
`List<Tool>` 交给 `GenerationHandler`。这样不把 Workspace/MCP 等 App 类型放进 `ai` 模块，也能保证同名
资源替换不会绕过调用开始时的最大权限。

后续完整 `AssistantRunSpec` 还应包含：

- Target identity、System Prompt、message template；
- 已解析的 Model/Provider、temperature/topP/maxTokens/reasoning、stream/context limit，以及 Assistant 级
  custom headers/bodies；
- Prompt Injection、Transformer 和 Memory mode；
- 本次允许的最大 capability 集合。集合使用稳定 capability key（内建 Search/Conversation capability、
  LocalToolOption、MCP server ID + tool name、Workspace ID + tool name、Skill ID），不能只按最终注册名比较，
  避免同名资源替换造成提权。

运行中重新解析工具时只能做：

```text
call-start maximum capabilities
∩ latest Target configuration
∩ currently available resources
∩ Target Run policy
```

因此用户撤销权限会尽快生效，但运行中新增的工具、MCP、Skill 或 Workspace 权限不会让当前调用
静默提权，只从下一次 `assistant_call` 生效。Target identity、模型和生成语义也从下一次调用生效。

Coordinator 在存在 active run 时监听最新 Settings，并用 `SubAssistantAccessPolicy` 重算 caller/Target
关系；Target 删除/禁用、caller delegation 关闭、访问撤销或 RunSpec Model/Provider 被禁用时，立即以对应
reason 取消 Child Job。执行前和 final 前的同步校验仍保留，防止 Flow 通知与具体操作之间的竞态；普通工具
开关变化只让后续 ToolCall 拒绝或消失，不无条件取消整轮 Target。监听器是当前 assistant_call
`coroutineScope` 内的结构化 sibling，并在 run `finally` 取消；不能用常驻 `AppScope.launch` 为每次调用遗留
collector。

Target 与普通聊天共用同一 Transformer 顺序，但使用调用级快照；Target 未绑定模型时只有模型及直接相关的
生成参数来自 caller，Target 身份、System Prompt、工具、记忆和权限仍保持独立：

- 执行 Target 自己的 Time Reminder、Assistant 级 Prompt Injection、Placeholder、Document/OCR、
  message template 和 Workspace Reminder；
- 不继承 Master 的 conversation System Prompt、conversation mode IDs 或 workspace cwd；Child 没有会话级
  override，Prompt Injection 明确使用 Target 的 `modeInjectionIds`；
- message template 必须从 `AssistantRunSpec` 的字符串快照编译，不能在后续 step 通过 SettingsStore
  重新读取后改变历史请求渲染；
- 输出继续复用 ThinkTag、Base64 文件落盘和 Target Regex 管道；USER regex 在写入 Child request 前执行；
- Memory mode 与初始注入列表按调用开始快照；Memory Tool 仍由 `GenerationHandler` 构建，但 edit/delete
  callback 必须绑定该快照的 owner namespace，并由 App 层传入 `isStillAllowed` 检查，不能让通用
  `GenerationHandler` 反向依赖 SettingsStore。执行前若最新配置已关闭 Memory 或已切换 local/global mode，
  当前 run 的 Memory Tool 立即返回 `tool_not_permitted`，不能在运行中切换 namespace；运行中新增 Memory
  权限也不加入本次 run。

Target Run policy：

- 永久过滤 `AssistantManagement`、`AssistantDelegation`；
- 保留 `ask_user`，但只有 Coordinator 可以把它传导到主聊天；metadata 保存 interaction ID、Child
  message ID、Tool ordinal 与有界 input，回答时必须同时匹配 run/interaction/locator；
- 同一批有多个 `ask_user` 时按原 ordinal 逐个显示与回答；同批其他工具在全部问题回答前不执行；
- 除 `ask_user` 外，某次具体 ToolCall 若 `needsApproval(args) == true`，直接写入
  `{"error":"tool_not_permitted","reason":"approval_required"}`，绝不进入 Pending；
- 执行前再次验证 caller/Target 仍存在、Target 仍属于子助手类别、仍满足有效访问公式，并按 capability
  key 确认工具仍启用且资源可用；已经撤销的调用返回稳定 `tool_not_permitted`，不执行旧 Tool 实例；
- 单个工具拒绝/失败作为普通 Target Tool Result，由 Target 决定如何恢复；
- Target 被删除或关闭子助手类别时分别使用 `target_removed`、`target_disabled`；caller 被删除、关闭
  delegation，或因允许列表/全局可见变化失去访问权时统一使用 `target_access_revoked`。这些变化在当前
  不可分割操作结束后的下一个 loop 边界 stopped；在接受模型 final 并提交 completed 前也必须执行这次
  校验，不能把撤权期间到达的迟到 final 记为成功。RunSpec 中的 Model/Provider 被删除或禁用时，显式
  Target 模型使用 `target_model_unavailable`，caller fallback 模型使用 `caller_model_unavailable`，均不得
  中途 fallback 到另一模型。用户停止才使用 `user_cancelled`。
- 等待回答时取消、删除 Target 或撤权沿用上述 stopped 语义并解除等待；进程重启不重放问题或工具副作用，
  Master metadata 清除交互入口并收口为 `stopped/app_restarted`，Child 中已持久化的 Pending 仅作为只读历史。

### 7.5 Target TTS 播放语义

Target 配置了 `LocalToolOption.Tts` 时保留 `text_to_speech`；Target Run policy 不过滤它。该工具仍是
现有的后台副作用：调用成功即返回，音频通过全局 TTS Controller 继续播放，不作为
`assistant_call` 的文本或非文本结果返回。Child 完成不会触发普通会话的 TTS 自动播放。

每轮 **Master Generation**（即一个用户 turn）创建一个 **turn-level** `TtsToolPlaybackContext`，
包含本轮稳定的 playback session ID、Assistant ID、名称快照、来源类型（normal/sub-assistant）
以及 `TtsToolPlaybackState`。该 context 在整轮 turn 内被 Master 和所有 Target 共享：

- Master 的 `text_to_speech` 工具直接使用该 context；
- `assistant_call` 执行时将该 context 传入 `SubAssistantCoordinator.executeCall()`；
- Coordinator 为每个 Target 创建一个派生 context，**复用同一 `sessionId` 和同一
  `TtsToolPlaybackState`**，但替换 `assistantId`、`assistantName` 和 `sourceType`；
- Target 的 `toolProvider` 在不同 LLM step 重建 Tool 时复用这个派生 context；
- 它不能放入单例 `LocalTools`，也不能随每步 Tool 列表一起重建。

共享 `sessionId` 保证 `computeEffectiveFlush` 不会因 Master ↔ Target 或 Target ↔ Target
来源切换而强制 flush。共享 `TtsToolPlaybackState` 保证 `hasSpoken` 标记在整轮 turn 内全局递增，
首次 TTS 调用（无论来自 Master 还是某个 Target） flush 建立队列，后续所有调用追加。

`ttsToolSequentialPlayback` 继续是唯一开关，不增加子助手专用配置：

| 条件 | 实际播放 |
|------|----------|
| 同一 turn，开关开启，首次 TTS 调用（Master 或任意 Target） | `flush`，中断此前播放并建立本轮队列 |
| 同一 turn，开关开启，后续 TTS 调用（不论来源是否切换） | `append`，按调用顺序追加 |
| 同一 turn，开关关闭 | 每次都 `flush`，后一次打断前一次 |
| turn 切换（`sessionId` 变化） | 无论开关状态都 `flush`，禁止不同 turn 共用队列 |
| 手动朗读/自动播放（无 session） | 沿用现有 flush 行为，清除子助手头像 |

开关在每次工具执行时读取；运行中关闭后，下一次调用立即转为中断播放。不同主会话并发朗读时，
各自拥有独立 `sessionId`，`computeEffectiveFlush` 检测到 `sessionId` 变化时强制 flush，
避免不同主会话的声音错误拼入同一队列。

`AppEvent.Speak` 增加可选的 `TtsPlaybackSource`；TTS 工具调用始终携带上述 session 和 Assistant
来源，手动朗读与自动播放保持现有默认行为。App 层保存瞬态 `activeSource`，并按
`requestedFlush || activeSession != incomingSession` 计算最终 `flush`。来源信息只服务播放仲裁和 UI，
不持久化到 Conversation，也不进入模型 Tool Result。

来源判定与 `TtsController.speak()` 必须在同一个串行入口完成，不能由多个 Event collector 各自先读后写。
无 session 的手动朗读/自动播放视为新来源，沿用其现有 flush 行为并清除子助手头像。显式停止、队列自然
播放完毕、Provider 切换、播放错误和 Controller dispose 时都清空 `activeSource`，避免控制条在无音频时
继续显示旧 Target。

**chunk 间 Ended 抑制**：`AudioPlayer`（ExoPlayer 封装）在每个 audio chunk 播完后发射
`PlaybackStatus.Ended`。如果 `TtsController` 直接传播此状态，`CustomTtsStateImpl` 会在 chunk 之间
清空 `_activeSource`，导致后续 TTS 调用看到 `currentSource = null` → `effectiveFlush = true` →
flush → 队列中未播放的 chunk 被丢弃。`TtsController` 的 `audio.playbackState` collector 必须在
`queue.isNotEmpty()` 时将 `Ended` 转为 `Playing`，只有当队列为空时才传播 `Ended`（表示整个队列
播放完毕）。

**控制条头像**：仅当 `activeSource` 为子助手且该助手开启“使用助手头像”时，在播放按钮前显示 Target
真实头像（内联渲染，不使用 `UIAvatar`，避免 `FloatingWindow` 缺少 `LocalToaster`）。主助手播放不显示
头像。Target 已删除或解析失败时回退 `Avatar.Dummy`。播放结束（`isSpeaking = false`）后控制条自动隐藏。

`assistant_call` 完成或生成被停止时，不清除已经提交给 TTS Controller 的音频，保持现有“后台播放”
语义；用户通过全局 TTS 控制条停止。新的播放来源会按上述规则中断旧队列，App 重启后不恢复音频。

## 8. 调用与 Child lineage

### 8.1 Readiness

`assistant_call` 分成“Target preflight”和“lineage lease”。当前 lease key 为
`Master Conversation ID + Target ID`：同一 Master 内对同一 Target 的调用必须形成确定的串行 lineage，
不同 Master 仍可并行调用同一 Target。

Preflight 按顺序验证：

```text
Caller 存在且 AssistantDelegation 仍启用
Target ID 可解析且存在
Target != caller
allowAsSubAssistant == true
Target.id in caller.allowedSubAssistantIds || Target.isSubAssistantGloballyVisible
Target.chatModelId != null → 显式模型可解析到 enabled Provider 下的 CHAT Model
Target.chatModelId == null → caller 当前有效 Chat Model 可解析
```

阻断 reason 保持稳定且可操作：

```text
tool_not_permitted
assistant_not_found
target_not_allowed
target_model_unavailable
caller_model_unavailable
target_busy
```

`target_not_allowed` 同时覆盖 caller 自身、“不是子助手”和“不满足有效访问公式”，避免通过手工构造 ID
绕过 Catalog。
运行开始后失去访问权则使用终态 `stopped/target_access_revoked`。

阻断矩阵以“不能安全、明确地继续”为准，不能把可恢复的配置缺省变成硬阻断：

| 场景 | 处理 | 理由 |
|------|------|------|
| 参数 JSON/UUID 无效，或 request 为空白 | 参数错误，不创建 Child | 没有可执行的确定任务 |
| caller 未启用 delegation、Target 缺失/是 caller/未开放/无访问权 | preflight 阻断 | 权限与身份边界，不能 fallback 到其他 Assistant |
| Target 显式模型有效 | 使用 Target 模型与生成参数 | 尊重 Target 配置 |
| Target 未绑定模型，caller 模型有效 | 构造 caller fallback RunSpec 并执行 | 缺省可由当前调用上下文明确补全，不应要求手工重复配置 |
| Target 显式模型失效 | `target_model_unavailable` | 显式配置优先，静默换模型会改变语义和费用边界 |
| Target 未绑定且 caller 无有效模型 | `caller_model_unavailable` | 没有可确定的运行模型，创建 Child 只会留下无效数据 |
| description 为空但已知 ID、权限与模型均有效 | 允许调用 | description 服务于目录路由，不是执行能力；配置 UI 仍要求填写后才能新开启类别 |
| 同一 Master/Target 已有运行 | `target_busy` | 保证共享 Child lineage 的历史和写入顺序唯一 |
| 不同 Master 调用同一 Target | 允许并行 | Child、Session 与 lineage 独立 |

模型或权限在 preflight 后变化时，写 Child 前重验；运行中由 Settings watcher 停止；接受 final 前再同步重验。
三处复用同一组资格与 RunSpec 快照规则：写入前仍返回可操作的 preflight reason，运行中和 final 前返回
stopped reason，避免监听调度竞态把撤权后的迟到结果提交为 completed。

不能使用 `Settings.getConversationAssistant()` 的“删除后回退当前 Assistant”行为。指定 Target
缺失必须失败，绝不能悄悄换成另一个 Assistant。

description 仍是启用子助手类别和 Catalog 路由质量的配置门槛，但不是已知 ID 显式调用的运行时硬阻断；
历史配置即使缺少 description，也不应在权限、模型与任务都有效时拒绝执行。

Preflight 后按 `Master ID + Target ID` 原子获取 run lease；同一 Master/Target 已有 active Job 时返回
`target_busy`，不同 Master 可以并行。取得 lease 后再创建/克隆持久化数据或追加 request。写入 request 前
再做一次访问校验，关闭
preflight 与持久化之间的竞态窗口。Coordinator 的 Target 索引只引用 Registry 中同一个结构化 Job，用于
删除/撤权时定位 run，不是第二套 Job 所有权。

### 8.2 分支安全的 lineage 选择

在 Master 当前选中的 `currentMessages` 中，以 `(messageIndex, partIndex)` 为顺序，从当前 Tool part 的
前一个位置逆序查找同一 Target 最近的 `assistant_call`。Provider 在同一 ASSISTANT message 返回多个
ToolCall 时，不能扫描到当前 part 后方尚未执行的调用，也不能只按 message 粗略逆序。

候选 previous metadata 必须同时满足：state 已终态、Target ID 一致、Child 可加载、
`child.parentConversationId == currentMasterId`、`child.assistantId == targetId`、request node 存在且当前
选中消息角色为 USER。然后按以下规则决定：

1. 没有前序调用：创建新 Child；
2. 前序调用指向的 run 正好是该 Child 的尾部：继续使用该 Child；
3. Child 在此前序 run 之后还有后续请求：说明旧分支已向前发展，只克隆从 Child 起点到此前序 run
   终点的前缀，再追加本次 request；严禁复制源 Child 的后续分支内容；
4. 前序 metadata 缺失、Child 已损坏或不属于当前 Master：创建新 Child，并把旧链接作为可恢复
   警告处理，不能读取不可信的其他会话历史；新 lineage 的 `previous_run_id` 置空，不伪造连续关系。

“run 终点”定义为该 request node 后、下一个当前选中 USER node 之前的最后一个节点。克隆时只复制该
闭区间内需要保留的 MessageNode，所有新 Child/MessageNode ID 重新生成；消息 ID 与 Provider opaque
metadata 保持原样，保证工具调用/结果协议配对不被破坏。

```text
Master branch A: call B(run-1) ─ call B(run-2)     → Child B-1: run-1, run-2
                              └ 切回 run-1 后再调用
Master branch B: call B(run-1) ─ call B(run-3)     → Child B-2: clone(run-1), run-3
```

这个规则比固定“一主会话一 Target 一 Child”多一个 clone 分支，但与现有 MessageNode 分支模型一致，
不会让新分支读取已经切走的 Target 工作历史。

### 8.3 完整调用顺序

```text
Master GenerationHandler 执行 assistant_call
  → Target preflight
  → 构造本次 SubAssistantRunSpec；显式 Target 模型优先，否则继承 caller 模型执行配置
  → 按当前 Tool part 位置解析 previous run 与 Child lineage
  → 获取 Master + Target lineage lease
  → 从最新 Settings 再校验权限与 RunSpec 模型可用性
  → 捕获当前 Target 身份、能力与 Transformer 配置快照
  → 必要时创建/前缀克隆 Child，再预处理、追加并保存 Target USER request，得到 childTaskNodeId
  → 回写 Master tool metadata 的 run/child link，并 checkpoint Master
  → Target GenerationHandler
       → Child Messages/Phase 实时更新 Session
       → step/tool-result 边界 checkpoint Child
       → preview/phase 经 RunStateReducer 与 ToolExecutionContext 回写 Master 内存态
  → 提取本次 run 的可见 final answer（忽略 TTS 等副作用工具，空步回退）
  → 写 terminal metadata，返回 Tool Result
  → GenerationHandler 合并 output 后 checkpoint Child 与 Master
  → 释放 lineage lease
  → Master 继续当前 Tool Loop
```

Target request 会执行 Target 的 USER 正则预处理和 message template；Target preset messages 只在新
Child 创建时加入一次。Master 的 system prompt、conversation override、mode injection、workspace cwd
和普通历史均不继承。

### 8.4 Fork、再生成与删除消息

这些现有操作必须纳入实现，不能只覆盖直线聊天：

- Fork、删除消息或再生成在修改 tree 前必须先停止并等待当前 Master generation，完成专用 terminal
  finalizer；不复制或裁剪仍为 starting/running 的调用。若加载到遗留非终态，先走 recovery 再执行 tree
  操作；
- Fork Master 时，按 Fork 后实际保留的 message variants 收集调用引用；同一源 Child 只克隆到“最后一个
  被保留 run”的终点，不复制其后内容。为新 Master 重新生成 Child/MessageNode ID，并 remap 所有
  `childConversationId`、`childTaskNodeId`、`runId`；`previousRunId` 只在其前序 run 也被复制时 remap，
  否则置空；
- Child 内引用的本地文件沿用现有 fork 文件复制策略；
- 再生成形成的新分支由 lineage resolver 按前序 run 复用或 clone；旧分支仍可切回；
- 删除一个包含 `assistant_call` 的 message variant 后，删除已不再被 Master 任意分支引用的 Child
  lineage；仍被其他 variant 引用的 lineage 保留。若被删 run 之后已没有任何保留引用，则把 Child 的
  无引用尾部截断到最后一个被引用 run 并清理尾部文件；若后续 run 仍被引用，中间历史作为其真实上下文
  保留，不能抽掉后伪造执行历史；
- 直接 Fork/删除逻辑下沉到 Conversation tree repository/service，禁止只复制 Master 行而保留跨
  Master 的 Child 指针。

## 9. 持久化、删除与恢复

### 9.1 Checkpoint

Child 的持久化边界：

```text
USER request 写入后
完整 LLM step 完成后
每个 Tool Result 完成后
生成正常结束或失败
```

Master collector 必须在 `TOOL_STATE_CHANGED` 保存 `assistant_call` start link，并在
`TOOL_RESULT_COMPLETED` 保存正常 terminal metadata + Tool Result。运行 preview 只节流回写内存状态，
不随每次更新写数据库；这样详情页可实时观察，又不会按 token 重写 MessageNode JSON。取消和进程恢复
走后述 durable finalizer，不依赖正常 Flow checkpoint。

具体提交规则是：start link 调用 `reportMetadata(checkpoint = true)`；phase/preview 和正常 terminal snapshot
只用 `checkpoint = false` 更新内存；contextual execute 返回后，`GenerationHandler` 在同一次 Messages 更新中
合并 terminal output，再发 `TOOL_RESULT_COMPLETED`。不能先把 completed metadata 单独落库，再留下没有 Tool
Result 的协议半状态。取消/恢复例外地由专用 finalizer 一次写入二者。

开始顺序采用“先保存 Child request，再 checkpoint Master link”。极短窗口内异常可能留下未引用 Child；
recovery 会清理未被任何 Master metadata 引用的 orphan，不自动重放 request。
Provider 或 Tool 异常退出时，Coordinator 先保存 Child Session 中已经产生的部分内容，再返回
`failed` Tool Result；不能只更新 Master 状态而丢失详情证据。

### 9.2 普通入口过滤

所有面向普通用户会话的查询必须显式加 `parent_conversation_id IS NULL`，包括：

- Assistant 会话列表、分页、Recent Chats、文件夹和 pinned；
- 标题搜索与全局会话搜索；
- FTS 建索引、重建索引与消息搜索；
- 普通会话数、用户可见消息数和每日活跃统计；
- 普通标题/建议生成、通知、声音、TTS 自动播放和分享入口。

MessageNode 统计不能只扫描 `message_node`；必须 join `conversationentity` 并限制
`parent_conversation_id IS NULL` 后计算用户可见消息数和每日活跃，否则隐藏的 Child step 会被误算成用户
消息。总 input/output/cached Token 表示实际 Provider 用量，仍同时汇总顶层与 Child usage，不能为了隐藏
会话而低报成本；同一个统计查询需要用明确的条件聚合或拆成两个 DAO 查询，不能让 `totalMessages` 与
Token 共用错误过滤条件。FTS rebuild 只从顶层会话 ID 开始，Child 从写入阶段就不建索引。

Child 仍正常持久化完整 MessageNode、usage、reasoning 与 Tool output，只通过调用卡片详情页访问。

### 9.3 删除

- 删除 Master Conversation：Repository 先收集 Master/Child 的文件与索引引用，在同一 Room 事务中删除
  全部 Child、MessageNode、Favorite 和 Master，再做可重试的 FTS/文件清理；没有自引用外键时也不能
  逐个事务删除后留下半棵树；
- 删除 Target Assistant：先在一次 Settings 原子变换中移除 Target、清理所有
  `allowedSubAssistantIds`，并在 Target 恰好是全局当前选择时切换到仍存在的普通 Assistant 或首个可用
  Assistant；同一变换写入内部 durable cleanup tombstone，至少保存 Assistant ID 与待检查的头像/背景 URI。
  这一步是权限撤销的提交点，新调用从此失败；
- Settings 提交后通过唯一 Session Registry 取消并等待该 Assistant 的普通顶层 generation，以及其作为
  Target 或 caller 参与的活跃 Child runs，再幂等删除普通顶层 Conversations、Local Memory 和助手文件。
  作为 Target 删除的 run 使用 `target_removed`，作为 caller 删除的 run 使用 `target_access_revoked`，不能
  统一误写为 `user_cancelled`。其他 Master 历史中以该 Assistant 为 Target 的 Child 保留，保证既有卡片
  详情可读；该 Assistant 自己的普通顶层 Conversation 被删时，其作为 Master 拥有的 Child tree 随父会话
  一并删除。头像/背景 URI 只有在最新 Settings 中已无其他 Assistant 引用时才删除，避免克隆或复用资源被误删。
  文件删除仅处理 FilesManager 确认归 App 所有的本地 URI，不对网络 URL 或外部未托管 URI 发起删除。
  Workspace、MCP、Skill、Provider 等共享配置不随 Assistant 一并删除，只解除已经随 Assistant 配置移除的引用；
  全部清理成功后移除 tombstone；失败不回滚已经提交的权限撤销，当前调用标记 `cleanup_pending`，下次启动
  继续幂等重试；每次消费前重新确认该 Assistant ID 仍不存在，若备份恢复等流程已重新引入同 ID，则把
  tombstone 视为过期并停止删除。若取消等待超时，本次不得先删仍可能被活跃 run 使用的数据，保留
  tombstone 后退出；
- DataStore、Room 和文件系统不存在跨存储事务，文档和实现都不能声称上述清理“整体原子”；
- 删除 Assistant 的 UI 与 `assistant_manage` 共用 `AssistantManagementService`；Target 删除后卡片使用
  name snapshot 和默认头像，详情仍可读，设置入口改为“助手已删除”。

### 9.4 取消与 App 重启

Target Job 必须是 Master Generation Job 的结构化子协程：

```text
stop Master Job
  → cancel assistant_call
  → cancel Target Provider/current Tool
  → Coordinator 在有界 NonCancellable 区域保存 Child 部分内容并清理 interrupted parts
  → 按精确 Tool locator 直接 finalize Master Session/Repository
  → 同时写 stopped metadata 与 stopped Tool Result
  → rethrow CancellationException
```

Target 必须通过当前 Master coroutine 下的 `coroutineScope/async + await` 运行，禁止 `AppScope.launch`
后再手工等待。通用 `GenerationHandler` 不吞 CancellationException；Coordinator 只在 await Child 的
业务边界区分两种来源：

- Master Job 已取消：这是用户停止或上层取消，durable finalize 后继续抛出，Master loop 不恢复；
- Master 仍 active，但 Child 被 run registry 以 `target_removed/target_disabled/target_access_revoked`
  停止：收口 Child 后返回 stopped Tool Result，Master 可以根据结果继续。

已经取消的 Master Flow 不保证还能安全 emit，因此第一种路径使用已知的
`masterConversationId + messageId + toolOrdinal + runId` 直接定位 Master Session，在
`withContext(NonCancellable) { withTimeout(...) { ... } }` 形式的有界区域同时写入 `stopped` metadata 与协议
有效的 Tool Result，并持久化一次；没有更具体上层原因时使用
`user_cancelled`。随后重新抛出取消。通用
`finishInterruptedTools` 只能作为未知工具兜底，不能覆盖这个已完成的专用终态。

durable finalizer、正常 terminal 和 recovery 共用同一个幂等提交入口：重新读取最新 Tool，只有 locator、
run ID 一致且仍为非终态时才推进；已经 completed/failed/stopped/unavailable 时直接返回原终态。这样迟到的
phase、重复 stop 或启动恢复不会覆盖更早已经提交的正确结果。

App 启动或 Master/Child 首次加载时执行 recovery：

- 扫描所有顶层 Master 的全部 MessageNode variants 和每个 Tool part，以 run ID 去重；不能只扫描
  `currentMessages`，否则切走分支中的 running metadata 和 Child 引用会被漏掉；同一 Master 内重复 run ID
  或 locator/link 冲突按损坏数据处理，不猜测目标；
- 先读取最新 Settings 与 pending deletion ID，再收口 starting/running。reason 按确定性优先：Target 已缺失
  或位于 tombstone 用 `target_removed`；Target 类别已关闭用 `target_disabled`；caller 缺失/被删除、delegation
  已关闭或访问关系已撤销用 `target_access_revoked`；Target 当前已无法解析可用 Model/Provider 时用
  `target_model_unavailable`；配置仍有效但所需 Child/link 缺失用 `child_missing`；其余配置与链接均有效但
  没有 active Job 才用 `app_restarted`。每种情况都同时写 stopped Tool Result，保证 Provider transcript
  配对；
- Child 中未完成 reasoning/tool 使用现有 interrupted 清理语义并 checkpoint；
- 已完成历史缺 Child 或 link 损坏时只禁用详情，不改写既有结果；
- 只有被有效 Master metadata 引用且 `child.parentConversationId` 与该 Master 一致的 Child 才算被引用；
  其余 orphan 删除；
- recovery terminal preview 从最新已持久化 Child 显示投影重新计算，不假设节流中的 Master preview 已经
  落库；
- stale run 全部收口后再消费 `pendingAssistantDeletions`，完成后原子移除 tombstone；
- 不自动重新调用 Provider，不重复执行可能有副作用的工具。

## 10. 主聊天 Running Card

`assistant_call` 不进入通用 `ChainOfThought` Tool step。`groupMessageParts()` 遇到该工具时先 flush
普通 reasoning/tool block，再生成独立 `SubAssistantCallBlock`，由 `SubAssistantCallCard` 渲染。

### 10.1 布局

运行中：

```text
┌────────────────────────────────────┐
│ [头像] Android 分析助手  正在使用… │
│ 分析 DeepSeek API 调用失败原因…      │
│ 已确认请求在第二个工具续轮后…        │
│ 正在检查 response.output 的顺序…    │
└────────────────────────────────────┘
```

信息层次固定为：

1. Target 头像（右下角叠圆；运行中圆边柔光扫尾）、名称、顶部右侧合并状态（运行中为 phase /
   active tool，终态为原因或状态文案）；
2. request 一行预览；
3. Target 最新可见文本的尾部 2～3 行（矮屏/Tabletop 2 行，常规 3 行），不内嵌滚动；
4. 若 Target 正在等待 `ask_user`，显示带“子助手需要你的回答”标签的独立问题区；提交后立即禁用，回答
   只投递给 metadata 中匹配的 run/interaction，不复用主助手审批回调。

不显示进度百分比、预计时间、“即将完成”或由模型主动生成的 progress 文案。
active tool 对已知内建工具使用本地化 display name，其他工具回退经过单行清理的注册名；不展示参数、输出
或服务器内部错误。

整张 Card 是一个语义明确的详情导航点击目标，不再单独占一行“查看详情”。没有创建 Child 的
unavailable 状态取消 Card 点击能力，并直接在 Card 内显示可操作原因。

Card 从 Tool input 通过 JSON decoder 读取 `request`，不能用字符串 `trim('"')` 或按 UTF-16 code unit
硬切；一行预览交给 `maxLines + ellipsis`。导航只传 `masterConversationId + runId`，Child link 由详情
ViewModel 从已持久化 metadata 解析和验证。

### 10.2 运行输出裁剪

Running Card 展示**最新输出的有界 tail**，而不是 request、reasoning 或工具原始 JSON。选择 tail 是因为
运行中最有价值的是“刚刚输出了什么”；完整过程由详情页承担。

`SubAssistantPreviewReducer` 的初始内部常量：

```text
最大缓冲：2,000 Unicode code points
首部边界扫描：200 Unicode code points
主卡可视窗口：常规 3 行，矮屏/Tabletop 2 行
进度回写节流：100ms，phase/terminal 立即 flush
```

这些是实现常量，不增加用户配置。算法：

1. 只从本次 `childTaskNodeId` 范围的 UI 显示投影中，逆序提取 Target ASSISTANT 消息的顶层
   `UIMessagePart.Text`；排除 Reasoning、Tool input/output、preset 和下一次 request；
2. 在节流采样时从各 Text part 尾部向前收集，达到缓冲上限即停止，避免每个 token 反复 join
   整段长输出；
3. 显示投影已经经过现有 visual transform；Reducer 不重复应用正则，Child 的持久化投影仍遵循
   `OutputMessageTransformer` 原有规则；
4. 把 CRLF/CR 统一为单个 `\n`，移除 NUL 和不可显示控制字符，连续空行最多保留一个空行；
5. 超限时从理论切点向后寻找优先边界：空行 → 换行 → 句末标点 → 空格；在扫描窗口内找不到
   才用 `BreakIterator.getCharacterInstance(Locale.ROOT)` 落在字符边界，并以组合字符/ZWJ emoji 用例验证；
   不能只按 UTF-16 或 code point
   硬切而拆开 surrogate、组合字符或 ZWJ emoji；
6. 首部被裁剪时加 `…\n`，不尝试补齐未完成 Markdown fence。卡片使用普通 `Text`，避免流式
   Markdown 表格、代码块或 Mermaid 在小区域反复重排；
7. preview 内容未变化时不更新 metadata revision，减少主消息重组。

卡片只展示 preview 尾部 2～3 行，用字符预算裁剪后交给 `maxLines + ellipsis`，不再内嵌滚动，也不在
主卡上做 follow/pause。完整过程与跟滚由详情页承担。Card 内不放“最新”等第二个点击按钮，保持整卡
唯一详情点击语义。主聊天列表的自动滚动与卡片分离，普通轻触进入详情。

不把 preview 作为 live region 持续朗读；无障碍只播报 running/completed/failed 等状态变化，避免
每个 chunk 打断屏幕阅读器。

### 10.3 Terminal Card

- completed：用 Target final 的首个非空段落生成最多三行静态预览；在句末边界裁剪，
  不额外调用模型总结；final 无文本但有非文本 part 时显示“已生成非文本内容”，两者都没有时显示
  “已完成，无文本输出”；
- unavailable：显示可操作原因，例如“未配置可用聊天模型”；没有 Child 时禁用详情入口；
- failed：保留最后一次 preview 并显示稳定错误文案，不显示 Exception/stack trace；
- stopped：保留最后 preview，显示“已停止”；app restart 可显示“应用重启，任务未自动恢复”。

只要 Child 与本次 request 起点仍存在，completed、failed 和 stopped 都可打开详情查看完整或部分过程；
详情可用性不由终态类型推断。

历史 metadata 缺失时回退通用 Tool renderer，不能因解析失败让整条 ChatMessage 崩溃。

### 10.4 TTS 控制条来源呈现

全局 `TTSController` 保持现有按钮和折叠行为，只增加来源感知：

- 仅 `activeSource` 为子助手且开启“使用助手头像”时，在播放/暂停按钮前显示 Target 小头像；主助手不显示；
- 头像按 Assistant ID 实时解析，Target 已删除或解析失败时显示默认头像；无障碍描述使用名称快照；
- 开关关闭时不显示头像，控制条保持简洁；
- 同一 Target session 的顺序播放保持同一头像；新来源强制 `flush` 时同步切换或移除头像；
- 来源状态只存在于当前 App 进程，不写入 Tool metadata，也不从历史消息恢复。

Child 详情中的 `text_to_speech` Tool UI 继续复用现有摘要与重播按钮；用户手动点击重播属于手动朗读，
不伪装为正在运行的 Target。

## 11. Target 执行详情

点击 Card 进入独立的全屏只读路由，不使用小型弹窗或在主消息内无限展开；reasoning、Tool 和长文本需要
稳定的滚动空间。路由参数使用 `masterConversationId + runId`，ViewModel 再从 Master 的所有 message
variants 中解析对应 Tool metadata；不能只信任导航参数传入的 Child ID。解析后必须验证 Child 的 parent、
Target ID、request node 和 run metadata 相互一致，才加载本次范围。这样既能使用 name/state/reason
快照，也避免把任意普通 Conversation 当成 Child 打开。

run ID 在一个 Master 内必须唯一匹配到一个 `assistant_call` Tool part；找不到、出现多个匹配、Tool 名称
不符或 metadata schema 不受支持时统一显示“详情不可用”，不能任选第一个结果。路由解析使用同一精确
Tool locator 规则，但 locator 本身不暴露为导航参数。

本次范围起点是 metadata 的 `childTaskNodeId`，终点是 Child 中下一个**当前选中** USER message 之前或
当前尾部。起点必须是当前选中角色为 USER 的 MessageNode；损坏或不匹配时显示“详情不可用”，不回退到
整个 Child。

页面结构固定为：

1. 顶部栏：返回、Target 头像、名称快照和当前状态；Target 仍存在时提供“助手设置”入口，已删除时显示
   不可用说明；
2. 请求摘要：明确标为“请求”，显示本次完整 `request`；长内容先有界展示并允许本地展开；
3. 执行时间线：按原顺序展示 Target answer、reasoning、ToolCall/ToolResult，复用现有
   ChatMessage、Markdown 和 Tool UI；
4. 终态说明：completed 不额外重复总结；failed、stopped 使用本地化横幅说明原因，并
   保留已经产生的部分内容。

内容边界：

- 只展示本次 run，不带入 Child lineage 的更早请求，也不显示下一次请求；
- 不展示 Master 的 Tool Result JSON、Catalog、内部 UUID、metadata、Exception 或 stack trace；
- reasoning 是否显示继续遵循用户现有 `showThinkingContent`；
- 运行中收集 Master Session 中该 run 的 metadata 状态和 Child Session 的内容；两者都不存在活跃
  Session 时回退 Room snapshot。默认跟随底部；用户向上滚动后暂停跟随，并通过“最新”恢复；
- Target 名称、状态和 reason 使用 Master metadata 快照；头像与设置入口按 Target ID 实时解析，删除后
  使用默认头像且不再提供设置入口。

详情页采用明确的 `ChatInteractionPolicy.ReadOnlyChild`。不显示输入框、编辑、删除、重生成、分支切换、
收藏、分享、审批按钮、标题生成和建议回复；只允许滚动、展开/折叠、复制以及现有 TTS 摘要的本地重播。
页面不发普通会话通知、完成提示音或 TTS 自动播放；运行期间 Target 显式调用 `text_to_speech` 不受此条
限制。不允许通过传空 callback 伪装只读，避免按钮仍可见或未来误接行为。

## 12. 实现文件与文档范围

主要改动位置：

```text
ai/.../core/Tool.kt
  ToolExecutionContext、contextual execute

ai/.../ui/UIMessagePart.kt
ai/.../ui/MessageMetadata.kt
  nested sub_assistant_call metadata 的类型安全读写与 merge

app/.../data/model/Assistant.kt
app/.../data/model/Conversation.kt
app/.../data/datastore/PreferencesStore.kt
app/.../data/ai/tools/local/LocalToolOption.kt
  子助手类别、全局可见、允许列表、内部删除 tombstone、Child 标识与管理/调用工具权限项

app/.../data/db/
app/.../data/repository/ConversationRepository.kt
  Migration_3_4 additive column/indices、Child 查询、事务化 tree 删除、普通入口过滤、Fork tree

app/.../data/ai/GenerationHandler.kt
  精确 Tool locator、contextual tool、toolProvider、phase/checkpoint/finished、NonInteractive policy

app/.../service/
  ConversationSessionRegistry、AssistantManagementService、SubAssistantCoordinator

app/.../data/ai/subassistant/
  SubAssistantAccessPolicy、lineage、run-state reducer、metadata、preview

app/.../data/ai/tools/
  GenerationToolSetFactory、AssistantToolFactory、Catalog builder

app/.../data/ai/tools/local/TextToSpeechTool.kt
app/.../data/event/AppEvent.kt
app/.../ui/hooks/TTS.kt
app/.../ui/components/ui/TTSController.kt
  每轮复用的 TTS playback context、来源切换仲裁与 Target 头像

app/.../ui/components/ai/AssistantPicker.kt
app/.../ui/pages/assistant/AssistantPage.kt
app/.../ui/pages/assistant/detail/AssistantBasicPage.kt
app/.../ui/pages/assistant/detail/AssistantLocalToolPage.kt
app/.../ui/components/message/
app/.../RouteActivity.kt
  类型筛选、权限配置、独立卡片、masterConversationId + runId 只读详情路由

app/src/main/res/values*/strings.xml
  全部支持 locale 的文案
```

功能落地后同步更新：

- `docs/references/assistant-configuration.md`；
- `docs/references/chat-generation-pipeline.md`；
- `docs/references/ui-architecture.md` 与 `message-rendering-pipeline.md`；
- `docs/dev/changelog.md`、`app/build.gradle.kts` 的版本信息。

## 13. 测试设计

测试不是实现后的补充项；每个阶段必须随生产代码一并提交对应覆盖。

### 13.1 JVM 单元测试

建议新增或扩展以下测试类：

| 测试类 | 关键用例 |
|--------|----------|
| `SubAssistantPreviewReducerTest` | 短文本、超限 tail、各级语义边界、surrogate/组合字符/ZWJ emoji、CRLF/控制字符、reasoning/tool 排除、使用视觉投影且不重复变换、内容不变不 emit、terminal head preview |
| `AssistantConfigCompatibilityTest` | 历史 JSON 缺字段时普通类别、非全局可见、允许列表为空；关闭类别清理授权；克隆保留类别但重置全局与允许列表 |
| `SubAssistantAccessPolicyTest` | 默认空、显式允许、全局可见、两者并集、caller/普通 Assistant/失效 ID 排除、稳定 Settings 顺序 |
| `AssistantCatalogPromptTest` | delegation/management/both 使用同一有效集合与 header/rows、无重复 Catalog、空列表、JSON 与 XML-like delimiter 转义、恶意 name/description 只能作为数据 |
| `AssistantManagementServiceTest` | CREATE 私有子助手并原子授权 creator、审批后在原子块内重验权限、update 白名单、Settings 持久化失败不发布成功、删除先撤权/停 run 后幂等清理、共享资源不误删、tombstone 重启重试与同 ID 恢复保护、并发更新不丢失 |
| `AssistantMemoryListToolTest` / `MemoryRepositoryOwnershipTest` | local 返回稳定 id 与 header/rows、global/disabled 空表、显式/全局授权、越权拒绝、edit/delete 同时校验 owner + id、绝不读取或修改其他 namespace |
| `SubAssistantCallMetadataTest` / `SubAssistantRunStateReducerTest` | 新旧 metadata 解码、phase/preview 连续更新不丢字段、状态单向转换、terminal 不被迟到进度覆盖、nested merge 保留 Provider opaque metadata |
| `SubAssistantLineageResolverTest` | 同 message 多 ToolCall 的 part 顺序、exact parent/target/request 校验、tail 复用、分支只克隆 run 前缀、删除引用后删除整条 lineage 或裁掉无引用尾部、损坏链接断开 previous、Fork remap |
| `SubAssistantRunPolicyTest` | preflight 与 Master/Target lease 判忙、显式/全局访问、显式 Target 模型优先、未绑定时继承 caller 模型执行参数、两侧均无模型时明确阻断、写入前访问重验、Assistant Tools 过滤、`ask_user` 保留、其余审批工具直接 denial |
| `GenerationHandlerFlowTest` / `GenerationHandlerLogicTest` / `ChatServiceToolApprovalTest` | 可取消子 Job 跨协程 metadata 发送不违反 Flow invariant；批量审批屏障、全部决策后按 ordinal 串行执行、重复/空 Provider ID 仍按 messageId + toolOrdinal 精确更新、过期 locator 拒绝、Target 只允许 `ask_user` 进入 Pending |
| `SubAssistantAskUserBridgeTest` | Child Pending 按 messageId + toolOrdinal 精确回写、错误 message/非 `ask_user` 拒绝、重启后未完成 Child Tool 转为协议完整的只读历史 |
| `ChainOfThoughtSelectionTest` | 折叠时尾部摘要仍保留全部 Pending Tool step，隐藏计数不包含被固定展示的审批卡 |
| `ConversationSessionRegistryTest` | 单 Session、持久化 Child 显式 open、空 Session 不遮蔽 Room、活跃内存态不被旧快照覆盖、Job/reference 清理 |
| `ConversationDAOIntegrationTest`（instrumentation） | 用真实 Room/SQLite 验证 list/paging/recent/search/pinned/folder/count 只返回顶层，Child 仍可按受控查询读取 |
| `TtsToolPlaybackStateTest` / `TtsPlaybackSourceTest` | Target 跨 step 复用状态；顺序开关开/关；不同 session 强制 flush；stop/idle/error/provider switch 清空来源；来源不持久化 |

Catalog 和 preview 逻辑应提取为纯函数，避免 JVM 测试复制生产算法。Provider 侧现有消息测试增加断言：
`sub_assistant_call` metadata 不出现在 OpenAI Chat/Responses、Claude 和 Gemini 请求体，同时各协议自身
opaque metadata 仍能无损回放。

### 13.2 数据库仪器测试

新增 `Migration_3_4_Test` 与 `ConversationDAOIntegrationTest`，覆盖：

- v1/v2/v3 均存在到 v4 的完整迁移路径；v3 通过 additive column/indices 迁移后
  `parentConversationId == null` 且 Conversation/MessageNode 完整，
  parent 与 assistant 索引名均与 Room schema 一致；
- v1-v3 历史导出 schema 仍保留，MigrationTest 能从真实 v3 schema 创建数据库，而不是手写近似旧表；
- schema 只存在于完整数据库类名对应的 canonical 目录，没有短名重复副本；
- 可插入多个相同 parent/target 的 Child lineage；
- Repository 在单个 Room 事务中删除 Master tree 与 MessageNode，失败不会留下半棵树；
- 删除 Target Assistant 的业务清理保留历史 Child；
- 普通 DAO/分页/Recent/pinned/search 排除 Child；
- FTS rebuild、消息数与每日活跃不包含 Child，总 Token 准确包含 Child usage；
- Fork tree 只复制最后保留 run 之前的 Child 前缀、重新生成主键并正确 remap metadata；
- 数据损坏/缺失 Child 的 recovery 不崩溃。

### 13.3 执行链集成测试

用 Fake Provider、Fake Tool 和 Test Dispatcher 跑真实 `GenerationHandler + SubAssistantCoordinator`：

- Master 调用 Target，Target 流式输出、使用工具、给出 final，Master 只收到 terminal content；
- 显式允许与全局可见 Target 均可调用；普通、越权或手工构造的 Target ID 被拒绝；
- `assistant_manage(CREATE)` 后新 Target 立即进入 creator 的管理与调用 Catalog；关闭全局可见但保留显式
  允许时仍可访问，移除最后一项授权后当前 run 在安全边界 stopped；
- 第二次调用在同一分支复用 Child，Target 能看到前一请求但看不到 Master 普通历史；
- 切回旧 Master 分支后调用会 clone lineage，不看到被切走分支的后续请求；
- 运行中触发 Fork/删除/再生成时先完成 stopped finalizer，不复制 starting/running metadata；
- Target `ask_user` 在主聊天子助手卡片中显示并可回答，回答后恢复同一 Child；取消、撤权、删除和重启
  不会串到主助手问题或错误 run；其他需审批 Tool 收到 `tool_not_permitted`，不会出现隐藏 Pending；
- 同一 Provider step 返回多个普通/子助手 ToolCall 时，所有 Pending 始终可见；本批在所有审批/回答完成
  前不产生自动工具副作用，完成后按原 ordinal 串行执行并保持 Tool Result 配对；
- 用户 stop 从 Master 级联到 Provider/Tool，Coordinator 直接持久化 stopped metadata + Tool Result、
  CancellationException 继续上抛且 Master loop 不继续；
- model/provider error、step limit、Target 删除、子助手类别关闭和资源撤销映射到正确终态；
- 模拟 start checkpoint 后进程丢失 active Job，recovery 同时写 app_restarted metadata/output 且不重复副作用；
- recovery 扫描未选中的 message variants，重复 run ID 拒绝猜测，并消费待删除 Assistant tombstone；
- 多个 Master 并行调用同一 Target 相互隔离，同一 lineage 重入返回 target_busy；
- Target 启用 TTS 时可正常调用；同一 Target run 的多次调用遵循顺序开关，Master/Target 来源切换不会
  拼接队列，`assistant_call` 只收到最终文本而不收到音频载荷。

### 13.4 Compose 与人工验收

Compose instrumentation：

- Assistant Picker 与设置页默认隐藏子助手，“显示子助手”与 Tag/搜索组合正确；当前直接使用子助手时
  Picker 默认能看到当前项；不存在普通 Assistant 时自动显示全部；
- 普通 Assistant 无额外 Tag，子助手显示类别 Tag，设置页对全局可见子助手再显示“全局”Tag；
- 允许列表默认空、工具创建后自动选中、全局 Target 未选中也生效，开关关闭后列表仍保留；
- Running Card 显示 Target/request/preview 尾部，phase 合并到顶栏；
- Target `ask_user` 使用独立标签和容器显示，提交按钮防重复；主助手自己的提问仍使用普通 Tool step；
- 主卡不内嵌滚动；详情页默认跟随底部，用户上滚后暂停，点“最新”恢复；普通轻触打开详情；
- completed/unavailable/failed/stopped 使用正确 semantics 和本地化文案；
- 整张 Card 使用单一详情点击语义；详情按“请求摘要—执行时间线—终态说明”展示，缺 Child 时入口禁用，
  更早/后续请求和内部 UUID/Exception/JSON 不泄露；
- 详情通过 masterConversationId + runId 解析并验证 parent/Target/request；伪造、过期或指向普通会话的参数
  只显示不可用，不打开其他 Conversation；
- description、子助手类别、全局可见和管理/调用 Tool group 在对应页面可发现，空描述校验可访问；
- read-only child 不显示输入、编辑、审批和分支操作，只保留本地展开、复制与 TTS 重播；
- Target TTS 播放时控制条显示 Target 头像；normal Assistant、自动播放和手动朗读保持现有简洁样式；
  顺序播放、来源切换和停止按钮的状态与实际队列一致。

人工验收至少覆盖窄屏、宽屏、深色模式、全部支持的 locale、超长中英文/emoji/Markdown/代码输出、
屏幕阅读器、运行中切后台、强杀重启、删除 Target、Master Fork 与分支切换，以及 Target TTS
顺序开关开启/关闭、Master 与 Target 交替朗读；同时覆盖默认列表、显示全部、空允许列表、显式允许、
全局可见和运行中撤权。

### 13.5 合并前验证门槛

按顺序完成：

```text
gradlew.bat test --no-parallel --max-workers=1
gradlew.bat lint --no-parallel --max-workers=1
gradlew.bat assembleDebug --no-parallel --max-workers=1
gradlew.bat connectedDebugAndroidTest --no-parallel --max-workers=1  # 有设备/模拟器时
git diff --check
```

设备覆盖不可用时必须在交付记录中明确列出未执行项，不能用 JVM 测试替代设备结论。

## 14. 分阶段实现计划

### 阶段 A：模型、迁移与纯逻辑

- 增加 Assistant/Conversation 字段、LocalToolOption、内部删除 tombstone、metadata schema，并完成历史
  Settings 默认值；
- 实现 `SubAssistantAccessPolicy`，统一显式允许、全局可见、self/普通/失效目标过滤；
- 使用 additive Migration 增加 Child 列与索引，完成 DAO 顶层过滤和 lineage/preview/catalog 纯函数；
- 先落地序列化、迁移、visibility、metadata merge 和 reducer 测试。

退出条件：历史数据兼容，Provider opaque metadata 无回归，Child 不出现在普通查询。

### 阶段 B：通用生成原语

- 以 optional contextual execute 引入带精确 Tool locator 的 `ToolExecutionContext`，保留普通 Tool API；
- `GenerationHandler` 支持 toolProvider、每个 LLM step 重新解析、Phase/Checkpoint/Finished 与 NonInteractive
  approval policy；Master collector 同步落地 checkpoint；
- 抽取 `ConversationSessionRegistry` 和 `GenerationToolSetFactory`，增加每轮复用的
  `TtsToolPlaybackContext` 和瞬态来源，普通聊天行为保持不变。

退出条件：现有 Tool/HITL/Provider 测试全通过，普通聊天生成无 UI/协议回归。

### 阶段 C：Assistant Tools 与管理

- 实现 `AssistantManagementService`、`AssistantToolFactory` 和 Catalog；
- 实现 Assistant Tools 的 schema、紧凑返回值和专用管理审批摘要；Catalog 与记忆列表使用
  `header + rows`，`assistant_memory_list` 保持只读；
- CREATE 原子写入私有子助手与 creator 允许列表；UPDATE/DELETE/list/call 均复用访问策略，删除与关闭
  类别清理反向授权；
- Memory Repository 增加 owner + id 的更新/删除约束；UI 删除切换到统一 Service，删除按撤权、停 run、
  durable tombstone 和幂等跨存储清理分阶段执行。

退出条件：权限默认关闭、Catalog 无重复且安全转义、CRUD 与清理具备并发测试。

### 阶段 D：Target 执行与 lineage

- 实现 preflight、按 Child 的 run lease、RunSpec、严格 parent/Target 校验与前缀 clone；
- 实现共享 Target Transformer 管道、run-state reducer、进度桥、父子 checkpoint、durable cancel、
  recovery、Fork tree 和 orphan cleanup；
- Target 跨 LLM step 复用 TTS playback context，并验证来源切换不会混合全局播放队列；
- 完成 Fake Provider 集成矩阵。

退出条件：直线、分支、Fork、停止、重启均不串上下文，不出现隐藏 Pending。

### 阶段 E：配置 UI、Card 与详情

- 完成子助手类别、全局可见、共享允许列表、Picker/设置页“显示子助手”筛选和全部 locale 资源；
- 将 `assistant_call` 从通用 COT 分组拆为独立 Card；
- 完成整卡单一详情入口、有界 preview 尾部、terminal UI，以及通过
  masterConversationId + runId 验证定位的全屏只读详情（详情页 follow/pause）；
- TTS 控制条在 Target 播放时显示 Target 头像，normal 播放样式不变；
- 补齐 Compose 测试与自适应/无障碍人工验收。

退出条件：用户能找到所有配置，运行中能稳定看到最新输出，历史详情可恢复且不暴露内部错误。

### 阶段 F：全量回归与文档收口

- 更新 reference docs、changelog 和版本；
- 执行 JVM、Lint、Debug、可用的设备测试和 diff review；
- 复核备份恢复、Assistant 删除、会话搜索/统计和所有 Provider 请求体。

## 15. 最终验收标准

实现只有同时满足以下条件才算完成：

- 历史 Assistant 升级后不会自动成为子助手或获得授权；类别、全局可见和允许列表在 UI 有明确入口、
  默认值、风险说明和清理行为；
- 管理、记忆查看和调用只接受显式允许或全局可见的子助手；工具创建的私有子助手原子加入 creator
  允许列表，运行中撤权不会继续使用旧权限；
- Assistant Picker 与设置页默认只显示普通 Assistant，可显式显示全部；子助手类别和全局可见状态有
  清晰但不过度的视觉区分；没有普通 Assistant 或当前会话直接使用子助手时自动显示全部，直接选择
  子助手仍是普通顶层聊天；
- Tool descriptions 与 Catalog 简短表达“子助手 = sub-agent”，适用于任务、通用请求和角色对话；
  Catalog 安全、动态、不重复，并以 `header + rows` 消除列表重复 key；
- `assistant_memory_list` 返回可引用的记忆 ID 但始终只读；记忆修改只能由所属 Target 在自己的
  Memory namespace 内完成，Repository 的 edit/delete 同时校验 owner 与 ID；
- Running Card 以有界 tail 展示 Target 可见文本并以整卡单一入口打开详情；详情只展示本次
  request，并通过 Master + run 验证 Child 归属；长输出、Unicode、详情页实时跟随和用户手动滚动行为稳定；
- Target 不读取 Master 普通上下文，Master 不接收 Target 实时过程；
- 同一分支可延续 Target 上下文；lineage 按 Child 加锁并严格校验 parent/Target/request，分叉/Fork 只
  克隆被保留 run 的前缀，不会读到已切走分支；
- Target 永远不能递归委托；只有 `ask_user` 可进入显式宿主交互，其余需审批 Tool 永远不会进入隐藏
  Pending；折叠的思考卡也必须固定显示全部 Pending；
- Master/Child 共用唯一 Session Registry；Master 消费 durable checkpoint，step limit、取消和重启不会
  被误报为正常完成；内部更新使用精确 Tool locator，空白或跨 step 重复 toolCallId 不会串写，所有终态
  ToolCall 都有协议配对的 Tool Result；
- Target TTS 跨 step 遵循现有顺序播放开关；不同播放来源不拼接队列，控制条只为 Target 增加头像；
- 停止、删除、撤权、Provider 失败与 App 重启都有可恢复终态且不会自动重放副作用；Assistant 删除先
  durable 撤权，再以 tombstone 重试跨存储清理，不误删仍被其他 Assistant 引用的资源；
- Child 通过 additive schema 迁移持久化，不污染普通会话列表、搜索、FTS、用户活动统计、标题、建议、通知
  和声音；实际 Token 总用量仍包含 Child usage；
- Provider opaque state、普通聊天 Tool Loop、现有 HITL 和多 Provider 协议测试无回归；
- 自动化测试、Lint、Debug 构建和可用设备验收均有明确结果与覆盖缺口。
