# 功能迭代清单

> 本文档记录 Fork 精简落地（0.0.2）后的功能迭代历史。
> 精简过程见 `fork-simplification-plan.md`，精简前架构见 `original-architecture.md`。
> 每次功能迭代提交时更新本文档。

---

## 0.0.3（versionCode 3）— 2026-06-19

### 新增

- **MCP 服务器分享**：MCP 列表页每个条目新增分享按钮，点击弹出二维码 + 系统文本分享。新增 `McpServerConfig.encodeForShare()` 导出 OpenCode 格式 JSON，与 `parseMcpServersFromJson` 双向兼容（扫码/相册/粘贴三种导入方式均可识别）。
- **Provider 粘贴导入**：Provider 导入对话框新增"粘贴配置字符串"选项，复用 `decodeProviderSetting` 解码，与扫码、相册共三种导入方式统一。

### 测试

- 新增 7 个 `encodeForShare` 单元测试（streamable_http/sse 往返、headers 含/空、空名称回退、排除 id/tools/enable）。

### 文档

- `architecture.md` → `original-architecture.md`（更名，明确为精简前架构）
- 新建 `changelog.md`（功能迭代清单）
- 整理 `fork-simplification-plan.md`、`AGENTS.md` 文档维护规则
