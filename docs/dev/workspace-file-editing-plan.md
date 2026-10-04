# 工作区文件查看与编辑执行计划

## 目标与范围

修正本地和远程工作区正文视口，统一常见文本文件识别，并在源码预览、编辑中使用已有语法高亮。
本次修改限 Android 仓库，保留已有 Starter 协议变更，不推送，不调整版本或 changelog。验证完成后按用户指令提交本次范围。

## 实现选择与职责

- `ui/components/files` 维护共享文件类型/语言识别、`FileEditorState`、`FileTextEditor` 和原生 IME 适配。
  本地、远程和 Skill 复用一个正文组件，移除原页面内的分类表和旧组件位置。
- 正文预览与编辑使用同一原生多行 `EditText`、同一 Editable。现有大正文和 IME 查询协议继续适用。
  全页模式由有限视口控制高度及滚动，不施加表单行数上限；表单保留原最小/最大行数。
  Markdown 富文本、图片、PDF 保持各自明确的展示职责。
- 文件分类只选择读取方式，语言识别只选择已有 grammar；不支持语法的文本仍可阅读和编辑。
  HTML/XML/JSON 作为源码，不执行 HTML，也不要求编辑中的 JSON/XML 已经完整合法。
  常见无扩展名文本可尝试读取，读取层明确拒绝二进制、非法编码及超限文件。
- `highlight` 继续负责解析与 token 样式；文件组件将 token 转为其拥有的颜色 span。
  样式不是可编辑正文，不替换 buffer，不移除 selection/composing/其他组件的 span。
  快速输入合并计算，后台解析有大小预算，只有原 state、语言及正文 revision 匹配时应用结果。
  大正文降级纯文本，复制和保存始终使用完整源码。
- 本地 query/application 和远程 service/VM 保持现有读取、保存、原 Session/文件身份、ETag、UNKNOWN、取消和草稿协议。
  不增加正文数据库、通用插件框架、第二个编辑状态或持久后台任务。

## 实施顺序

1. 统一文本类型与语言识别，迁移共享组件及真实调用者。
2. 在共享组件内完整实现视口、只读/编辑和有界语法高亮，复用现有配色。
3. 补齐本地文本读取有效性，更新两类工作区页面及必要文档。
4. 增加高价值行为测试，验证正文保全、跨行语法、迟到结果与实际可见区域。
5. 完成定向验证及串行全量门禁，在专用设备观察实际页面并核对最终 diff。

## 验收

- 长文件正文真实绘制到视口下部，滚动到末尾；只读、编辑、IME 开关、横屏、大字体均可访问正文和动作。
- 两类工作区均可打开常见 HTML/XML/JSON/Python 及配置文本，特殊文件名识别一致。
- JSON 属性、字符串及数字、HTML/XML 标签、Python 关键字与跨行字符串具有正确主题颜色。
- 高亮更新不改变文字、dirty、选区或 composing，旧结果不能应用到新正文；大文件保持完整且可操作。
- 保存失败保留草稿，远程冲突及 UNKNOWN 仍按原协议处理，不自动重放。
- 先运行定向 JVM/设备用例，再执行 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1`
  及 `connectedDebugAndroidTest --no-parallel --max-workers=1`。真实服务和 OEM 设备未执行时单独注明。

当前事实在 `docs/references/ui-architecture.md` 和 `workspace-architecture.md` 同步维护；本文件仅记录本次执行方案。

## 执行结果（2026-10-04）

上述实施步骤已完成。文件识别集中在 `FileFormat.kt`，原编辑器与 IME 适配迁入共享文件组件目录；
全页视口、原生单 buffer 和现有高亮解析器共同承载源码预览与编辑。读取和保存继续由原服务负责。

- 定向 JVM、Android 编译及设备行为验证通过。
- 全量 `test assembleDebug lintDebug assembleRelease --no-parallel --max-workers=1` 通过；
  JUnit 报告合计 3,028 项通过、12 项因 Windows shell/符号链接条件跳过。
- 全量 `connectedDebugAndroidTest --no-parallel --max-workers=1` 通过；
  专用 `emulator-5560` 上 389 项通过、20 项因显式 live 场景或设备条件跳过。
  编辑器、Skill 草稿、本地文件页、远程文件页和导航恢复的 39 项均通过且无跳过。
- 横屏与 1.5 倍字体另行运行 7 项，全部通过；实际绘制、源码高亮、滚动末尾和键盘下动作可达均已验证。
- 最终 diff、旧调用路径、UTF-8 无 BOM、CRLF 和参考文档范围校验通过。
  真实 Core/Provider、有效麦克风、PRoot 镜像及 OEM 设备场景未执行，不计为通过。

详细报告和截图位于本地构建目录 `build/workspace-file-editing-verification.md`，不作为当前架构参考文档维护。
保留原有 Starter 改动；验证完成后按用户指令提交本次范围，不推送。
