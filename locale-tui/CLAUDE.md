# locale-tui 维护约定

遵循仓库 [AGENTS.md](../AGENTS.md)。运行、CLI/TUI、配置、写入与验证边界统一由 [README.md](README.md)维护，
不在本文件复制命令或字段清单。

- CLI、TUI 和翻译结果复用原 XML writer；单文件原子提交不能扩称为跨语言事务。
- 手工编辑和 dry-run 不初始化真实翻译 client；取消、冲突或部分失败不发布半写 XML，不回滚已提交语言，TUI 保留未提交草稿。
- 修改 XML、占位符、批量重试或草稿资格时运行 README 的离线测试；真实 API 只在明确授权的 live 场景使用。
