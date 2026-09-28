# locale-tui

Android 字符串管理工具，使用 Python 3.12 及以上版本。当前 app、workspace 具有资源目录，维护英文、简中、日文、韩文和俄文；不列出没有 res 目录的模块。

## 运行

```bash
uv run --directory locale-tui --python 3.12 src/main.py
uv run --directory locale-tui --python 3.12 src/main.py --help
```

配置位于 `config.yml`。只有实际翻译或显式 `test-connection` 需要 `.env` 中的 `OPENAI_API_KEY`，可选 `OPENAI_BASE_URL`。不要把凭据提交到仓库。手动编辑和 dry-run 不要求 key，不创建 API client。

## 批量命令

```bash
# 先查看缺失项；仅列 key，不调用模型、不创建目标文件
uv run --directory locale-tui src/main.py translate-missing -m app --dry-run

# 仅翻译指定目标语言；--lang 可重复
uv run --directory locale-tui src/main.py translate-missing -m app -l values-zh

# 仅重译目标文件中已经存在的条目；key 和 regex 取并集
uv run --directory locale-tui src/main.py retranslate -m app --key prompt_label --regex '^assistant_' --dry-run

# 手动修改不调用翻译服务
uv run --directory locale-tui src/main.py add greeting "Welcome" -m app --skip-translate
uv run --directory locale-tui src/main.py set greeting "欢迎" -m app -l values-zh
```

`add` 先保存源语言，再通过同一 batch pipeline 翻译缺失的目标；现有目标值不自动覆盖，更新它们用 `retranslate`。批量命令支持 `--batch-size`、`--concurrency`、`--retries` 覆盖配置，均须大于零。`retries` 是包含第一次请求在内的最大尝试次数，重试只包含失败条目；SDK 不另外重试。

`translate-missing` 区分未定义条目与合法空字符串；显式空串不算缺失。`retranslate` 必须提供至少一个 `--key` 或 `--regex`，无匹配不写入。源或目标的 `translatable="false"` 不自动翻译；内联 XML/xliff、plurals 和 arrays 明确列为未处理，不会被压成纯文本。

格式占位符的数量、实参绑定、类型以及换行必须保留。按 Java Formatter 规则，普通参数独立递增，`<` 引用前一个有效参数，`%%`/`%n` 不消费参数。包含未编号或相对引用时保守保持消费顺序；纯显式 `%1$s`/`%2$d` 可重排，不允许 `%s %d` 变成 `%d %s`。手动值与模型输出使用同一 Android 字符串校验：值是 **XML 解码后的 Android 文本**，直接输入 `&`，不要手动编码成 `&amp;`；XML 序列化负责实体转义。已有 Android 反斜杠转义会保留，裸引号会按 Android 语法处理，包围整串的双引号保留其空白保护语义。

每个目标文件只原子提交其成功条目，保留属性、注释、顺序和其他节点。坏 XML 报路径及原始错误，不当作空文件；写入或替换失败保留原 bytes。翻译期间选中条目被外部修改时拒绝覆盖，刷新后再决定。跨语言文件没有全局事务：部分失败保留已提交文件，列出失败语言/key，CLI 返回非零；取消不发布半写 XML。`add` 中已明确保存的源语言不因后续翻译取消而撤回。

## 术语表

术语按目标语言配置，共用于 CLI、add 和 TUI：

```yaml
translation:
  batch_size: 10
  concurrency: 4
  retries: 3
  glossary:
    values-zh:
      prompt: 提示词
      context: 上下文
```

模板可使用 `{target_language}`、`{source_strings}`、`{glossary}`。未写 `{glossary}` 时仍会在请求末尾附上该目标语言的术语表；不会把简中术语附给日文等其他目标。

## TUI

| 按键 | 动作 |
|---|---|
| `Enter` | 编辑当前条目 |
| `t` | 翻译缺失条目到内存 |
| `s` | 保存修改过的条目 |
| `Delete` | 显式删除当前 key，立即按文件提交 |
| `/` | 搜索 |
| `d` / `m` | 无引用 / 缺译过滤 |
| `r` | 重新读取，保留已有草稿 |
| `Escape` | 返回；存在草稿时再次按下确认放弃 |

翻译不会自动保存。Save 只在对应文件提交成功后清除 dirty 项，部分保存失败仍保留失败草稿供重试。读取失败保留页面内容和草稿，并禁止 Save；修复文件后 Refresh 再操作。外部同时修改了同一条目时，需返回并重新打开以放弃旧草稿、重新编辑。显式 Delete 也通过同一个单文件原子 writer，不承诺跨语言原子删除；部分失败保留未删除条目，可再次删除。

自动草稿会保留翻译时的源文本；CLI 和 TUI 提交时由 XML writer 重读源与目标，拒绝源删除/变更或任一端变为受保护内容，哪怕目标文本未变。TUI Refresh 保留草稿来源，不解除该检查；恢复原资源后可重试，源已永久变更时应放弃旧草稿并重新翻译。显式修改目标值后，该值转为手工草稿，仍受旧文本冲突和 XML 保全检查，但不会无条件禁止手工编辑 `translatable=false`。这些检查不构成跨文件事务。

## 验证

```bash
# 默认全部离线：fake client、临时 XML、CLI 与 Textual headless 行为
uv run --directory locale-tui --python 3.12 --group dev pytest -q

# 只有明确需要真实服务并具备凭据时才执行
uv run --directory locale-tui --python 3.12 --group dev pytest --run-live tests/test_translator.py -v
```

真实 API 测试默认跳过，即使直接指定该测试文件也需 `--run-live`。离线测试禁止创建真实 API client 和访问外网（Windows asyncio 的 loopback 唤醒连接除外），不读取项目 `.env`。离线通过不代表真实翻译质量或服务可用性已验收。
