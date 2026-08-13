# 工作区架构与执行参考

本文档描述当前 Workspace 的持久化、Rootfs、PRoot 进程、AI 工具与交互终端边界。消息生成如何装配这些工具见 [chat-generation-pipeline.md](chat-generation-pipeline.md)，模型可见文案见 [prompts-and-tools.md](prompts-and-tools.md)。

## 1. 目标与边界

Workspace 为 Assistant 提供应用私有的 Linux Rootfs 和用户文件区。PRoot 在无 root 权限的 Android 进程中完成路径翻译和 bind mount；它是用户态隔离层，不等同于内核容器或安全虚拟机。

只有同时满足以下条件时才向模型注册 Workspace 工具：

```text
Assistant.workspaceId 有效
&& WorkspaceEntity 存在
&& shellStatus == READY
```

会话的 `workspaceCwd` 只影响 `workspace_shell` 默认工作目录，不改变 Assistant 与 Workspace 的绑定关系。

## 2. 组件职责

| 组件 | 职责 |
|------|------|
| `WorkspaceManager` | 目录布局、路径解析、文件操作、命令执行上下文与 bind mount 表 |
| `WorkspaceFileSystem` | `FILES` / `LINUX` 存储区内的安全相对路径操作 |
| `WorkspaceShellRunner` | 阻塞式命令执行接口与进程 I/O 收集 |
| `ProotShellRunner` | 构造 PRoot 命令并通过 `ProcessBuilder` 执行 |
| `RootfsInstaller` | 下载、校验路径、解压和原子替换 Rootfs |
| `RootfsPatcher` | 修补 DNS、hosts、hostname、locale、group 与临时目录 |
| `WorkspaceRepository` | Room 实体、协程调度、安装状态和 Manager 调用 |
| `WorkspaceTools` | 注册 `workspace_*` 工具、schema、审批与结果形状 |
| `WorkspaceTerminalSession` | 通过 Termux PTY 提供用户交互终端 |

`workspace` Gradle 模块不依赖应用 UI；`app` 模块负责 Room、Compose、文件上传、工具注册和 DI。

## 3. 文件系统与挂载

每个 Workspace 使用独立 root 名称，名称只允许字母、数字、点、下划线和连字符：

```text
<filesDir>/workspaces/<root>/
├─ files/    用户文件区
├─ linux/    Rootfs
└─ tmp/      PRoot 临时目录与安装 staging
```

Rootfs 内的主要映射：

| Rootfs 路径 | 宿主来源 | 访问语义 |
|-------------|----------|----------|
| `/workspace` | 当前 Workspace 的 `files/` | 模型与用户的主要工作目录 |
| `/skills` | 应用级技能目录 | 跨 Workspace 共享 |
| `/tool_outputs` | 大型工具输出目录 | 供模型用 shell 读取截断后的全文 |
| `/upload` | 用户上传目录 | 提示词要求只读；需修改时先复制到 `/workspace` |
| `/dev`、`/proc`、`/sys` | Android 对应目录 | 仅存在时挂载，不允许文件 API 直接读取 |

`WorkspaceManager.resolveRootfsPath()` 负责把 Rootfs 绝对路径映射回宿主文件。bind mount 按目标路径长度降序匹配，避免较短前缀抢先命中；`/workspace` 映射到当前 Workspace 文件区，其他路径落到 `linux/`。内核文件系统只能通过 shell 访问。

`WorkspaceStorageArea.FILES` 和 `LINUX` 用于管理页面的直接文件操作；AI 工具使用 Rootfs 绝对路径，以便与 shell 看到同一命名空间。

## 4. PRoot 执行契约

`ProotShellRunner` 的关键参数：

```text
--root-id --link2symlink --kill-on-exit
-k 4.14.0
-r <linuxDir>
-w /workspace/<relative-cwd>
-b <filesDir>:/workspace
...configured bind mounts...
/usr/bin/env -i <explicit environment> /bin/bash -c 'eval "$1"' MeasixPilot <command>
```

- `--root-id` 只伪装 guest UID/GID，不赋予 Android root 权限。
- `--kill-on-exit` 防止 PRoot 退出后遗留子进程。
- 命令作为位置参数传入，避免再次拼接和转义。
- cwd 必须先由 `WorkspaceManager` 验证为 `files/` 下存在的目录，再转换为 `/workspace` 路径。
- 环境用 `env -i` 清空后显式设置 `HOME`、`PATH`、`TERM`、locale 与 `PWD`。

### Android 兼容约束

实现通过 `-k 4.14.0` 让现代 glibc 避免选择 PRoot 无法可靠处理的新 syscall 路径，并用与 `-w` 一致的 `PWD` 作为防御性回退。不得默认设置 `PROOT_NO_SECCOMP=1`：PRoot 自身的 seccomp filter 用 `SECCOMP_RET_TRACE` 触发可靠的 syscall 翻译，禁用后在 Android 14+ 上可能出现 `mkdir`、`stat`、`chdir` 或 `getcwd` 的 `ENOSYS`。

x86_64 设备必须使用 x86_64 PRoot 和 Rootfs，arm64 设备必须使用 arm64 产物。架构不匹配导致的 `SIGILL` 不能通过关闭 seccomp 修复。

## 5. AI 工具

注册名和默认审批语义如下。布尔值表示 `needsApproval`，不是“自动允许”：

| 工具 | 默认 `needsApproval` | 行为 |
|------|-------------------------|------|
| `workspace_read_file` | `false` | 读取 UTF-8 文本或图片；单文件大小受限 |
| `workspace_write_file` | `false` | 写入 UTF-8 文本并返回文件元数据 |
| `workspace_edit_file` | `false` | 按 `old_text` / `new_text` 精确或宽松匹配替换，diff 只进入 UI metadata |
| `workspace_shell` | `true` | 在 Rootfs 中执行任意 shell 命令 |

Workspace 实体可用 `toolApprovals` 对各工具覆盖默认值。即使用户关闭写入或编辑审批，只要目标路径不在 `/workspace` 或 `/tmp`，实现仍强制审批；参数无效也按需审批处理，不能利用解析失败绕过路径保护。

Target Run 沿用同一工具定义，但非交互审批策略会拒绝除 `ask_user` 之外的所有需审批工具。因此默认配置下，子助手可以直接读写安全根，却不能直接运行 `workspace_shell`；用户撤销 Workspace 或工具权限后，下一模型 step 会按运行快照与当前配置的交集移除能力。

### 文件工具

- `workspace_read_file` 要求 Rootfs 绝对路径。文本按 UTF-8 返回 `{path,text}`；图片保存为聊天文件后返回 Image part 和路径说明。
- 单次读取上限由 `MAX_READ_FILE_BYTES` 控制；大文件应改用 shell 的 `head`、`tail`、`grep` 等分段读取。
- `workspace_write_file` 支持 `overwrite`，通过受控 shell 命令创建父目录、写 stdin 并读取 `stat` 元数据。
- `workspace_edit_file` 依次尝试 exact、line-trimmed 和 block-anchor 策略；除非 `replace_all=true`，匹配必须唯一。
- unified diff 存在 `DiffMetadata`，用于 `DiffView`，不会混入发送给 Provider 的工具结果文本。

### Shell 工具

`workspace_shell` 的 cwd 相对 `/workspace`；默认超时来自 `WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS`，调用参数可在工具上限内覆盖。结果为：

```json
{
  "exitCode": 0,
  "stdout": "...",
  "stderr": "...",
  "timedOut": false,
  "truncated": true
}
```

`truncated` 仅在发生截断时出现。stdout 与 stderr 分别由后台 collector 持续读到 EOF，超过 `MAX_OUTPUT_CHARS` 后丢弃多余字符但继续排空管道，避免子进程因缓冲区写满而挂起。

## 6. 进程、超时与取消

```text
WorkspaceRepository.executeCommand()
  -> runInterruptible(Dispatchers.IO)
  -> WorkspaceManager.executeCommand()
  -> ProotShellRunner.execute()
  -> ProcessBuilder.start()
  -> readResult(timeout, stdin)
```

stdout、stderr 和可选 stdin 使用独立 daemon 线程。超时会 `destroyForcibly()`，返回 `exitCode=-1` 与 `timedOut=true`。协程取消通过 `runInterruptible` 转换为线程中断；`readResult()` 在中断路径强制销毁进程并继续传播取消，确保停止生成不会留下后台 PRoot。

## 7. 交互终端

`WorkspaceTerminalSession` 使用同一 PRoot 二进制、Rootfs、内核伪装、核心 flags、显式环境和 seccomp 约束，但通过 Termux `TerminalSession` 提供 PTY 与 ANSI 交互。

两个入口必须同步关键兼容参数，但挂载集合有意不同：

- 工具入口使用 `WorkspaceManager` 的完整 bind mount 表，包括 `/skills`、`/tool_outputs` 和 `/upload`；
- 交互终端当前只额外挂载 `/skills`，不自动暴露工具输出和上传目录。

因此维护时应同步 PRoot 运行语义，不应假设两个入口的所有业务挂载完全相同。

## 8. Rootfs 安装与修补

`RootfsInstaller` 支持 gzip 与 xz 压缩的 tar：

```text
ensureWorkspace
  -> 下载到 tmp
  -> 解压到 staging
  -> 校验 tar 路径、symlink 与 hardlink 不逃逸 staging
  -> 删除旧 linux 并 rename staging -> linux
  -> RootfsPatcher.patch
  -> 清理 archive / staging
```

下载和解压循环检查线程中断，页面取消安装后可以尽快停止。tar 解压支持普通文件、目录、symlink、hardlink、GNU long name/link 与 PAX 路径；所有落盘路径都经过 canonical containment 检查。

`RootfsPatcher` 是幂等修补器：

- 保留已存在且有效的非本地 DNS；否则写入设备 DNS 或默认 DNS；
- 补齐 IPv4/IPv6 localhost、hostname 和 `LANG`；
- 为 Android supplementary GID 追加可读 group 名；
- 确保 `/tmp`、`/var/tmp` 与 `/root` 存在并具有所需权限。

应用启动时 `cleanupAllTempDirs()` 清理每个 Workspace 的 PRoot temp、Rootfs `/tmp` 与 `/var/tmp`；后续执行或 patch 会按需重建。

## 9. 状态与删除

Workspace shell 状态使用 `DISABLED`、`INSTALLING`、`READY` 和 `BROKEN`。只有 READY 注册工具和打开终端；安装失败进入 BROKEN，Rootfs 缺失可回到 DISABLED。

删除 Workspace 时先删除 Room 实体和磁盘目录，再清理所有 Assistant 的 `workspaceId` 引用。删除或状态变化后，下一次工具装配不会继续暴露旧 Workspace。

## 10. 维护与验证

修改 Workspace 时应覆盖：

- `WorkspaceManagerTest`：路径逃逸、bind mount 优先级、内核文件系统限制；
- `WorkspaceToolsTest`：真实注册名、审批覆盖、安全写根、结果 schema 与文本替换；
- `ProotShellRunnerTest` / `WorkspaceShellRunnerTest`：命令参数、环境、超时、输出排空与中断；
- `RootfsInstallerTest` / `RootfsPatcherTest`：归档逃逸、链接、取消与幂等修补；
- 真实设备上的匹配架构 Rootfs、Android 14+ shell、交互终端和取消清理。

关键兼容参数同时存在于 `ProotShellRunner` 与 `WorkspaceTerminalSession`。调整 `-k`、seccomp、环境或 PRoot flags 时必须核对两个入口；调整业务挂载时则按各入口的暴露边界分别评估。
