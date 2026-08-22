# 多模态附件上下文与按需分析架构设计

> 状态：设计定稿，待按本文实施
>
> 日期：2026-08-22
>
> 适用范围：Master 普通聊天、用户上传图片、`generate_image` 与其他工具图片产物、`assistant_call` 入站附件与 Child 交付物、模型动态切换
>
> 本轮实现范围：图片附件；附件分析能力按通用附件架构设计，document / audio / video 暂不接入分析工具
>
> 关联基线：[`multimodal-context-and-turn-durability-design.md`](multimodal-context-and-turn-durability-design.md)

本文是现有《多模态上下文与 Turn 持久化设计》的多模态访问补充与修订。原文关于资产事实、稳定 `attachment_ref`、Turn durability、artifact replay、Provider 协议完整性和失败可见性的原则继续有效；以下内容取代原文中与 **自动 OCR / visual observation、`DERIVED` 投影、能力不匹配即 fail-fast、子助手自动 observation** 相关的设计。

本轮完成后，系统中不再存在 OCR 这一领域语义。原 `ocrModel` 实际承担的是通用视觉观察模型，后续统一改为 **Attachment Analysis Model（附件分析模型）**；视觉内容不再在上下文转换阶段被后台自动读取，而由模型在确有需要时显式调用附件分析工具。

---

## 1. 决策摘要

最终采用以下模型：

```text
                    Durable Conversation
                           │
                    Attachment Fact
          attachment_ref / type / mime / name / asset
                           │
                           │  不随模型和设置变化
                           ▼
                   Request Projection
                           │
                 ┌─────────┴─────────┐
                 │                   │
              NATIVE          REFERENCE_ONLY
                 │                   │
                 │             ┌─────┴─────┐
                 │             │           │
                 A             B           C
             原生读图片   inspect_attachments   无图片读取能力
```

核心决策：

1. **取消图片自动 OCR / visual observation。** 图片出现在上下文中本身不得触发任何额外模型调用。
2. **取消 `NATIVE / DERIVED / UNAVAILABLE` 作为单一附件能力三态。** 附件投递方式与附件分析能力是两个正交概念。
3. **A/B/C 只影响当前 request 的投影和工具集，不修改 Conversation。**
4. **B 和 C 的附件上下文完全一致。** 两者唯一差别是 B 当前拥有 `inspect_attachments`，C 没有。
5. **模型不能看图不等于附件不可用。** `UNAVAILABLE` / failure 只用于附件本身不存在、损坏、不安全、无法物化等真实资产失败。
6. **`attachment:<uuid>` 是稳定 identity，`/upload/...` 等 path 只是当前可用 locator。** 工具参数优先使用 `attachment_ref`。
7. **`generate_image`、用户上传图片、历史图片、嵌套 `Tool.output` 图片、子助手入站和出站图片进入同一附件链。** 不再按来源实现不同 fallback。
8. **附件分析工具是 Runtime 根据本次实际模型能力自动提供的 capability tool，不是 Assistant 手工勾选的普通 LocalTool。**
9. **本轮彻底移除 OCR 命名。** 设置、领域模型、Prompt、Transformer、缓存和 UI 均改为 Attachment Analysis / multimodal 语义；旧 DataStore key 只允许存在于一次性迁移代码中。

---

## 2. 为什么取消自动 OCR

当前 `ImageInputAdapter` 的所谓 OCR 并不是文字 OCR：默认 Prompt 要求描述文字、图标、形状、物体、位置和空间关系，本质是通用 visual observation。当前实现会在聊天模型不支持 IMAGE、但配置了视觉模型时，于上下文转换阶段自动调用该模型，再把图片替换成 `<attachment_observation>` 文本。

这在 Agent 架构下有三个根本问题。

### 2.1 在任务意图明确前提前解释图片

同一张截图可能被问：

- “错误信息是什么？”
- “右上角第二个按钮是什么？”
- “比较左右两块代码的差异。”

固定的通用 observation 无法同时为这些任务提供最合适的证据，而且会为根本不需要读取图片的 turn 产生额外模型调用和 token。

### 2.2 把附件事实和派生理解混为一体

图片是稳定事实；“某视觉模型在某个 Prompt 下对此图片的描述”只是一次派生结果。把派生描述作为 request 前置转换，会导致相同 Conversation 在 A/B/C 下呈现不同历史，并把模型能力变化错误地写进附件语义。

### 2.3 错误地把消费能力不足定义为资产失败

C 场景中，文件、`attachment_ref`、MIME、managed artifact 都可能完全正常，只是本次模型没有读取视觉内容的方法。这不应该变成 `ATTACHMENT_INPUT_UNAVAILABLE` 或整个 turn 失败。

因此新的原则是：

> **上下文转换只表达附件事实并完成当前 Provider 的原生投影；派生视觉理解只能来自显式的 `inspect_attachments` 工具调用。**

---

## 3. 领域边界与不变量

### 3.1 四个职责

```text
Attachment Fact
  └─ 这个附件是什么、稳定身份是什么

Attachment Resolver
  └─ attachment_ref / 受控 path 当前能否解析为真实受管资产

Attachment Projection
  └─ 当前模型和 Provider 是否投递原生媒体，或只投递引用事实

Attachment Analysis Capability
  └─ 当前 run 是否提供 inspect_attachments 来按需读取内容
```

不得再由一个 `ImageInputAdapter` 同时承担“判断能力 + 自动分析 + 替换上下文 + fail-fast”。

### 3.2 必须保持的不变量

1. Conversation 中的原始附件和工具产物是历史事实，不因当前模型变化而重写。
2. `attachment_ref` 一经产生，在该资产生命周期内保持稳定。
3. path 不是 identity；path 可以因 materialize、workspace、同步或 Provider 投影变化而改变。
4. A/B/C 每次 request / run 都基于实际 resolved model 和当前设置重新计算。
5. 任何附件仅仅进入上下文，都不得隐式调用附件分析模型。
6. 当前模型不支持 IMAGE 不得单独造成 turn 或 `assistant_call` 失败。
7. 只有真实附件解析、完整性、安全或类型失败才属于 attachment failure。
8. derived visual text 只能是显式工具调用的可见 Tool Result，因此自然进入正常 Agent 历史和 Turn durability。
9. 用户图片、`generate_image`、其他 Tool artifact、Child artifact 使用同一个 attachment graph 和同一套投影规则。

---

## 4. 稳定附件事实与 Model View

### 4.1 Durable Attachment Fact

持久事实至少应能表达：

```text
attachment_ref   attachment:<uuid>
type             image
mime             image/png
display_name     screenshot.png
asset identity   managed file / tool artifact / sub-assistant artifact
source           user / tool / sub-assistant（如现有领域已有，保留即可）
```

这里不记录：

- 当前模型能否看图；
- 当前是否配置附件分析模型；
- “当前图片不可读”之类能力提示；
- 自动 observation；
- 临时 Provider file id / URL；
- 把 path 当作永久身份。

### 4.2 Request-scoped Attachment Manifest

每次构建 model view 时，在附件附近生成短、结构稳定、不可持久化的 manifest。例如：

```text
[Attachment ref=attachment:8f2... type=image mime=image/png name="screenshot.png" path="/upload/screenshot.png"]
```

规则：

- `ref` 必须存在；
- `type`、`mime`、`name` 尽量来自稳定元数据；
- `path` 仅在当前 request 中确实存在可供本地工具访问的受控路径时提供，否则省略；
- 不暴露宿主机绝对路径、过期 URL 或其他内部实现路径；
- manifest 递归覆盖顶层附件和 `Tool.output` 内附件；
- manifest 只属于 model view，不写回 Conversation。

现有 `AttachmentRefHintTransformer` 已经承担“model-view only + 递归 Tool.output”的基础职责，本轮应把它提升为稳定附件上下文生成器，而不是另起一条并行提示链。

### 4.3 `attachment_ref` 与 path 的使用规则

- LLM 引用已有附件时优先使用 `attachment:<uuid>`。
- `/upload/...` 可作为当前 workspace 可访问 locator，并允许附件分析工具在受控范围内接受。
- Runtime 内部始终通过 `AttachmentResolver` 验证和解析，不信任模型直接提供的任意文件路径。
- 如果同一个附件经过 materialize 后 path 改变，`attachment_ref` 不变。

---

## 5. A / B / C 的确定逻辑

A/B/C 是 **本次 run 的能力结果**，不是 Conversation、Assistant 或附件的永久属性。

### 5.1 A：当前模型可原生读取图片

条件：

```text
resolved chat model
+ current provider / endpoint profile
+ image MIME / limits
+ placement constraints
=> 可以在本次请求中原生投递该图片
```

行为：

```text
stable attachment manifest
+ native image block
+ 不自动注册 fallback inspect_attachments
```

说明：

- 不能永远只依据 `model.inputModalities.contains(IMAGE)`；最终能力解析应同时考虑 Provider、endpoint、MIME、placement 和文件状态。
- 如果某 Provider 不允许媒体直接位于 tool result 内，应在 Provider projection 层做协议安全的 media relocation，同时保留原 Tool call/result 关系，不改变附件身份。
- 默认不给 A 注册 `inspect_attachments`，避免重复视觉路径、额外工具 schema 和结果冲突。未来若出现“专业 OCR / 超高分辨率局部分析”等独立能力，应作为新的明确能力设计，而不是本 fallback 的理由。

### 5.2 B：当前模型不能原生读图片，但配置了有效附件分析模型

条件：

```text
native image delivery = false
AND attachmentAnalysisModel 已配置
AND model/provider 当前可用
AND analysis model 支持 IMAGE
=> B
```

行为：

```text
stable attachment manifest
+ 不投递原生 image block
+ 自动注册 inspect_attachments
+ 不自动分析任何图片
```

B 与 C 的 attachment manifest 完全相同。

可增加一条非常短的 request-scoped capability hint，帮助模型正确使用工具，例如：

```text
Image attachment content is available through inspect_attachments when visual details are needed.
```

该提示不持久化，也不针对每个图片重复注入。

### 5.3 C：当前模型不能原生读图片，也没有有效附件分析模型

条件：

```text
native image delivery = false
AND no valid attachment analysis capability
=> C
```

行为：

```text
stable attachment manifest
+ 不投递原生 image block
+ 不注册 inspect_attachments
+ 允许正常生成
```

可增加一条短的 request-scoped capability hint：

```text
Image attachments are reference-only in this run. Their visual content is not available; do not infer image details.
```

因此以下请求不再被 Runtime 错误拦截：

```text
[图片] 顺便帮我写一个 Kotlin 排序函数
```

而当用户问“图片里是什么？”时，模型能看到附件事实，却没有 native media 和分析工具，因此应直接说明本次无法读取图片内容，而不是猜测。

### 5.4 真正的附件失败

以下才属于 attachment failure：

- `attachment_ref` 找不到；
- managed asset 已丢失；
- 文件损坏或无法读取；
- MIME / 内容校验不通过；
- 大小、安全或路径策略不允许；
- remote materialization 失败；
- 请求了当前明确不支持的附件类型。

不要再用 `UNAVAILABLE` 表示“模型看不了图片”。

### 5.5 模型动态切换

| 切换 | 当前 request 变化 | Durable history |
|---|---|---|
| A → B | native image 消失，`inspect_attachments` 出现 | 不变 |
| A → C | native image 消失 | 不变 |
| B → A | `inspect_attachments` 消失，native image 出现 | 不变 |
| B → C | `inspect_attachments` 消失 | 不变 |
| C → B | `inspect_attachments` 出现 | 不变 |
| C → A | native image 出现 | 不变 |

过去的 delivery / tool execution 事实不改写；下一 request 只从原始附件事实重新投影。

---

## 6. `inspect_attachments` 工具设计

### 6.1 定位

工具名确定为：

```text
inspect_attachments
```

不用 `inspect_image`，因为工具属于附件访问能力；MVP 只实现 image resolver，但上层工具名、参数与结果协议从一开始保持 attachment 语义，未来增加 document / audio / video 时无需更换上层契约。

它是：

- Runtime 自动能力工具；
- read-only；
- 不需要审批；
- 不属于 Assistant 的 `LocalToolOption` 手工开关；
- 仅在 B 场景注册；
- A 默认不注册；
- C 不注册。

### 6.2 为什么工具应在 B 中始终存在

注册条件只依赖本次 run 的能力，不依赖“当前历史里是否已经有图片”。即便初始上下文没有图片，B 仍注册该工具，因为本轮后续可能：

- `generate_image` 新生成图片；
- 其他工具产生图片 artifact；
- workspace / 下载类工具产生可解析图片；
- 子助手返回图片。

这样工具 schema 在一次 run 内稳定，也使刚生成的图片能够在下一 Agent step 立刻被分析。

### 6.3 Tool description

建议保持简洁、确定、引导式：

```text
Inspect the content of one or more attachments when visual details are needed. Currently supports images. Ask for the specific evidence, transcription, description, or comparison needed for the task.
```

不要求“看到图片必须调用”，也不要求“大而全描述”；是否调用、问什么由主模型根据任务决定。

### 6.4 参数

MVP 只保留两个参数：

```json
{
  "attachments": ["attachment:...", "attachment:..."],
  "request": "Compare the two screenshots and identify the visible error differences."
}
```

契约：

```text
attachments : string[]，required，1..4
              优先 attachment:<uuid>
              可兼容受控 /upload/... path
              输入顺序就是分析标签顺序

request     : string，required
              说明当前任务需要从附件获取的具体视觉证据或比较目标
```

暂不增加 `mode`、`detail`、`ocr`、`quality`、`language` 等参数。模型能力和 Provider 参数属于 Runtime 配置，不应该污染通用工具协议。

### 6.5 多附件语义

必须原生支持多个附件，而不是让 LLM 循环单图调用：

- 最多 4 个，与现有 `assistant_call` 图片上限保持一致；
- Runtime 先解析和校验所有输入；
- 全部成功后，在 **一次附件分析模型调用** 中按输入顺序提供所有图片；
- 每个附件附带稳定 label，例如 `Attachment 1 / attachment:... / name`；
- 这样视觉模型能直接完成真正的跨图比较；
- 任一输入无法解析或类型不支持时，本次调用整体失败，不静默跳过，避免比较任务得到不完整结论。

### 6.6 Runtime system guidance

固定的系统约束应短而稳定，例如：

```text
You analyze attachments for another agent. Answer the requested analysis using only the provided attachment contents. Be precise, distinguish attachments by label, transcribe visible text when relevant, and state uncertainty. Do not perform unrelated tasks.
```

用户/Caller 的 `request` 作为独立任务输入，不拼进系统 Prompt。这样缓存前缀稳定，职责也清晰。

原 `ocrPrompt` 不再承担“把所有图片提前描述一遍”的职责；若保留用户可配置分析 Prompt，它只作为上述固定 contract 之外的附加分析指导，不改变工具输入输出语义。

### 6.7 成功返回

工具只返回 Text Tool Result，不把图片再次作为 Tool output 返回，避免递归投影和无意义媒体回环。

建议协议：

```json
{
  "status": "completed",
  "attachments": [
    {
      "ref": "attachment:8f2...",
      "name": "before.png",
      "mime_type": "image/png"
    },
    {
      "ref": "attachment:91a...",
      "name": "after.png",
      "mime_type": "image/png"
    }
  ],
  "content": "..."
}
```

`content` 是附件分析模型针对本次 `request` 的结果。它作为显式 Tool Result 正常进入 Conversation/Turn durability，因此之后的模型切换仍能看到“此前明确调用工具得到的证据”，但系统不会把它误认为附件本身。

### 6.8 失败返回

建议 reason 集合保持小而确定：

```json
{
  "status": "failed",
  "reason": "attachment_not_found",
  "attachment": "attachment:8f2..."
}
```

MVP reason：

```text
attachment_not_found
unsupported_attachment_type
analysis_model_unavailable
analysis_failed
```

能归因到单个输入时带 `attachment`；模型或 Provider 级失败无需伪造某个 attachment。

### 6.9 解析与安全

`inspect_attachments` 不直接 `File(path)`：

```text
Tool input
  -> AttachmentResolver
  -> stable ref / controlled tool path validation
  -> managed local asset
  -> MIME / size / safety validation
  -> attachment analysis provider
```

优先复用现有 `AttachmentResolver`。需要扩展其调用接口时，应扩展为 attachment-generic resolver，而不是在工具中复制一套路径和远程下载逻辑。

### 6.10 未来附件类型

工具协议从现在就允许未来扩展：

```text
inspect_attachments
  ├─ image resolver       ← 本轮实现
  ├─ document resolver    ← future
  ├─ audio resolver       ← future
  └─ video resolver       ← future
```

本轮遇到 document / audio / video 必须返回 `unsupported_attachment_type`，不要为了“架构完整”提前实现四套 analyzer。

现有 `DocumentAsPromptTransformer` 保持不变；文档内容抽取是否未来收敛进统一附件分析能力，另行设计，不在本轮扩大范围。

---

## 7. `generate_image` 的统一上下文行为

`generate_image` 必须被视为“产生一个新的稳定图片附件”，而不是一个特殊的视觉 fallback 场景。

### 7.1 生成完成时

成功的 `generate_image` 应继续：

```text
生成图片
  -> managed/generated media 持久化
  -> Tool artifact metadata
  -> UIMessagePart.Image
  -> ensure stable attachment_ref
  -> Tool Result 持久化
```

工具成功与 Caller 是否能看图无关。C 模型不能看图，不代表 `generate_image` 失败。

### 7.2 下一 Agent step 的统一流程

每个工具执行后的下一 Provider step 都重新构建 request-specific model view：

```text
ToolArtifactReplayTransformer
  -> attachment materialization / ref stamping
  -> attachment manifest
  -> current A/B/C projection
  -> Provider encoding
```

因此刚生成的图片无需任何特殊“回灌 OCR”逻辑。

### 7.3 A

```text
generate_image Tool Result
+ stable attachment manifest
+ native image media
```

主模型在下一 step 直接看到图片。

如果当前 Provider 不允许 Image 直接位于 Tool result 中，由 Provider projection 层把媒体移动到协议允许的位置；Tool success/result 仍保持完整，附件 `ref` 不变。

### 7.4 B

```text
generate_image Tool Result
+ stable attachment manifest/reference
+ inspect_attachments 已经在本 run 工具集中
```

不会自动分析生成图片。只有主模型确实需要确认视觉结果时才调用：

```text
inspect_attachments(
  attachments=["attachment:..."],
  request="Check whether the generated image contains ..."
)
```

这解决了“文生图结果需要继续参与 Agent 推理，但不应每次强制二次视觉模型调用”的问题。

### 7.5 C

```text
generate_image Tool Result
+ stable attachment manifest/reference
```

模型知道图片已经生成、知道其 `attachment_ref` / 可用 path，但没有看到视觉内容，也没有分析工具。它不得声称具体画面符合某些视觉细节。

### 7.6 历史 Tool image

历史 `generate_image` 图片和其他 Tool image 规则完全相同：每次 request 先通过 artifact replay 恢复真实资产，再按当前 A/B/C 重新投影。不得保存一次性的 `[Image]`、自动 observation 或“历史不可读”文本来代替原图片事实。

---

## 8. 子助手统一规则

Master 和 Target 不拥有两套多模态语义。Target 只是另一个拥有自己 resolved model / settings snapshot / tool set 的 run。

### 8.1 `assistant_call` 入站附件

当前流程中，Target 不支持 IMAGE 且没有 OCR 时会在写入 Child 前 preflight 并拒绝委托。该行为必须删除。

新的流程：

```text
assistant_call attachments
  -> AttachmentResolver
  -> 检查真实资产 / 类型 / 安全
  -> 将原始 Image + stable attachment_ref 写入 Child task
  -> Target 构建自己的 request model view
  -> 按 Target 本次 A/B/C 处理
```

只要附件资产真实有效：

- Target A：native image；
- Target B：reference + `inspect_attachments`；
- Target C：reference only，仍允许 Target 执行任务。

只有附件本身解析失败才阻止委托。

### 8.2 Target 的实际模型必须成为工具能力输入

Target 可在调用期继承 Caller 模型，因此不能在 `GenerationToolSetFactory` 内重新用静态 Assistant 设置猜模型。

`SubAssistantCoordinator` 已经解析出真正的 `runSpec.model`。工具集构建必须显式使用这个 resolved model：

```text
GenerationToolSetFactory.buildTools(
  assistant = ...,
  settings = runSettingsSnapshot,
  resolvedModel = runSpec.model,
  runMode = TARGET,
)
```

Master 也传入本次实际 resolved model。所有 capability-dependent tools 都以这个参数为准。

### 8.3 Tool set 在一个 run 内冻结

run 开始时解析：

```text
resolved model
settings snapshot
attachment analysis capability
tool set
```

本轮运行期间即使用户修改设置，也不在中途改变工具 schema；新设置从下一 run / turn 生效。这对 Provider prompt/tool cache 和 ToolCall 协议稳定性都更合理。

### 8.4 Child → Caller 图片交付

当前 `projectArtifactsForCaller()` 的 `DERIVED` 分支会自动 `observe()` Child 图片，应删除。

Child 交付物保持稳定 artifact/ref 事实。若当前 contract 使用 `extras=artifacts` 控制媒体投影，则继续保留该开关，但投影改为：

```text
Caller A  -> artifact metadata/reference + native image
Caller B  -> artifact metadata/reference；需要时 inspect_attachments
Caller C  -> artifact metadata/reference only
```

不再有：

```text
Caller B -> 自动 observation
```

`artifactDelivery` 语义建议从旧：

```text
derived / partial / unavailable
```

调整为：

```text
native / reference / partial / missing
```

其中 `missing` 只表示实际 artifact 不存在或无法物化；不能用 `unavailable` 表示 Caller 看不了图。

### 8.5 Child 自己 `generate_image`

Child 内部生成图片后，先按 **Target 自己的 A/B/C** 继续其 Agent loop；最终作为 Child artifact 返回 Master 时，再按 **Caller 当前 A/B/C** 独立投影。

这两个能力判断互不继承、互不写入历史。

---

## 9. OCR 语义彻底移除与设置迁移

本轮实施后代码、设置和 UI 不再出现 OCR 作为当前产品概念。

### 9.1 新命名

领域字段：

```text
ocrModelId       -> attachmentAnalysisModelId: Uuid?
ocrPrompt        -> attachmentAnalysisPrompt
```

Prompt：

```text
DEFAULT_OCR_PROMPT
-> DEFAULT_ATTACHMENT_ANALYSIS_PROMPT
```

UI：

```text
OCR Model
-> Attachment Analysis Model / 附件分析模型
```

分析模型选择器：

- 有明确 `None / 未配置`；
- 只显示支持 IMAGE input 且 Provider 可配置的模型；
- 不再用随机 UUID 表示“未配置”。

### 9.2 DataStore key

新 key：

```text
attachment_analysis_model
attachment_analysis_prompt
```

旧 key：

```text
ocr_model
ocr_prompt
```

只允许保留在一次性兼容迁移代码中，不能继续作为运行时领域命名。

迁移规则：

1. 若新 key 已存在，以新 key 为准。
2. 若只有旧 `ocr_model`：
   - 能解析到现有模型；
   - Provider 存在；
   - 模型支持 IMAGE；
   才迁移为 `attachment_analysis_model`，否则迁移为未配置。
3. 旧 Prompt 若为旧默认值，迁移为新的默认 Attachment Analysis Prompt。
4. 用户自定义旧 Prompt 可迁移为 `attachmentAnalysisPrompt`，避免静默丢失用户设置；运行时仍在固定工具 contract 外使用它，不能覆盖 Tool 输入输出约束。
5. 新值成功写入后清除旧 key。
6. 备份恢复和兼容反序列化必须覆盖旧字段迁移测试。

### 9.3 删除旧实现

实施完成后删除或替换：

- `OcrTransformer`；
- `OcrPrompt.kt` / `DEFAULT_OCR_PROMPT`；
- `ImageInputAdapter.observe()`；
- `ImageAdaptCapability.DERIVED`；
- `CurrentImageInputUnavailableException` 这类“能力不足即 turn failure”路径；
- `<attachment_observation>` 自动包装；
- `image_observation_cache.json` 和相关 cache key/version；
- `ocrModelId` / `ocrPrompt` 的运行时和 UI 命名；
- Child artifact 自动 observe 分支。

本轮不要为新工具增加隐藏 observation cache。若未来证明附件分析结果值得缓存，cache key 至少必须包括：

```text
ordered attachment content hashes
+ attachment analysis model
+ analysis prompt/version
+ normalized request
```

否则不同任务的问题会错误复用同一通用描述。

---

## 10. 目标请求管线

### 10.1 Master

目标顺序：

```text
Conversation history
  -> replay-safe history / ordinary context transformers
  -> ToolArtifactReplayTransformer
  -> AttachmentContextTransformer
       - materialize/resolve request locators
       - emit stable manifest recursively
  -> AttachmentProjectionTransformer
       - NATIVE or REFERENCE_ONLY
       - Provider placement decision
  -> provider wire encoding
```

Tool set 在 Provider 调用前基于本次 resolved model 生成：

```text
base tools
+ assistant-selected tools
+ workspace / skills / MCP
+ runtime capability tools
    └─ inspect_attachments only for B
```

### 10.2 Target

使用与 Master 完全相同的：

```text
AttachmentContextTransformer
AttachmentProjectionTransformer
GenerationToolSetFactory
```

仅 run mode 和 resolved model/settings snapshot 不同，不维护 Target 专用多模态 fallback。

### 10.3 Provider projection

Provider 层只负责：

- typed text/image/file 编码；
- native media placement；
- Provider file handle / URL / base64 等协议差异；
- 保持 Tool call/result 完整性；
- 对失败返回明确 typed failure。

Provider 层不得自行决定调用附件分析模型。

---

## 11. 文件级实施方案

以下按当前代码职责给出确定改动，实施时允许因现有文件布局做小范围重命名，但不要重新引入第二套附件链。

### 11.1 Settings / Prompt

**`data/datastore/Settings` / `SettingsStore` 相关文件**

- `ocrModelId` → nullable `attachmentAnalysisModelId`；
- `ocrPrompt` → `attachmentAnalysisPrompt`；
- 新增新 DataStore key；
- 增加旧 key 一次性迁移；
- 删除随机 UUID “未配置”兼容方式；
- 写入时 null 删除 model key。

**`data/ai/prompts/OcrPrompt.kt`**

- 删除 OCR 文件/命名；
- 新建 `AttachmentAnalysisPrompt.kt`；
- 使用短、稳定的分析助手默认 Prompt。

**设置 UI**

- 所有 OCR 文案和变量改为 Attachment Analysis；
- 明确 `None`；
- 过滤到 IMAGE-capable models；
- 不再解释成“上传图片自动 OCR”。

### 11.2 Attachment context / projection

**`AttachmentRefHintTransformer.kt`**

- 建议重命名/提升为 `AttachmentContextTransformer`；
- 从 `[Attachment ref, name]` 扩展为固定 manifest：ref/type/mime/name/request path；
- 保持 model-view only；
- 保持递归 `Tool.output`；
- 不写任何当前 capability 文本到 Conversation。

**`AttachmentInputTransformer.kt`**

- 移除自动 observation；
- 重构为 `AttachmentProjectionTransformer`；
- 只负责 NATIVE / REFERENCE_ONLY；
- 保留本地图片和嵌套 Tool image 的投影；
- capability mismatch 不抛异常。

**`ImageInputAdapter.kt`**

- 删除 `observe()`、DERIVED、自动错误占位和 observation cache；
- 如仍需图片原生能力解析，重命名为更准确的 `AttachmentProjectionResolver` / `NativeMediaCapabilityResolver`，职责仅限当前 Provider 是否能投递原图。

**`OcrTransformer.kt`**

- 删除，不保留 compatibility alias。

### 11.3 `inspect_attachments`

新增建议：

```text
data/ai/tools/runtime/AttachmentAnalysisTool.kt
```

或放入当前 runtime capability tools 的统一目录；不要加入 `LocalToolOption`。

职责：

- schema / description；
- 1..4 attachments 校验；
- 调用 `AttachmentResolver`；
- 检查当前 `attachmentAnalysisModelId`；
- 单次多图片分析模型调用；
- 结构化成功/失败 Text result；
- 不返回 Image part。

如果项目暂时没有 runtime tools 目录，可由 `GenerationToolSetFactory` 调用一个独立 `createAttachmentAnalysisTool(...)` factory，避免让 Factory 自己持有 Provider 调用细节。

### 11.4 `GenerationToolSetFactory.kt`

- `buildTools()` 显式增加本次 `resolvedModel` 参数；
- 使用统一函数计算 A/B/C；
- B 自动增加 `inspect_attachments`；
- A/C 不增加；
- 不根据当前 messages 是否含图片改变工具集；
- Master/Target 共用。

### 11.5 `ChatService.kt`

- 生成/重新生成/审批恢复路径都传入本次 resolved model；
- input transformer 链替换旧 `AttachmentRefHintTransformer + AttachmentInputTransformer` 为新的 context + projection 语义；
- 保证每个 Tool step 后下一 Provider step 重新执行 artifact replay 和 attachment projection；
- 不捕获/生成 `CurrentImageInputUnavailableException` 类型的能力失败。

### 11.6 `AttachmentResolver.kt`

- 继续作为稳定 ref / 受控 path → managed asset 的权威入口；
- 为 `inspect_attachments` 提供可复用的批量解析接口；
- 批量解析采用 all-or-nothing；
- 目前只把 image 交给 Attachment Analysis Tool；
- 不在 Tool 内复制安全路径与 remote fetch 规则。

### 11.7 `ImageGenerationTool.kt` / artifact replay

- `generate_image` 继续产生持久 artifact + Image；
- 确保所有成功 Image 都有 stable `attachment_ref`；
- `ToolArtifactReplayTransformer` 保持在 attachment context/projection 之前；
- 不增加任何自动分析逻辑；
- Tool result 中的 path/ref 能在下一个 step 被 Resolver 使用。

### 11.8 `SubAssistantCoordinator.kt`

- 删除“attachments 非空 + Target capability UNAVAILABLE => 拒绝” preflight；
- attachment 入站只做真实 resolve / materialize / validation；
- Child task 始终保存原始 attachment fact；
- Target tools 构建传 `runSpec.model`；
- 删除 Caller artifact projection 中对 `ImageInputAdapter.observe()` 的依赖。

### 11.9 `SubAssistantResultProjection.kt`

- 删除 `DERIVED` 分支；
- A 输出 native image projection；
- B/C 输出稳定 reference/manifest；
- delivery 命名迁移到 `native/reference/partial/missing`；
- Child tool artifact 和最终 assistant media 继续走同一 extract/dedup 机制。

### 11.10 文档同步

实施代码时同步：

- `docs/dev/multimodal-context-and-turn-durability-design.md`：将已被本文取代的 OCR/DERIVED/fail-fast 部分标记为 superseded 或直接同步成新语义；
- `docs/references/sub-assistant-multimodal.md`：删除 Target 自动 observation / unavailable preflight 描述；
- `docs/dev/changelog.md`：记录 OCR → Attachment Analysis、自动视觉观察取消、`inspect_attachments` 和统一 A/B/C。

本文是实施时的权威设计；若旧文档与本文在上述多模态访问语义上冲突，以本文为准。

---

## 12. 实施顺序

按以下顺序实施，保持每一步可测试、避免同时存在两套视觉 fallback：

### Phase 1 — Domain / Settings rename

1. 建立 Attachment Analysis 新字段、Prompt、新 DataStore key 和迁移。
2. 设置 UI 改名、`None`、IMAGE 模型过滤。
3. 保持旧运行逻辑临时可编译，但运行时代码开始只依赖新字段。

### Phase 2 — Stable context + remove auto observation

1. `AttachmentContextTransformer` 稳定 manifest。
2. `AttachmentProjectionTransformer` 只做 NATIVE / REFERENCE_ONLY。
3. 删除自动 observation、cache 和 capability mismatch fail-fast。
4. 删除 OCR compatibility classes。

### Phase 3 — `inspect_attachments`

1. 实现批量 resolver。
2. 实现 tool schema / provider call / structured result。
3. `GenerationToolSetFactory` 使用 resolved model 动态注册。
4. 验证工具即使初始无图片也在 B run 中稳定存在。

### Phase 4 — `generate_image` / Tool artifact

1. 确认生成图片稳定 ref。
2. 验证 A/B/C 的下一 step 投影。
3. 验证历史 tool image 与当前 tool image 相同。

### Phase 5 — Sub-assistant convergence

1. 删除 Target modality preflight rejection。
2. Target 使用同一 A/B/C 和动态 capability tool。
3. Child → Caller 删除 DERIVED observation。
4. 调整 artifact delivery 命名与测试。

### Phase 6 — Cleanup / docs / regression

1. 全仓确认无运行时 OCR 语义残留。
2. 删除死代码和旧缓存。
3. 更新原设计、sub-assistant reference 和 changelog。
4. 跑完整单测、集成测试和 Android 构建。

---

## 13. 测试矩阵与验收

### 13.1 A/B/C 基础

至少覆盖：

| 来源 | A | B | C |
|---|---|---|---|
| 当前用户图片 | manifest + native | manifest + inspect tool | manifest only |
| 历史用户图片 | manifest + native replay | manifest + inspect tool | manifest only |
| 当前 `generate_image` | native on next step | ref + callable inspect | ref only |
| 历史 `generate_image` | native replay | ref + callable inspect | ref only |
| 其他 nested `Tool.output` image | native | ref + inspect | ref only |
| `assistant_call` Target inbound | Target native | Target ref + inspect | Target ref only |
| Child → Caller artifact | Caller native | Caller ref + inspect | Caller ref only |

### 13.2 六种模型切换

必须覆盖：

```text
A -> B
A -> C
B -> A
B -> C
C -> A
C -> B
```

验收：

- persisted Conversation / attachment ref 不改变；
- 只改变 request media blocks、capability hint 和 tool set；
- 切回 A 能再次从原始资产得到 native media；
- 不依赖过去自动 OCR 文本恢复视觉能力。

### 13.3 `inspect_attachments`

必须覆盖：

- 1 张、2 张、4 张图片；
- 顺序和 label 稳定；
- 多图只产生一次 analysis provider call；
- `attachment_ref` 正常解析；
- 受控 `/upload` path 正常解析；
- 任一 attachment invalid 时整体失败；
- document/audio/video 当前返回 `unsupported_attachment_type`；
- analysis model 未配置/删除/provider 不可用；
- analysis provider failure；
- success 只返回 Text Tool Result；
- 显式 Tool Result 正常持久化并可在后续历史重放。

### 13.4 工具集

- A：无 fallback `inspect_attachments`；
- B：有 `inspect_attachments`，即使历史里当前没有图片；
- C：无 `inspect_attachments`；
- Target 继承 Caller model 时按 `runSpec.model` 判定，不按静态 Target 设置误判；
- 同一 run 内修改设置不改变已经冻结的 tool schema；下一 run 生效。

### 13.5 `generate_image`

- 工具成功与 A/B/C 无关；
- 生成图片获得 stable attachment ref；
- B 在同一 Agent loop 的下一 step 可调用 `inspect_attachments` 分析刚生成图片；
- C 能看到 artifact/reference 但没有视觉细节；
- A 能收到 native media；
- Tool result 嵌套图片在 Provider 不支持 tool-result media 时 relocation 不破坏 Tool call/result 关系。

### 13.6 子助手

- C Target 不再因附件存在而拒绝 `assistant_call`；
- 真正 attachment missing/unsafe 仍拒绝；
- Target B 可以按需调用 `inspect_attachments`；
- Child B 生成图片后可以自行 inspect；
- Caller B 收到 Child image ref 后可以 inspect；
- 无任何 Child artifact 自动 observation 调用。

### 13.7 OCR 清理验收

运行时代码、设置 UI 和新文档中不再存在当前语义的：

```text
ocrModelId
ocrPrompt
OcrTransformer
DEFAULT_OCR_PROMPT
ImageAdaptCapability.DERIVED
<attachment_observation>
image_observation_cache
CurrentImageInputUnavailableException（能力不匹配用途）
```

旧 `ocr_model` / `ocr_prompt` 字符串只允许出现在兼容迁移测试或迁移代码中。

### 13.8 最关键的行为断言

必须有测试证明：

> **仅仅把图片加入 Conversation，在 B/C 中不会触发任何额外模型调用。**

只有显式 ToolCall `inspect_attachments` 才允许调用 Attachment Analysis Model。

---

## 14. 与成熟 API / Agent 模式的对应关系

本设计不是依赖某一家 Provider 的特殊机制，而是对当前主流模式取共同部分：

- OpenAI Responses / multimodal input 使用 typed content block，并可通过 file/image reference 传入媒体；资源身份和本次输入投影是分离的。
- Anthropic Files API 强调上传一次、通过 `file_id` 多次引用，并在 Message 中以 typed image/document block 使用。
- Gemini Files API 使用可复用 file URI，再由当前请求决定怎样把 media 纳入内容。
- MCP Resources 使用稳定 URI 标识资源，Host 决定资源何时、以何种方式进入模型上下文。

这些做法的共同点不是“对不支持图片的模型自动 OCR”，而是：

```text
stable resource identity
+ typed attachment/media
+ request-specific projection
+ explicit tool/resource resolution when needed
```

参考：

- OpenAI Images and vision: https://platform.openai.com/docs/guides/images-vision
- Anthropic Files API: https://docs.anthropic.com/en/docs/build-with-claude/files
- Anthropic Vision: https://docs.anthropic.com/en/docs/build-with-claude/vision
- Gemini Files API: https://ai.google.dev/gemini-api/docs/files
- Model Context Protocol Resources: https://modelcontextprotocol.io/docs/concepts/resources

---

## 15. 最终架构判定

本轮之后，多模态图片处理不再是：

```text
图片
 -> 当前模型不会看
 -> 后台自动 OCR/visual observation
 -> 文本替换图片
```

而是：

```text
任意图片来源
 -> stable attachment fact
 -> current request projection
      ├─ A: native image
      ├─ B: reference + inspect_attachments
      └─ C: reference only
```

`inspect_attachments` 的一次显式调用才形成：

```text
attachment ref(s)
 -> validated managed assets
 -> Attachment Analysis Model
 -> task-specific visual evidence
 -> persisted Tool Result
```

由此得到统一且长期稳定的边界：

- **Conversation 保存事实；**
- **Resolver 找到资产；**
- **Projection 适配当前 Provider；**
- **Tool 在需要时读取附件内容；**
- **模型切换只改变当前能力，不改变过去事实。**

这套规则同时覆盖用户上传图片、`generate_image`、未来图片工具产物和子助手，且为 document/audio/video 留出了相同的附件识别扩展点，而不要求本轮提前实现它们。
