# 工作区（Workspace）架构与技术原理

> 本文档以 Measix Pilot 当前代码为准，详细记录工作区模块的架构设计、proot 技术原理、
> Android 14+ 兼容性修复经验、工具调用生命周期和交互流程。
> 代码变更时应同步更新本文档。工作区在消息生成链路中的位置参见
> [`chat-generation-pipeline.md`](./chat-generation-pipeline.md)。

## 目录

1. [模块概览](#模块概览)
2. [核心组件与职责](#核心组件与职责)
3. [文件系统布局](#文件系统布局)
4. [Proot 技术原理](#proot-技术原理)
5. [Android 14+ 兼容性修复](#android-14-兼容性修复)
6. [两个入口点：工具调用 vs 交互终端](#两个入口点工具调用-vs-交互终端)
7. [工具调用生命周期](#工具调用生命周期)
8. [Rootfs 安装与修补](#rootfs-安装与修补)
9. [进程管理与超时控制](#进程管理与超时控制)
10. [架构注意事项](#架构注意事项)

---

## 模块概览

工作区模块为 AI 助手提供一个沙箱化的 Linux 环境，使模型能够通过工具调用执行
shell 命令、读写文件、编辑代码。其核心是在 Android 用户态下运行 [PRoot](https://github.com/termux/proot)，
通过 ptrace 拦截系统调用，将一个完整的 Linux rootfs（如 Ubuntu、Alpine）映射到应用的私有目录中，
无需 root 权限即可提供接近原生的 Linux 体验。

### 模块结构

```
workspace/                          # 独立 Gradle 模块（不依赖 Android Framework）
├── Workspace.kt                   # 数据模型：Workspace, WorkspaceShellStatus, WorkspaceConfig 等
├── WorkspaceManager.kt            # 核心管理器：文件操作、命令执行、路径解析
├── WorkspaceShellRunner.kt        # Shell 执行接口 + HostShellRunner + 进程 I/O 工具
├── ProotShellRunner.kt            # proot 驱动的 Shell 执行器（工具调用入口）
├── RootfsPatcher.kt               # rootfs 安装后的配置修补（DNS、hosts、locale、group）
├── RootfsInstaller.kt             # rootfs 下载、解压、安装流程
├── WorkspaceFileSystem.kt         # 文件系统操作（list、read、write、glob、grep）
└── src/main/jniLibs/              # 预编译的 proot 二进制文件
    ├── arm64-v8a/
    │   ├── libproot_exec.so        # proot 主可执行文件
    │   └── libproot_loader.so      # proot 动态加载器
    └── x86_64/
        ├── libproot_exec.so
        └── libproot_loader.so

app/                                # 应用模块
├── data/repository/
│   └── WorkspaceRepository.kt     # Repository 层：DAO 操作 + 协程调度 + 状态管理
├── data/ai/tools/
│   └── WorkspaceTools.kt          # AI 工具定义（read_file、write_file、edit_file、shell）
├── di/
│   └── RepositoryModule.kt        # Koin DI 配置（WorkspaceManager、ProotShellRunner 实例化）
└── ui/pages/extensions/workspace/
    ├── WorkspaceDetailPage.kt     # 工作区管理 UI
    └── WorkspaceTerminalSession.kt  # 交互终端入口（Termux TerminalSession）
```

### Proot 二进制来源

proot 二进制来自 [Termux 的 proot 构建](https://github.com/termux/proot)，是使用 talloc 库
静态链接的 ELF 可执行文件。以 `.so` 后缀打包在 APK 的 `lib/<abi>/` 目录中，
避免 Android 构建系统将它们视为独立的 native 库。通过 ABI splits 为
`arm64-v8a` 和 `x86_64` 分别打包对应架构的 proot 二进制。

---

## 核心组件与职责

| 组件 | 所在模块 | 职责 |
|------|----------|------|
| `WorkspaceManager` | workspace | 核心管理器：文件 CRUD、命令执行委托、rootfs 路径解析、bind mount 映射 |
| `WorkspaceShellRunner` | workspace | Shell 执行接口，定义 `execute(context): WorkspaceCommandResult` |
| `HostShellRunner` | workspace | 直接在宿主机执行 shell（用于测试或无 proot 场景） |
| `ProotShellRunner` | workspace | **工具调用入口**：构建 proot 命令行，通过 ProcessBuilder 执行 |
| `WorkspaceTerminalSession` | app | **交互终端入口**：构建 proot 命令行，通过 Termux TerminalSession 执行 |
| `RootfsInstaller` | workspace | 下载 rootfs 压缩包（.tar.gz/.tar.xz），解压到工作区 linux 目录 |
| `RootfsPatcher` | workspace | 安装后修补 rootfs 配置（DNS、hosts、hostname、locale、group、tmp 目录） |
| `WorkspaceRepository` | app | Repository 层：Room DAO 操作 + 协程调度 + shell 状态机管理 |
| `WorkspaceTools` | app | 定义 AI 可调用的 4 个工具及其 JSON Schema 和执行逻辑 |

---

## 文件系统布局

每个工作区在应用私有目录下有独立的目录结构：

```
/data/data/net.weero.measix.pilot/files/workspaces/<root>/
├── files/          # 用户文件区（bind mount 到 rootfs 内的 /workspace）
├── linux/          # Linux rootfs 根目录（完整的 Linux 发行版）
└── tmp/            # proot 临时文件目录（PROOT_TMP_DIR、TMPDIR）
```

### Bind Mount 映射

通过 proot 的 `-b` 参数将宿主目录映射到 rootfs 内部。DI 配置在
`RepositoryModule.kt` 中定义：

| 宿主路径 | rootfs 内路径 | 用途 |
|----------|---------------|------|
| `workspaces/<root>/files` | `/workspace` | 用户文件区，AI 工具读写的主要区域 |
| `files/skills` | `/skills` | 全局技能目录，跨工作区共享 |
| `files/tool_outputs` | `/tool_outputs` | 工具输出中转目录 |
| `files/upload` | `/upload` | 用户上传文件目录 |
| `/dev` | `/dev` | 设备文件（条件挂载：仅当存在时） |
| `/proc` | `/proc` | 进程信息（条件挂载） |
| `/sys` | `/sys` | 系统信息（条件挂载） |

### 存储区枚举

```kotlin
enum class WorkspaceStorageArea {
    FILES,   // 用户文件区（/workspace 对应的宿主 files/ 目录）
    LINUX,   // rootfs 内部文件（直接访问 linux/ 目录）
}
```

`WorkspaceManager.resolveRootfsPath()` 负责将 rootfs 内的绝对路径解析为宿主文件系统
上的实际文件，优先检查 bind mount 映射（按目标路径长度降序匹配，确保 `/a/b` 在 `/a` 之前），
再回退到 linux 目录。

---

## Proot 技术原理

### 什么是 PRoot

[PRoot](https://github.com/termux/proot) 是一个用户态实现，通过 `ptrace(2)` 系统调用拦截
被跟踪进程的系统调用，在用户态模拟文件系统路径翻译、用户/组 ID 映射和挂载点，从而在不要求
root 权限的前提下提供类似 chroot + mount 的功能。

### 工作机制

```
┌─────────────────────────────────────────┐
│              Android 内核                 │
│         (seccomp + SELinux 策略)          │
├─────────────────────────────────────────┤
│  proot 进程 (libproot_exec.so)           │
│  ├─ ptrace(PTRACE_TRACEME)               │
│  ├─ 注册路径翻译规则 (-r, -w, -b)        │
│  ├─ 安装 seccomp BPF 过滤器               │
│  └─ fork → 被跟踪子进程                  │
│      └─ /bin/bash → 用户命令             │
│          └─ 每次 syscall 被 proot 拦截    │
│              ├─ 路径翻译 (如 /workspace   │
│              │   → 宿主 files/ 目录)      │
│              ├─ UID/GID 映射             │
│              └─ 伪造返回值               │
└─────────────────────────────────────────┘
```

### Seccomp 加速机制

proot 内置一个 seccomp BPF 过滤器（源码：[`seccomp.c`](https://github.com/termux/proot/blob/master/src/syscall/seccomp.c)），
仅对 proot 需要翻译的 syscall（如 `mkdirat`、`newfstatat`、`getcwd`、`chdir` 等）返回
`SECCOMP_RET_TRACE`，触发 `PTRACE_EVENT_SECCOMP` 事件；对其他 syscall 直接返回
`SECCOMP_RET_ALLOW` 放行。这比传统的 `PTRACE_SYSCALL`（拦截所有 syscall）高效得多，
因为大多数 syscall 不需要 proot 翻译。

如果设置了环境变量 `PROOT_NO_SECCOMP=1`，proot 会跳过安装 seccomp 过滤器，回退到
`PTRACE_SYSCALL` 模式。这在某些内核有 [bug](https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161)
的设备上是必要的回退方案，但在 Android 14+ 上会导致部分 syscall（如 `mkdirat`）拦截不可靠。

### 关键 proot 参数

| 参数 | 作用 |
|------|------|
| `--root-id` | 将 guest 进程的 UID/GID 映射为 0（root），无需实际 root 权限 |
| `--link2symlink` | 允许在只读 bind mount 上创建符号链接（通过符号链接重定向写入） |
| `--kill-on-exit` | proot 退出时杀死所有子进程，防止僵尸进程 |
| `-k <version>` | 伪造内核版本号（关键兼容性修复，见下文） |
| `-r <path>` | 指定 rootfs 根目录 |
| `-w <path>` | 设置 guest 进程的初始工作目录（虚拟路径翻译，不调用 chdir） |
| `-b src:dst` | 绑定挂载宿主目录 `src` 到 rootfs 内的 `dst` 路径 |

### 二进制文件

proot 的可执行文件和加载器以 `.so` 后缀打包在 APK 的 `lib/<abi>/` 目录中，
避免 Android 构建系统将它们视为独立的 native 库：

| 文件 | 用途 |
|------|------|
| `libproot_exec.so` | proot 主可执行文件（实际是 ELF 二进制，非共享库） |
| `libproot_loader.so` | proot 的动态加载器（通过 `PROOT_LOADER` 环境变量指定） |

---

## Android 14+ 兼容性修复

这是工作区模块开发中最关键的技术挑战。在 Android 14+ 设备上，proot 沙箱内的 shell
命令会因系统级 seccomp 策略而失败，表现为 `getcwd: Function not implemented`
和 `mkdir: cannot create directory '/workspace': Function not implemented`。

### 问题 1：`getcwd()` 返回 `ENOSYS`

**根因**：glibc 2.33+ 在检测到内核版本 ≥ 5.8 时，会优先使用 `faccessat2`（syscall 号 439，
内核 5.8 新增）等新系统调用。虽然 proot 的 seccomp 过滤器包含 `faccessat2`
（带 `FILTER_SYSEXIT`），但 proot 在处理该 syscall 时存在边界情况——当 proot 将
syscall 替换为 avoider（no-op）后，内核可能覆写返回值为 `-ENOSYS`，而 exit 阶段
的恢复在特定条件下可能不可靠。这直接影响 `getcwd()` 的实现，
因为 glibc 内部使用 `faccessat2` 检查路径权限。

**修复**：使用 `-k 4.14.0` 伪装内核版本。4.14.0 是 Android 8-9 使用的 LTS 内核版本，
足够旧以避免 glibc 选择 `faccessat2` 路径，又足够新以支持所有现代 glibc 功能。
这是防御性修复：即使 proot 的 seccomp 过滤器理论上可以拦截 `faccessat2`，
避免使用该 syscall 是更稳健的方案。

### 问题 2：`cd` 命令失败（`chdir()` 返回 `ENOSYS`）

**根因**：此前设置了 `PROOT_NO_SECCOMP=1`（原意为解决模拟器 SIGILL），禁用了 proot
的 seccomp 过滤器。proot 的 seccomp 过滤器对 `chdir` 返回 `SECCOMP_RET_TRACE`
（带 `FILTER_SYSEXIT`），确保 proot 能在 syscall enter 和 exit 两个阶段拦截并翻译
路径。禁用 seccomp 后回退到 `PTRACE_SYSCALL` 模式，在 Android 14+ 的系统级
seccomp 策略下，`chdir` 可能在 proot 拦截之前就被内核阻断返回 `ENOSYS`。

**修复**：移除 `PROOT_NO_SECCOMP=1` 后，proot 的 seccomp 过滤器恢复正常，
`chdir` 可被正确拦截和翻译。同时，我们移除了 bash 包装脚本中显式的 `cd` 命令——
这并非因为 `chdir` 被阻断（在 seccomp 恢复后它可正常工作），而是因为
proot 的 `-w` 参数已通过虚拟路径翻译设置了初始 CWD，`cd` 是冗余的。
省略 `cd` 简化了命令构造，也避免了在有人错误重新启用 `PROOT_NO_SECCOMP`
时的潜在问题。

### 问题 3：`mkdir`/`stat`/`rename` 返回 `ENOSYS`

**根因**：此前为解决模拟器上的 SIGILL 问题而设置了 `PROOT_NO_SECCOMP=1`，
禁用了 proot 自身的 seccomp BPF 过滤器。这导致 proot 回退到 `PTRACE_SYSCALL` 模式，
在 Android 14+ 上对 `mkdirat`、`newfstatat` 等 syscall 的拦截不可靠——
系统级 seccomp 策略可能在 proot 的 ptrace 拦截器之前就返回 `ENOSYS`，
proot 无法及时翻译路径。

**修复**：移除 `PROOT_NO_SECCOMP=1`。proot 的 seccomp 过滤器对需要翻译的 syscall
返回 `SECCOMP_RET_TRACE`，触发 `PTRACE_EVENT_SECCOMP`，这是 Android 14+ 上可靠拦截
syscall 的必要机制。之前的 SIGILL 实际是架构不匹配（arm64 rootfs 跑在 x86_64 模拟器 CPU 上）
导致的 `SIGILL`（signal 4），与 seccomp 无关。

### 修复策略总结

| 修复 | 代码位置 | 解决的问题 |
|------|----------|------------|
| `-k 4.14.0` 内核版本伪装 | `ProotShellRunner.buildCommand()` | 防御性修复：避免 glibc 使用 `faccessat2`，防止 `getcwd()` ENOSYS |
| `PWD=<path>` 环境变量 | `ProotShellRunner.buildCommand()` env 列表 | 防御性回退：为 bash 提供 CWD，避免 `getcwd()` 失败时的警告 |
| 移除显式 `cd` 命令 | `ProotShellRunner.buildCommand()` bash 脚本 | 简化：`-w` 已设置 CWD，`cd` 冗余；附带避免 `chdir()` 边界情况 |
| 移除 `PROOT_NO_SECCOMP=1` | `ProotShellRunner.buildEnvironment()` | 核心修复：恢复 seccomp 过滤器，确保 `mkdir`/`stat`/`chdir` 等可靠拦截 |

### 修复前后对比

**修复前（失败）：**

```bash
# proot 构建的命令（简化）
PROOT_NO_SECCOMP=1 proot ... -w /workspace -- /bin/bash -l -c "cd /workspace && eval \"$CMD\""
#                ^^^^^^^^^^^^^^                         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
#  禁用 seccomp → mkdir/chdir 拦截失败     cd /workspace → chdir() 被系统 seccomp 阻断
#                                              （因 PROOT_NO_SECCOMP 禁用了 proot 的拦截）
```

**修复后（成功）：**

```bash
# proot 构建的命令（简化）
proot ... -k 4.14.0 -w /workspace -- /usr/bin/env -i PWD=/workspace \
    /bin/bash -c 'eval "$1"' MeasixPilot "$CMD"
#       ^^^^^^^^^^                             ^^^^          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
#  内核伪装    PWD 回退              不调用 cd（冗余），CWD 由 proot -w 虚拟设置
```

### 与上游 RikkaHub 的差异分析

上游 [rikkahub](https://github.com/rikkahub/rikkahub) 的 `ProotShellRunner` 和
`WorkspaceTerminalSession` **不包含**上述任何修复。具体差异：

| 差异项 | 上游 | 本项目 | 合理性 |
|--------|------|--------|--------|
| `-k 4.14.0` | 无 | 有 | ✅ 合理：上游未在 Android 14+ + 现代 glibc rootfs 上充分测试 |
| `PWD` 环境变量 | 无 | 有 | ✅ 合理：防御性回退，无害 |
| `bash -l` 登录 shell | 有 | 移除 | ✅ 合理：已显式设置 PATH，登录脚本非必需且可能调用 `getcwd()` |
| `cd` 命令 | `cd -- "$1" && eval "$2"` | `eval "$1"` | ✅ 合理：`-w` 已设置 CWD，`cd` 冗余 |
| `PROOT_NO_SECCOMP` | 从未设置 | 从未设置（此前误加后移除） | ✅ 一致 |
| `buildEnvironment()` 方法 | 无（内联设置） | 有（可测试） | ✅ 合理：便于单元测试 |
| `buildCommand` 可见性 | `private` | `internal` | ✅ 合理：便于单元测试 |

> **结论**：本项目的修改全部合理。核心修复是移除 `PROOT_NO_SECCOMP=1`（恢复 seccomp
> 过滤器），`-k 4.14.0` 和 `PWD` 是防御性加固。上游代码在未设置 `PROOT_NO_SECCOMP` 的
> 情况下，`mkdir`/`chdir` 可正常工作（seccomp 拦截），但 `getcwd()` 可能仍有问题
> （因无 `-k` 伪装，glibc 可能使用 `faccessat2`）。

---

## 两个入口点：工具调用 vs 交互终端

工作区有两个独立的 proot 入口点，它们必须保持参数同步：

### 1. 工具调用入口：`ProotShellRunner`

- **文件**：`workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt`
- **执行方式**：`ProcessBuilder` + 同步等待（`waitFor`）
- **用途**：AI 工具调用（`workspace_shell`、`workspace_write_file` 等）
- **I/O 模型**：stdin → stdout/stderr 批量收集，超时后强杀进程
- **输出限制**：每流最多 128KB（`MAX_OUTPUT_CHARS`），超出后丢弃但标记 `truncated`

### 2. 交互终端入口：`WorkspaceTerminalSession`

- **文件**：`app/src/main/java/.../WorkspaceTerminalSession.kt`
- **执行方式**：Termux `TerminalSession`（异步 PTY）
- **用途**：用户在 UI 中直接操作终端
- **I/O 模型**：通过 `TerminalView` 实时渲染 ANSI 转义序列
- **生命周期**：跟随 Compose 页面，退出时由 `--kill-on-exit` 清理子进程

### 同步要求

两个入口点必须保持以下参数一致：

| 参数 | ProotShellRunner | WorkspaceTerminalSession |
|------|-------------------|--------------------------|
| proot flags | `--root-id --link2symlink --kill-on-exit` | 同左 |
| 内核伪装 | `-k 4.14.0` | `-k 4.14.0` |
| 环境清理 | `env -i` + 显式设置 | `env -i` + 显式设置 |
| PWD 变量 | `PWD=<proot -w 路径>` | `PWD=<proot -w 路径>` |
| seccomp | **不设置** `PROOT_NO_SECCOMP` | **不设置** `PROOT_NO_SECCOMP` |
| bind mounts | `/workspace` + 自定义 + `/dev, /proc, /sys` | 同左 + `/skills` |

> **警告**：修改任一入口点的 proot 参数时，必须同步修改另一个。两者的参数差异
> 会导致工具调用和交互终端行为不一致。

---

## 工具调用生命周期

### 完整流程

```text
用户在聊天中发送消息
  │
  ▼
ChatService.sendMessage()
  ├─ 装配 Tools（包含 createWorkspaceTools）
  └─ GenerationHandler.generateText()
       │
       ├─ 模型返回 tool_call: workspace_shell
       ├─ 检查 needsApproval（workspace_shell 默认 auto-approve）
       ├─ 执行 tool.execute()
       │    │
       │    ▼
       │  WorkspaceRepository.executeCommand(id, command, cwd)
       │    ├─ runInterruptible(Dispatchers.IO) { ... }
       │    └─ WorkspaceManager.executeCommand(root, command, cwd, ...)
       │         └─ ProotShellRunner.execute(context)
       │              ├─ 检查 rootfs 是否安装（bin/sh 是否存在）
       │              ├─ 检查 proot 二进制（libproot_exec.so / libproot_loader.so）
       │              ├─ RootfsPatcher.patch(linuxDir)  // 修补 DNS/hosts/locale
       │              ├─ buildCommand(context, proot)    // 构建完整 proot 命令行
       │              ├─ ProcessBuilder.start()           // 启动进程
       │              └─ process.readResult(timeout, stdin)  // 收集输出
       │                   ├─ StreamCollector（stdout, daemon thread）
       │                   ├─ StreamCollector（stderr, daemon thread）
       │                   └─ StreamWriter（stdin, daemon thread，可选）
       │
       ├─ 构建 tool_result 消息（stdout + stderr + exit_code）
       └─ 继续下一轮模型调用（最多 256 步）
```

### 四个 AI 工具

| 工具名 | 默认审批 | 功能 |
|--------|----------|------|
| `workspace_read_file` | 需审批 | 读取 rootfs 内的文件（支持文本和图片） |
| `workspace_write_file` | 需审批 | 写入文件到 `/workspace` |
| `workspace_edit_file` | 需审批 | 基于行号替换的精确编辑（带 unified diff） |
| `workspace_shell` | **自动** | 执行任意 shell 命令（最大超时 600 秒） |

### 审批机制

```kotlin
val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,  // 需用户确认
    "workspace_write_file" to false, // 需用户确认
    "workspace_edit_file" to false,  // 需用户确认
    "workspace_shell" to true,       // 自动通过
)
```

用户可在工作区设置页面对每个工具覆盖默认审批行为，配置存储在
`WorkspaceEntity.toolApprovals`（JSON 序列化的 `Map<String, Boolean>`）。

---

## Rootfs 安装与修补

### 安装流程

```text
WorkspaceRepository.installRootfs(id, url)
  ├─ 更新 shellStatus → INSTALLING
  ├─ runInterruptible { RootfsInstaller.install(root, url) }
  │    ├─ 下载压缩包（支持 .tar.gz / .tar.xz）
  │    ├─ 解压到 staging 目录
  │    ├─ 删除旧 linux/ 目录，将 staging 重命名为 linux/
  │    └─ RootfsPatcher.patch(linuxDir)
  └─ 更新 shellStatus → READY（或 BROKEN 如果失败）
```

### RootfsPatcher 修补内容

| 修补项 | 文件 | 说明 |
|--------|------|------|
| DNS 配置 | `/etc/resolv.conf` | 写入公共 DNS（1.1.1.1、8.8.8.8、223.5.5.5），支持用户自定义 |
| 主机名解析 | `/etc/hosts` | 确保 localhost 条目存在（IPv4 + IPv6） |
| 主机名 | `/etc/hostname` | 设置为 "localhost"（或用户指定） |
| 区域设置 | `/etc/default/locale` | 设置 `LANG=C.UTF-8` |
| 用户组 | `/etc/group` | 将 Android 补充组 ID 映射为 `android_gid_<id>` |
| 临时目录 | `/tmp`, `/var/tmp`, `/root` | 确保存在并设置正确权限 |

### Shell 状态机

```text
DISABLED ──installRootfs()──→ INSTALLING ──成功──→ READY
                                  │                   │
                                  └──失败──→ BROKEN   │
                                                        │
                          checkIntegrity() ──rootfs缺失──→ DISABLED
                          checkIntegrity() ──目录缺失──→ BROKEN
```

---

## 进程管理与超时控制

### 执行模型

`ProotShellRunner` 使用 `ProcessBuilder` 启动 proot 进程，通过三个 daemon 线程
异步收集 stdout、stderr 和写入 stdin：

```text
ProcessBuilder
  ├─ stdout → StreamCollector (daemon thread, 128KB cap)
  ├─ stderr → StreamCollector (daemon thread, 128KB cap)
  └─ stdin  → StreamWriter   (daemon thread, 可选)
```

### 超时处理

```kotlin
val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
if (!finished) {
    process.destroyForcibly()  // 超时强杀
}
```

超时后 `exitCode = -1`，`timedOut = true`。

### 协程取消支持

`WorkspaceRepository.executeCommand()` 使用 `runInterruptible(Dispatchers.IO)` 包装
阻塞调用。当协程被取消时（如用户停止生成），线程中断信号会打断 `waitFor`，
触发 `destroyForcibly()` 杀掉 proot 进程及其子进程。

### 输出截断

`StreamCollector` 在读取到 `MAX_OUTPUT_CHARS`（128KB）后继续读取到 EOF 但丢弃数据，
设置 `truncated = true`。这个设计防止管道写满导致子进程阻塞无法退出。

---

## 架构注意事项

### 1. 两个入口点同步

修改 `ProotShellRunner` 的 proot 参数时，必须同步修改 `WorkspaceTerminalSession`
中的对应参数。两者的差异会导致工具调用和交互终端行为不一致。

### 2. proot 二进制架构匹配

proot 二进制（`libproot_exec.so`）和 rootfs 架构必须与设备 CPU 架构匹配。
在 x86_64 模拟器上使用 arm64 rootfs 会导致 `SIGILL`（signal 4）崩溃。
APK 通过 ABI splits 为 `arm64-v8a`、`x86_64` 分别打包对应架构的 proot 二进制。

### 3. 不要设置 PROOT_NO_SECCOMP=1

proot 的 seccomp BPF 过滤器是可靠拦截 `mkdir`/`stat`/`rename` 等 syscall 的必要机制。
禁用它会导致回退到 `PTRACE_SYSCALL` 模式，在 Android 14+ 上拦截不可靠。
仅在确认设备内核存在 seccomp bug（如 [Ubuntu bug #1202161](https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161)）时
才考虑设置 `PROOT_NO_SECCOMP=1` 作为回退。

### 4. 临时文件清理

`WorkspaceManager.cleanupAllTempDirs()` 在应用启动时调用，清理：
- proot 临时文件（`workspaces/<root>/tmp/`）
- rootfs 内的 `/tmp` 和 `/var/tmp` 目录

### 5. Bind Mount 路径解析

`WorkspaceManager.resolveRootfsPath()` 按目标路径长度降序匹配 bind mount，
确保 `/a/b` 在 `/a` 之前匹配。内核文件系统（`/dev`、`/proc`、`/sys`）不可作为
文件读取，必须通过 `workspace_shell` 工具访问。

### 6. HostShellRunner 用于测试

`HostShellRunner` 直接在宿主机执行 shell 命令（`/system/bin/sh` 或 `/bin/sh`），
不经过 proot 沙箱。主要用于：
- 单元测试（`HostShellRunnerTest`）验证进程 I/O 管道
- 不需要 rootfs 的轻量场景
- CI 环境中无法运行 proot 时的回退
