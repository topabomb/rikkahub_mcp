# 多模态附件投影与按需识别架构

> 状态：设计定稿，作为本轮实现的权威方案
>
> 日期：2026-08-22
>
> 适用范围：Master、用户上传图片、`generate_image` 与其他图片 Tool artifact、`assistant_call` 入站附件与 Child 交付物、模型动态切换
>
> 本轮实现范围：Image。Document / Audio / Video 保留附件身份，但不接入本轮识别工具。
>
> 关联基线：[`multimodal-context-and-turn-durability-design.md`](multimodal-context-and-turn-durability-design.md)

本文重新收敛多模态附件方案。目标不是把旧 OCR 子系统换一个名字，而是**删除旧的自动派生链**，只保留真正需要的三件事：

```text
稳定附件事实
+ 当前请求的原生/引用投影
+ 必要时显式调用的附件识别工具
```

原设计中的资产事实、stable `attachment_ref`、Tool artifact replay、Turn / Tool execution 持久化和 Provider 协议完整性继续有效；以下内容取代自动 OCR / visual observation、`DERIVED`、能力不匹配 fail-fast、自动 observation cache 与子助手自动 observation 等设计。

**本轮完成后，OCR 不再是产品、领域、配置或运行时概念。** 旧 OCR 只允许出现在一次性迁移代码、迁移测试和历史说明中。

---

## 1. 最终架构：只保留三个职责

```text
                    Durable Conversation
                           │
                    Attachment Fact
              ref / type / mime / name / asset
                           │
                           ▼
                  Request Projection
                    │             │
                  native       reference
                    │             │
                    └──────┬──────┘
                           │
                  Runtime Tool Set
                           │
             inspect_attachments（按需）
```

### 1.1 Attachment Fact：只保存事实

Conversation / Tool artifact 中保留原始附件及稳定引用：

```text
attachment_ref = attachment:<uuid>
type           = image
mime           = image/png
display_name   = screenshot.png
asset          = 当前已有的 managed file / LocalArtifactRef / Image part
```

它不保存：

- 当前模型能不能看图；
- 当前是否配置识别模型；
- A/B/C 状态；
- 自动生成的图片描述；
- 当前 Provider 的临时 file id / URL；
- 一次请求中的 `/upload/...` locator；
- “这张图当前不可读”之类能力状态。

`attachment:<uuid>` 是身份，path 只是 locator。模型切换、文件重新 materialize 或 Provider 变化都不能改变附件身份。

### 1.2 Request Projection：只决定本次怎么给模型

投影层只回答一个问题：

> **当前 resolved chat model / Provider 能否在本次请求中原生接收这个 Image？**

答案只有：

```text
NATIVE
REFERENCE_ONLY
```

这里没有 `DERIVED`。投影阶段禁止调用其他模型。

### 1.3 Attachment Inspection：需要内容时才显式读取

当前聊天模型不能原生看图、但系统配置了有效的附件识别模型时，Runtime 自动提供：

```text
inspect_attachments
```

主模型根据任务需要决定是否调用。图片仅仅存在于历史中，不会触发识别模型。

---

## 2. 做减法后的明确删除项

本轮不是“OCR → Attachment Analysis”逐项改名。以下旧结构直接删除：

- 自动 OCR / visual observation；
- `OcrTransformer`；
- `OcrPrompt.kt` / `DEFAULT_OCR_PROMPT`；
- `ImageInputAdapter.observe()`；
- `ImageAdaptCapability.DERIVED`；
- `<attachment_observation>`；
- observation cache / `image_observation_cache.json`；
- `CurrentImageInputUnavailableException` 这类“聊天模型不能看图 => turn 失败”的路径；
- Child artifact 自动 observation；
- 用户可配置的 OCR / attachment-analysis Prompt；
- 为识别结果新增 `AttachmentDerivative` / analysis cache / analysis entity；
- 为 A/B/C 新增持久化状态或 delivery receipt；
- 为“是否启用识别”再增加一个布尔开关。

本轮也**不新增**一套平行的 `AttachmentAsset` 数据库，只为了支持识别工具。现有 Conversation Image、stable `attachment_ref`、managed artifact 和 Tool execution 已经足够支撑当前目标。如果未来文件同步/生命周期治理确实需要规范化 Asset 表，应作为独立问题设计，不能作为本轮多模态 fallback 的附带复杂度。

---

## 3. A / B / C：只用于解释场景，不做领域模型

A/B/C 是设计文档中的行为简称，不需要在代码里持久化，也不要求建立 `AttachmentMode` 三态对象。

运行时只需要两个事实：

```text
nativeImageSupported: Boolean
inspectionModelAvailable: Boolean
```

由此自然得到：

| 场景 | 当前聊天模型原生 Image | 有效附件识别模型 | Model View | Tool Set |
|---|---:|---:|---|---|
| A | 是 | 任意 | attachment ref + native Image | 不注入 fallback tool |
| B | 否 | 是 | attachment ref only | 注入 `inspect_attachments` |
| C | 否 | 否 | attachment ref only | 无识别工具 |

关键规则：

1. B/C 的附件事实与引用上下文完全相同。
2. A/B/C 不写入 Conversation。
3. A/B/C 不进入数据库。
4. 当前模型不能看图不是 attachment failure。
5. 真正的 attachment failure 只包括：ref 不存在、资产丢失、损坏、不安全、类型不支持或无法读取。
6. 每个新 run 根据实际 resolved model 和 settings snapshot 重新计算能力。

模型动态切换因此不需要迁移历史：

```text
A -> B/C   去掉本次 native media；历史不变
B/C -> A   重新从原始 Image 投影 native media；历史不变
B <-> C    只改变本次 tool set；历史不变
```

---

## 4. 持久化：只持久化事实和真实 Tool Result

这是本轮架构收敛的核心。

### 4.1 Conversation

继续持久化原始 `UIMessagePart.Image`、附件 metadata 和 stable `attachment_ref`。请求投影产生的提示、媒体 relocation、能力说明都不回写 Conversation。

### 4.2 Tool artifact

`generate_image` 和其他工具产生图片时，继续使用现有：

```text
Tool execution
+ LocalArtifactRef / managed file
+ UIMessagePart.Image
+ stable attachment_ref
```

图片生成成功与 Caller 是否能看图无关。

### 4.3 `inspect_attachments` 结果

识别工具是普通显式 ToolCall：

```text
Assistant ToolCall
-> inspect_attachments
-> Text Tool Result
```

其调用参数和结果自然进入现有 Turn / Tool execution 持久化。**不再建立任何独立“视觉派生结果”存储。**

这意味着过去确实调用识别工具得到的文字证据会像其他工具结果一样存在于历史中；它是“某次工具调用的结果”，不是附件本体，也不会在模型切换后被系统偷偷重新解释。

### 4.4 不持久化 request capability

以下全部是 request/run-time 派生值，不持久化：

```text
nativeImageSupported
inspectionModelAvailable
是否注入 inspect_attachments
本次 Image placement
本次 capability hint
```

### 4.5 不新增 Delivery Receipt

本轮不为 NATIVE / REFERENCE_ONLY 新增 `AttachmentDeliveryReceipt`。

Provider wire 是否正确通过协议测试验证；真正需要持久化的是附件和工具执行事实，而不是“某次请求模型当时能否看到像素”。如果未来 Provider file handle 需要复用，可做 Provider-local cache，但它不是 Conversation 语义。

---

## 5. 配置：只保留一个可选模型

### 5.1 唯一新配置

Settings 只保留：

```kotlin
val attachmentInspectionModelId: Uuid? = null
```

DataStore：

```text
attachment_inspection_model
```

`null` 就是“未配置”。不要再用随机 UUID 代表空值。

### 5.2 不再存在 Prompt 配置

不新增：

```text
attachmentInspectionPrompt
attachmentAnalysisPrompt
DEFAULT_ATTACHMENT_ANALYSIS_PROMPT
```

原因：`inspect_attachments` 的系统指令属于工具实现契约，而不是用户配置。它应该短、稳定、版本随代码管理；真正的任务意图已经由 Tool 参数 `request` 提供。

因此旧 `ocrPrompt` **不迁移到新字段**，而是直接淘汰。

### 5.3 UI

建议放在现有模型/多模态设置中，只提供一个选择器：

```text
附件识别模型
[ 未配置 / Model ... ]
```

说明保持简洁：

> 当前聊天模型不能直接读取图片时，可按需使用该模型识别附件内容。

规则：

- 不增加 Enable 开关；选择 `未配置` 即关闭能力。
- 选择器只展示声明支持 IMAGE input 的模型。
- Runtime 仍需重新验证 model/provider 当前是否存在且可用。
- 已选择模型被删除/Provider 被移除时，不自动选择另一个模型；本次自然退化为 C。
- 当前聊天模型本身支持 Image 时，配置仍可保留，但不注入 fallback `inspect_attachments`。

### 5.4 Run snapshot

每个 Master / Target run 开始时固定：

```text
resolved chat model
settings snapshot
resolved attachmentInspectionModel
final tool set
```

run 中途修改设置不改变已经暴露给 Provider 的 tool schema；下一 run 生效。

---

## 6. 从旧 OCR 到新配置的一次性迁移

迁移必须明确是 **cutover**，不是长期兼容层。

旧值：

```text
ocr_model
ocr_prompt
```

新值：

```text
attachment_inspection_model
```

迁移规则：

1. 如果新 key 已存在，以新值为准。
2. 如果只有旧 `ocr_model`，且 ID 能解析到当前存在、支持 IMAGE 的模型，则写入 `attachment_inspection_model`。
3. 旧模型不存在、Provider 不存在或模型不支持 IMAGE，则新值为 null，不猜测替代模型。
4. `ocr_prompt` 不迁移。无论旧值是默认还是用户自定义，都不进入新运行时模型。
5. 完成迁移后删除 `ocr_model` 与 `ocr_prompt`。
6. best-effort 删除旧 observation cache 文件；删除失败只记录日志，不阻塞启动。
7. 旧备份恢复如果包含 `ocrModelId`，导入边界允许一次性映射到 `attachmentInspectionModelId`；`ocrPrompt` 忽略。
8. Runtime `Settings`、UI state、工具实现都不能为了兼容继续保留 `ocrModelId` / `ocrPrompt` alias。

如果现有 Settings/backup 有版本迁移入口，应在版本迁移中完成；不要让普通 settings read path 永久携带“新 key 不存在就读取旧 OCR key”的分支。

---

## 7. Request Projection：合并旧 Transformer，而不是再加层

上一版方案提出 `AttachmentContextTransformer + AttachmentProjectionTransformer` 两层。当前目标是进一步做减法：**用一个 request-only Transformer 完成附件提示和 Image 保留/去除。**

目标：

```text
ToolArtifactReplayTransformer
  -> AttachmentProjectionTransformer
  -> Provider encoding
```

`AttachmentProjectionTransformer` 只做三件事：

1. 递归遍历顶层消息与 `Tool.output` 中的 Image。
2. 在 model view 中加入短稳定引用，例如：

```text
[Attachment ref=attachment:8f2... type=image mime=image/png name="screenshot.png"]
```

3. 当前请求支持 native image 时保留 Image；否则仅保留引用文本。

禁止它做：

- 调用附件识别模型；
- 读写分析缓存；
- 写回 Conversation；
- 因聊天模型不能看图而抛 turn-level failure；
- 决定 Assistant 是否应该分析图片。

这样可直接收敛当前：

```text
AttachmentRefHintTransformer
+ AttachmentInputTransformer
+ ImageInputAdapter
```

而不是把三者全部换一组新名字继续存在。

### 7.1 Capability hint

只在当前 model view 确实包含 reference-only Image 时增加一次短提示，不对每张图重复：

B：

```text
Image contents are not directly visible in this run. Use inspect_attachments when visual details are needed.
```

C：

```text
Image contents are not available in this run. Do not infer visual details from attachment references.
```

提示是 request-scoped，不持久化。

### 7.2 Native capability

实现上不需要建立复杂 `A/B/C` registry。保留一个小的纯能力判断即可：

```text
canDeliverNativeImage(resolvedModel, provider/profile) -> Boolean
```

它应使用项目已有的 Model / Provider 能力信息，并由 Provider wire tests 校验真实协议支持。若 Provider 对 Tool Result 中媒体 placement 有额外限制，仍由 Provider projection/encoder 放到协议允许的位置；不要因此重新引入自动识别 fallback。

---

## 8. `inspect_attachments`：唯一新增的运行时能力

### 8.1 定位

工具名确定为：

```text
inspect_attachments
```

它表示“按需读取附件内容”，不是 OCR，也不是用户手工启用的 LocalTool。

属性：

- Runtime capability tool；
- read-only；
- 不需要审批；
- Master / Target 共用；
- 只在 B 中注入；
- 当前只支持 Image；
- 不因当前历史是否已经有图片而改变注册状态。

即使 run 开始时没有图片，B 仍注册它，因为后续 `generate_image` / Tool / Child 可能产生图片。这样同一个 run 的 tool schema 稳定。

### 8.2 Tool description

```text
Inspect one or more attachments when their content is needed for the task. Currently supports images. Specify the evidence, text, details, or comparison you need.
```

它是引导式描述，不要求“有图必须调用”，也不让工具自己猜整项用户任务。

### 8.3 参数

只保留两个参数：

```json
{
  "attachments": ["attachment:...", "attachment:..."],
  "request": "Compare the visible error messages in these screenshots."
}
```

契约：

```text
attachments : string[]  required, 1..4
              只接受 stable attachment:<uuid>
              输入顺序即分析顺序

request     : string    required
              明确本次需要的可见证据、文字、细节或比较目标
```

**不接受任意文件 path 作为公开工具契约。** `/upload/...` 仍可存在于其他文件工具或 model view，但 `inspect_attachments` 统一使用 stable ref，避免同时维护两套身份语义。

不增加：

```text
mode
ocr
language
quality
detail
model
provider
```

模型/provider/质量策略由 Runtime 决定，任务意图由 `request` 表达。

### 8.4 多附件

- 一次 1..4 个 Image。
- 先通过现有 `AttachmentResolver` 解析全部 refs。
- 任一 ref 无法解析、资产不可读或类型不支持，本次调用整体失败，不静默跳过。
- 所有图片在**一次附件识别模型调用**中按输入顺序提供。
- 内部标签带 ref/name，保证多图比较无歧义。

这比 LLM 连续调用单图工具更省调用、也更适合真正的跨图任务。

### 8.5 内部识别指令

系统指令是工具实现常量，不是 Settings：

```text
Analyze only the provided attachments for the caller's request. Use the attachment labels, report relevant visible evidence, transcribe text when useful, compare attachments when requested, and state uncertainty. Do not perform unrelated tasks.
```

`request` 作为独立用户任务输入。不要把用户 Prompt 模板、旧 OCR Prompt 或历史上下文拼进固定 system instruction。

### 8.6 返回值

成功只返回一个普通 Text Tool Result：附件识别模型针对 `request` 的结果。

不额外包装：

```text
status=completed
artifact_delivery
analysis_model
cache_hit
```

这些都不是主模型完成任务所需的语义，ToolCall 参数已经记录了输入附件。

失败沿用项目现有 Tool failure 机制，不再发明第二套状态信封。建议保留少量机器可判别 reason：

```text
attachment_not_found
unsupported_attachment_type
inspection_model_unavailable
inspection_failed
```

### 8.7 不缓存

本轮不实现附件识别结果 cache。

原因：结果由 `attachments + request + model` 共同决定；新 cache 会重新引入旧 observation subsystem 的生命周期、失效和持久化问题。显式 Tool Result 已经是正确的历史记录。如果以后真实性能数据证明重复调用值得缓存，再独立设计。

### 8.8 未来类型

工具名和 `attachments[]` 从第一天使用通用 Attachment 语义，因此未来可扩展 Document / Audio / Video。

但本轮**不创建** document/audio/video analyzer interface、registry 或空实现。遇到非 Image 直接 `unsupported_attachment_type`。等真实需求出现时再扩展 resolver 和模型选择规则。

---

## 9. Tool Set：运行时注入，不进入 Assistant 配置

注册规则：

```text
if (canDeliverNativeImage(resolvedModel)) {
    // A: 不注入 fallback tool
} else if (valid attachmentInspectionModel exists) {
    // B
    add(inspect_attachments)
} else {
    // C: 无 tool
}
```

不要把 `inspect_attachments` 放进：

- Assistant `LocalToolOption`；
- Target tool allowlist 的用户配置；
- MCP；
- 独立“enable attachment inspection”开关。

`GenerationToolSetFactory.buildTools()` 应显式接收当前真实 `resolvedModel`。尤其 Target 可以在运行时继承 Caller model，不能继续通过 `settings.getChatModel(target)` 猜测。

建议只新增一个紧凑的 `AttachmentInspectionToolFactory`（或同职责函数）负责创建工具和调用识别模型；不要再拆出 capability registry、prompt repository、analysis service、cache repository 等层级，除非后续出现第二个真实调用方。

---

## 10. `generate_image`：普通图片 Artifact，不设计特殊回灌

`generate_image` 成功后必须保证：

```text
managed/generated file
+ Tool artifact
+ UIMessagePart.Image
+ stable attachment_ref
```

stable ref 应在 Tool Result 进入历史前确定，不能等未来 Transformer 临时生成。

后续 Agent step 与用户上传图完全相同：

### A

```text
attachment ref + native Image
```

当前聊天模型直接读取生成结果。不需要 `inspect_attachments`。

### B

```text
attachment ref
+ inspect_attachments available
```

不会自动检查生成图。只有任务需要，例如“生成并确认文字是否正确”，主模型才显式调用识别工具。

### C

```text
attachment ref only
```

模型知道图片成功生成，但不知道具体视觉内容，不能声称已经验证画面。

这条规则同样覆盖历史 `generate_image` 和其他 Tool output Image。`ToolArtifactReplayTransformer` 继续在附件投影前恢复 artifact；不新增任何“生成图自动 OCR/自动验证”路径。

---

## 11. 子助手：不再有第二套多模态机制

Target 只是另一个拥有自己 resolved model 和 settings snapshot 的 run。

### 11.1 Main -> Target

`assistant_call attachments[]`：

```text
attachment refs
-> AttachmentResolver
-> 验证资产真实存在/安全
-> 原始 Image + stable ref 写入 Child task
-> Target 使用统一 AttachmentProjectionTransformer
-> Target 使用统一 runtime tool set
```

删除：

```text
Target 不支持 IMAGE + 无 OCR => 拒绝 assistant_call
```

能力不足不是附件失败。Target C 也可以继续完成不依赖视觉内容的部分；若任务必须读取图片，它会因为没有视觉内容/工具而正常说明限制，而不是 Coordinator 在任务开始前把整个委托拒绝。

### 11.2 Target resolved model

Target 工具集必须使用实际 `runSpec.model`。Master/Target 共享同一个 `GenerationToolSetFactory` 入口，不维护 Target 专用图片 fallback。

### 11.3 Target 内 `generate_image`

Child 生成图片后先按 **Target 自己的 A/B/C** 进入下一 Agent step。最终交付给 Caller 时，再根据 Caller 当前能力独立投影。两次判断互不继承。

### 11.4 Child -> Caller

Child artifact 的稳定引用始终是交付事实。现有 `extras=artifacts` 可以继续控制是否把 artifact **内容**投影给 Caller，但不改变 artifact 是否存在。

目标行为：

```text
Caller A + extras=artifacts  -> ref + native Image
Caller B + extras=artifacts  -> ref；需要时 inspect_attachments
Caller C + extras=artifacts  -> ref only
extras 不含 artifacts        -> 轻量 artifact refs，按现有 contract
```

删除 Child -> Caller 自动 observation。

### 11.5 `artifact_delivery` 做减法

新的能力模型下，`derived / unavailable / native / reference` delivery 状态不再承担必要信息：

- artifact 是否存在由 artifact/ref 表达；
- 当前是否收到 native media 由实际 message parts 表达；
- B 是否能识别由 tool set 表达；
- C 的限制由 request capability hint 表达；
- artifact 真丢失由现有 artifact availability/failure 表达。

因此**目标领域模型不保留 `artifact_delivery` 状态**。如果该字段已经成为必须兼容的外部 Tool Result 契约，可在输出 adapter 暂时保留兼容值，但不得继续驱动内部逻辑，并应在兼容窗口结束后删除。

---

## 12. 目标请求管线

Master 与 Target 使用同一条链：

```text
Durable messages
  -> replay-safe / ordinary context transforms
  -> ToolArtifactReplayTransformer
  -> AttachmentProjectionTransformer
       - emit stable ref hint
       - keep native Image OR reference only
       - add one request-scoped capability hint when needed
  -> Provider encoding
```

Tool set：

```text
assistant-selected tools
+ workspace / skills / MCP
+ runtime capability tools
    └─ inspect_attachments only when B
```

附件识别模型只有一条入口：

```text
explicit inspect_attachments ToolCall
```

不得从 Transformer、Provider encoder、SubAssistantCoordinator、artifact replay 或 UI 自动触发。

---

## 13. 文件级实施方案

### 13.1 Settings / migration

**`PreferencesStore.kt` / `Settings`**

- 删除 `ocrModelId`、`ocrPrompt`。
- 新增 `attachmentInspectionModelId: Uuid? = null`。
- 新增 `ATTACHMENT_INSPECTION_MODEL = stringPreferencesKey("attachment_inspection_model")`。
- null 时删除 key。
- 增加一次性旧 `ocr_model` 迁移并清除旧 key。
- `ocr_prompt` 只删除，不迁移。
- backup restore legacy adapter 只迁移旧 model ID。

**设置 UI**

- 删除 OCR model / prompt UI。
- 新增一个“附件识别模型”选择器。
- 明确“未配置”。
- 只列 IMAGE-capable model。

### 13.2 删除旧输入适配链

删除：

```text
OcrTransformer.kt
OcrPrompt.kt
ImageInputAdapter.kt
```

删除对应 observation cache、capability enum 和 fail-fast exception。

将：

```text
AttachmentRefHintTransformer.kt
AttachmentInputTransformer.kt
```

收敛为：

```text
AttachmentProjectionTransformer.kt
```

一个递归 pass 同时生成稳定附件 hint 和 native/reference projection。

如果实施中发现某个通用 Transformer 还承担非图片职责，保留该职责即可；不要为了满足文件名强行合并无关逻辑。但最终不能继续存在“自动识别 adapter”这一层。

### 13.3 `AttachmentResolver.kt`

继续作为 stable ref -> managed asset 的权威入口。

为 `inspect_attachments` 增加最小批量入口即可，例如：

```text
resolveImages(refs: List<String>): List<ResolvedImage>
```

规则：

- 只接受 `attachment:<uuid>`；
- 1..4；
- all-or-nothing；
- 复用现有安全/存在性校验；
- 不复制 path/remote fetch 逻辑到 Tool。

不要新增通用 analyzer registry。

### 13.4 新增 `AttachmentInspectionTool`

建议一个文件即可：

```text
data/ai/tools/AttachmentInspectionTool.kt
```

职责：

- Tool schema / description；
- refs 校验；
- 调用 `AttachmentResolver`；
- 获取 run snapshot 中的 inspection model；
- 一次多图模型调用；
- 返回 Text 或现有 Tool failure。

固定 system instruction 可作为同文件 private constant，不再新建 Prompt 配置文件。

### 13.5 `GenerationToolSetFactory.kt`

- `buildTools()` 增加 `resolvedModel: Model?`。
- Web search 等已有 capability 判断也优先使用该真实模型，避免 Target 运行时模型与静态 Assistant 设置不一致。
- 当非 native + inspection model valid 时添加 `inspect_attachments`。
- 不根据当前 message 是否包含 Image 决定 tool schema。

### 13.6 `ChatService.kt`

- 所有 Master 生成入口向 tool factory 传本次 resolved model/settings snapshot。
- transformer 链替换为新的单一附件投影。
- 删除 capability mismatch fail-fast catch。
- 每个 Tool step 后下一 Provider request 继续从 durable/tool artifact 事实重新投影。

### 13.7 `generate_image` / artifact replay

- 确保成功 Image 在持久化 Tool Result 前已经有 stable `attachment_ref`。
- 保持 `ToolArtifactReplayTransformer` 在附件投影前。
- 不新增自动 inspection。

### 13.8 `SubAssistantCoordinator.kt`

- 删除视觉能力 preflight rejection。
- 入站只验证 attachment ref/asset。
- Target `buildTools()` 传 `runSpec.model`。
- 删除自动 observation 调用。

### 13.9 `SubAssistantResultProjection.kt`

- 删除 `DERIVED` 分支。
- 不调用识别模型。
- 输出 stable artifact refs；Caller native projection 交给统一附件投影。
- 内部删除 `artifact_delivery` 驱动逻辑；如需外部兼容只留 adapter。

### 13.10 不新增的文件/层

本轮明确不要为了“架构完整”创建：

```text
AttachmentAnalysisRepository
AttachmentDerivativeStore
AttachmentInspectionCache
AttachmentCapabilityRegistry
AttachmentDeliveryReceiptRepository
AttachmentInspectionPromptSettings
ImageAnalyzer / DocumentAnalyzer / AudioAnalyzer / VideoAnalyzer 空接口族
```

只有真实第二个调用方出现后再抽象。

---

## 14. 实施顺序：一次切换，不长期双轨

### Phase 1 — 先写 RED 测试

锁定新行为：

- 图片存在不会自动触发第二模型。
- C 不因图片存在而 fail-fast。
- Tool Result / generated image 都有 stable ref。
- B 才拥有 `inspect_attachments`。
- Target 使用 `runSpec.model`。

### Phase 2 — Settings cutover

1. 新增 `attachmentInspectionModelId?`。
2. 一次性迁移旧 model ID。
3. 删除 prompt 配置和 UI。
4. 删除旧 cache。
5. backup legacy import 覆盖测试。

这一步结束后 Runtime 不再读取 OCR 字段。

### Phase 3 — 删除自动识别链

1. 合并附件 hint/projection。
2. 删除 `ImageInputAdapter.observe()` / DERIVED / exception。
3. 删除 `OcrTransformer` / prompt / cache。
4. 让 B/C 都变成 reference-only projection。

### Phase 4 — 新增 `inspect_attachments`

1. 批量 stable ref resolver。
2. 1..4 image 单次识别调用。
3. runtime tool injection。
4. 固定 system instruction + task `request`。
5. 无 cache。

### Phase 5 — `generate_image` + Sub-assistant 统一

1. 验证 generated image stable ref。
2. A/B/C 下一 step。
3. 删除 Target modality rejection。
4. 删除 Child -> Caller automatic observation。
5. 收敛/删除 `artifact_delivery` 内部状态。

### Phase 6 — 清理

- 全仓删除旧运行时 OCR 语义。
- 更新旧多模态/子助手文档到新语义。
- 单测、集成测试、Provider wire tests、Android build 全绿。

实施过程中不要保留“旧自动 OCR 和新 inspect tool 同时工作”的长期兼容模式。迁移完成后直接切到新链。

---

## 15. 验收矩阵

### 15.1 来源 × A/B/C

| 图片来源 | A | B | C |
|---|---|---|---|
| 当前用户上传 | ref + native | ref + inspect tool | ref only |
| 历史用户图片 | ref + native | ref + inspect tool | ref only |
| 当前 `generate_image` | 下一 step native | 下一 step 可 inspect | ref only |
| 历史 `generate_image` | native replay | ref + inspect | ref only |
| nested Tool output Image | native/协议合法 placement | ref + inspect | ref only |
| Target inbound | Target native | Target inspect | Target ref only |
| Child -> Caller | Caller native when requested | Caller ref + inspect | Caller ref only |

### 15.2 动态切换

覆盖：

```text
A -> B
A -> C
B -> A
B -> C
C -> A
C -> B
```

断言：

- Conversation 不变化；
- attachment ref 不变化；
- 不写入 capability state；
- 只变化 native media / runtime tool；
- 切回 A 能重新读取原始 Image。

### 15.3 `inspect_attachments`

覆盖：

- 1 / 2 / 4 张图；
- 输入顺序稳定；
- 多图只调用识别模型一次；
- 只接受 `attachment:<uuid>`；
- missing / corrupt / unsupported；
- inspection model 被删除/Provider 不可用；
- provider failure；
- 成功只产生 Text Tool Result；
- ToolCall / Tool Result 正常进入现有持久化；
- 未显式 ToolCall 时识别模型调用次数严格为 0。

### 15.4 Settings / migration

覆盖：

- `attachmentInspectionModelId=null` 正确持久化为无 key；
- 旧有效 `ocr_model` -> 新 model；
- 旧无效 model -> null；
- `ocr_prompt` 不迁移；
- 旧 key 被清除；
- 旧 cache 被 best-effort 清理；
- 旧 backup 可导入 model ID；
- 新 Settings 序列化不再含 OCR 字段。

### 15.5 Sub-assistant

覆盖：

- Target C 不因有附件而拒绝委托；
- 真正 missing/unsafe attachment 仍拒绝；
- Target B 有识别工具；
- Target 的工具能力依据 `runSpec.model`；
- Child generated image 在 Child 内按 Target 能力处理；
- Caller 侧不再自动识别 Child artifact；
- 无 `artifact_delivery` 也能完整表达正常结果；若暂时保留兼容字段，内部逻辑不依赖它。

### 15.6 Provider wire

至少覆盖 OpenAI Chat/Responses、Anthropic、Gemini 当前项目支持路径：

- A 的普通 user Image 不被静默删除；
- A 的 Tool output/generated Image 在协议不允许原位置时被合法投影；
- B/C wire 不携带图片二进制；
- Tool call/result 配对不因 media relocation 破坏。

---

## 16. 最终清理标准

实现完成后，运行时代码、Settings、UI 中不得再存在：

```text
ocrModelId
ocrPrompt
OCR_MODEL
OCR_PROMPT
OcrTransformer
DEFAULT_OCR_PROMPT
ImageAdaptCapability.DERIVED
<attachment_observation>
image_observation_cache
自动 visual observation
```

旧 `ocr_model` / `ocr_prompt` 字符串只允许在：

```text
一次性 migration
migration tests
历史 changelog / design migration note
```

同时不应出现“新 OCR 换皮”：

```text
attachmentAnalysisPrompt setting
hidden analysis cache
background attachment analysis
DERIVED renamed to another enum
persistent A/B/C capability
```

---

## 17. 最终不变量

```text
附件存在 != 当前模型能读取附件
attachment_ref != path
附件事实 != 附件识别结果
模型能力 != Conversation 历史
识别工具可用 != 必须调用
```

系统最终只保留：

```text
Conversation / Tool artifact
        │
        └── stable attachment_ref
                │
                ▼
       request-time projection
          ├─ native Image
          └─ reference only
                │
                └─ optional inspect_attachments
                       │
                       └─ normal persisted Tool Result
```

这比旧方案少了自动 OCR、派生缓存、三态 Adapter、Prompt 配置、额外持久化和子助手特殊 fallback，同时完整覆盖用户图片、`generate_image`、Tool artifact、子助手与动态模型切换。
