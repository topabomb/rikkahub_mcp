# 上游同步检查记录

> 本文档记录对原项目 [RikkaHub](https://github.com/rikkahub/rikkahub) 提交的检查历史，避免重复检查。

---

## 检查点格式

```
### YYYY-MM-DD - 版本号

**检查范围**：commit_hash1..commit_hash2

**有价值提交**：
- [ ] 提交描述（影响范围）

**已同步**：
- 提交描述 → 对应的本地修改

**跳过**：
- 提交描述（原因）
```

---

## 检查记录

### 2026-06-20 - 检查 2.3.2 更新

**检查范围**：`5b9be301..2026-06-19 最新`

**原项目信息**：
- Fork 基线：2.3.1（versionCode 164）- 2026-06-18
- 当前上游：2.3.2（versionCode 165）- 2026-06-19

**有价值提交**：

- [x] `fix: OCR model requests now include provider advanced custom body/headers` - OCR 请求修复 → 已同步（ca7c956e）
- [x] `fix: 平板横竖屏旋转后模态抽屉残留打开无法关闭` - UI 修复，平板适配 → 已同步（1837b9d5）
- [x] `chore: 更新依赖和 baseline prof` - 依赖更新，性能优化 → 已同步（fd5b8ab3）

**跳过**：

- `feat: 支持 Firecrawl 无 API Key 模式` - 已在清理中移除 Firecrawl
- `适配小米 MiMo ASR + 阶跃星辰 Step ASR` - 已在清理中移除 ASR 相关代码
- `适配 aiping.cn 思考参数` - 未使用的 Provider
- `移除开发者页面和AI日志追踪` - 我们可能需要保留调试功能

**待评估**：

- `docs: 更新 claude.md & agents.md` - 文档更新，可参考
- `feat(chat): 消息底部显示编辑文件列表并支持导出/分享` - 功能增强，可考虑
- `feat(setting): MCP 服务器名称限制为英文和数字并提示非法输入` - MCP 功能增强

---

## 同步建议

### 优先级 P0（建议同步）

1. ~~**平板横竖屏修复**~~ ✅ 已同步
2. ~~**OCR 请求修复**~~ ✅ 已同步

### 优先级 P1（可考虑）

1. ~~**依赖更新**~~ ✅ 已同步
2. **MCP 名称限制** - 功能增强（未在上游找到）

### 优先级 P2（暂不需要）

1. 新 ASR Provider - 我们已简化 ASR 模块
2. Firecrawl 支持 - 我们已移除

---

## 本地改进（偏离上游）

### 2026-06-20 - 修复废弃 API

**问题**：上游使用了已废弃的 `currentWindowDpSize()` API，编译时会产生警告。

**修复**：
- 移除：`import androidx.compose.material3.adaptive.currentWindowDpSize`
- 添加：`import androidx.compose.ui.platform.LocalDensity` + `import androidx.compose.ui.platform.LocalWindowInfo`
- 替换：使用 `LocalWindowInfo.current.containerSize` + `LocalDensity` 计算窗口尺寸

**文件**：`app/src/main/java/net/weero/mersix/pilot/ui/pages/chat/ChatPage.kt`

**影响**：功能逻辑不变，仅 API 调用方式更新

---

## 同步检查频率

建议每 **2 周** 检查一次上游提交，或在以下情况时检查：
- 原项目发布新版本
- 我们遇到已知问题需要上游修复
- 计划大版本更新前

---

*最后更新：2026-06-20*
