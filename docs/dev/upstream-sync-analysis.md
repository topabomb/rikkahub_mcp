# 上游提交详细分析报告

> 分析日期：2026-06-20
> 分析范围：RikkaHub 2.3.1 → 2.3.2（8 个提交，24 个文件变更）

---

## 一、提交清单

| 提交信息 | 类型 | 影响范围 |
|----------|------|----------|
| fix: OCR model requests now include provider advanced custom body/headers | Bug 修复 | OCR 功能 |
| chore: 更新依赖和 baseline prof | 维护 | 性能优化 |
| chore: 版本号更新至 2.3.2 (165) | 版本 | 无 |
| fix: 平板横竖屏旋转后模态抽屉残留打开无法关闭 | Bug 修复 | UI |
| docs: 更新 claude.md & agents.md | 文档 | 无 |
| feat: 支持 Firecrawl 无 API Key 模式 | 新功能 | Search |
| 适配 aiping.cn 思考参数 | 适配 | Provider |
| 适配小米 MiMo ASR + 阶跃星辰 Step ASR | 新功能 | ASR |

---

## 二、P1 项目详细分析

### 2.1 依赖更新（chore: 更新依赖和 baseline prof）✅ 已同步

**变更内容**：
- 更新项目依赖库版本
- 更新 baseline profile 性能配置文件

**影响评估**：
- ✅ **性能优化**：Baseline Profile 可提升应用启动速度和减少卡顿
- ✅ **安全性**：依赖更新通常包含安全补丁
- ⚠️ **兼容性风险**：需要验证新依赖是否与我们的代码兼容

**同步状态**：✅ 已完成（fd5b8ab3）
- composeBom: 2026.05.01 → 2026.06.00
- material3: 1.5.0-alpha21 → 1.5.0-alpha22

---

### 2.2 MCP 功能增强

**说明**：原始提交列表中未发现 "MCP 服务器名称限制" 相关提交，可能是误读或在其他分支。

---

## 三、待评估项目详细分析

### 3.1 文档更新（docs: 更新 claude.md & agents.md）

**变更内容**：
- 更新 AI 辅助开发的指导文档
- 可能包含新的最佳实践

**影响评估**：
- 📝 **参考价值**：可了解原项目的开发规范
- ⚠️ **适用性**：我们的项目结构已大幅简化

**建议**：**仅参考**
- 不直接合并，但可借鉴其中的通用建议
- 我们的 `AGENTS.md` 已针对简化后的项目定制

---

### 3.2 消息编辑文件列表功能

**说明**：原始提交列表中未发现此功能提交，可能是：
- 在其他分支开发
- 尚未合并到 master
- 误读提交信息

**建议**：**暂不处理**
- 等待功能稳定后再评估

---

### 3.3 MCP 服务器名称限制

**说明**：原始提交列表中未发现此功能提交。

**建议**：**暂不处理**
- 如果需要，我们可以自行实现简单的名称校验

---

## 四、其他有价值的修复

### 4.1 OCR 请求修复（P0）

**问题**：OCR 模型请求未传递自定义 headers/body

**修复代码**：
```kotlin
params = TextGenerationParams(
    model = model,
+   customHeaders = model.customHeaders,
+   customBody = model.customBodies,
),
```

**影响**：影响文档处理功能的正确性

**建议**：**立即合并**
- 修改文件：`app/src/main/java/net/weero/mersix/pilot/data/ai/transformers/OcrTransformer.kt`
- 变更量：+2 行

---

### 4.2 平板 UI 修复（P0）

**问题**：平板横竖屏旋转后模态抽屉残留无法关闭

**影响**：影响平板用户体验

**建议**：**合并**
- 需要查看具体的 Compose 代码修复

---

## 五、合并优先级

### P0 - 立即合并

| 项目 | 工作量 | 风险 | 状态 |
|------|--------|------|------|
| OCR 请求修复 | 低（+2 行） | 低 | ✅ 已同步 |
| 平板 UI 修复 | 中 | 低 | ✅ 已同步 |

### P1 - 建议合并

| 项目 | 工作量 | 风险 | 状态 |
|------|--------|------|------|
| 依赖更新 | 中 | 中 | ✅ 已同步 |
| Baseline Profile 更新 | 中 | 低 | ⏸️ 待处理 |

### P2 - 暂不处理

| 项目 | 原因 |
|------|------|
| MiMo/Step ASR | 已在清理中移除 ASR |
| Firecrawl 无 Key 模式 | 已移除 Firecrawl |
| aiping.cn 适配 | 未使用的 Provider |
| 文档更新 | 仅参考 |

---

## 六、执行建议

### 6.1 立即执行

1. **OCR 修复**：手动应用 +2 行代码
2. **平板 UI 修复**：查看具体修复并应用

### 6.2 本周执行

1. **依赖更新**：
   ```bash
   # 查看上游依赖版本
   git show 5b9be301:gradle/libs.versions.toml
   
   # 对比差异并更新
   ```

2. **Baseline Profile**：
   ```bash
   # 重新生成
   ./gradlew :app:generateBaselineProfile
   ```

### 6.3 记录检查点

更新 `upstream-sync.md`，标记已检查的提交。

---

*分析完成于 2026-06-20*
