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
| `RootfsPath` | 纯 guest 路径规范化与安全写区分类，供审批及 Manager 共用 |
| `RootfsFileHandle` / `RootfsFileAccess` | Manager 内部的受控目录/文件描述符 IO；不读取配置、不拥有审批或持久化事实 |
| `WorkspaceFileSystem` | `FILES` / `LINUX` 存储区内的安全相对路径操作 |
| `WorkspaceShellRunner` | 阻塞式命令执行接口与进程 I/O 收集 |
| `ProotLaunchSpec` | 两类 PRoot 启动共用的 executable、bind、cwd、env 与 argv |
| `ProotShellRunner` | 把 `ProotLaunchSpec` 交给 `ProcessBuilder` 执行 |
| `RootfsInstaller` | 下载、校验路径、解压和原子替换 Rootfs |
| `RootfsPatcher` | 修补 DNS、hosts、hostname、locale、group 与临时目录 |
| `WorkspaceRepository` | Room 实体、协程调度、安装状态和 Manager 调用；不作为 Workspace UI API |
| `WorkspaceApplicationService` | Workspace typed command 的唯一 owner；UI、模型 Rootfs 操作、安装/删除与终端 mutation 共用互斥协议 |
| `WorkspaceQueryService` | 把 Workspace 列表/详情投影为 `WorkspaceUiModel`，并提供文件列表、文本预览和 `observeTerminal` 读口；所有 UI Workspace 读取都走此 port |
| `WorkspaceTerminalRuntime` | application-scoped PTY、创建 Job、Tab/选中项和 shell-exit 生命周期唯一 owner |
| `WorkspaceTools` | 注册 `workspace_*` schema、审批与结果形状；执行只使用受限 `WorkspaceToolSession` capability |
| `WorkspaceTerminalSession` | 通过 Termux PTY 提供用户交互终端 |

`workspace` Gradle 模块不依赖应用 UI；`app` 模块负责 Room、Compose、文件上传、工具注册和 DI。
Workspace 工具由 `GenerationToolSetFactory` 在 Master/Target 共用的 `TurnEngine` 管道中装配；工具执行事实经 `CommitCheckpoint` / `FinalizeTurn` 写入，不另开落库路径。

`GenerationToolSetFactory` 只从 `WorkspaceQueryService` 取得 typed readiness 与审批投影。真正执行时，`WorkspaceApplicationService.executeTool` 在 per-workspace gate 内重新校验 Workspace 仍存在且为 `READY`，再交付只含 Rootfs read/write/update/command 的 `WorkspaceToolSession`；工具代码不能取得 Repository。这样从装配到执行之间发生删除、重装或状态变化时 fail-closed，且 `workspace_edit_file` 的 read/replace/write 整体不会与 UI 写入、安装或删除交错。

Compose、ViewModel、聊天文件补全、cwd 选择和已编辑文件导出都只能依赖 `WorkspaceQueryService` / `WorkspaceApplicationService`；不得持有 `WorkspaceRepository` 或 Room `WorkspaceEntity`。`WorkspaceUiModel` 只公开 UI 所需的 id、名称、typed `WorkspaceShellStatus` 和工具审批投影，不把持久化实体或 `shell_status` 字符串编码当作页面协议。字符串只存在于 Room 边界，并由 `WorkspaceEntity.resolvedShellStatus` 一次解析；未知值按 `BROKEN` fail-closed，不能意外开放工具或终端。

`WorkspaceDocumentsProvider.queryDocument` 在解析到具体文件后必须先确认 `File.exists()`，已删除路径不能再写入 SAF 游标。

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
| `/upload` | 用户上传目录 | 提示词要求只读；需修改时先复制到 `/workspace` |
| `/dev`、`/proc`、`/sys` | Android 对应目录 | 仅存在时挂载，不允许文件 API 直接读取 |

`WorkspaceManager.resolveRootfsPath()` 负责把规范化的 Rootfs 绝对路径映射回宿主文件。`RootfsPath.parse` 消除 `.` / `..` 与重复分隔符，拒绝越过 guest 根、NUL 和反斜线；审批与执行共用规范化后的路径。bind mount 按目标路径长度降序匹配，避免较短前缀抢先命中；`/workspace` 映射到当前 Workspace 文件区，其他路径落到 `linux/`。内核文件系统只能通过 shell 访问。

`WorkspaceStorageArea.FILES` 和 `LINUX` 用于管理页面的直接文件操作；AI 工具使用 Rootfs 绝对路径，以便与 shell 看到同一命名空间。

## 4. PRoot 执行契约

`workspace/proot-lock.json` 是唯一机器可读 manifest，固定 PRoot/Termux Packages 来源、版本、源码 archive 校验、
构建参数以及各 ABI artifact 的路径、SHA-256 和 ELF 契约。`workspace/PROOT.md` 记录来源与许可证；PRoot 源码、
patch/build scripts、第三方许可证、静态链接依赖的可重链接材料和适用安装信息共同构成独立 Release 合规门禁，
provenance URL 不能代替该义务。manifest 校验通过也不能表述为已经完成本地 bit-for-bit 可复现构建。

`ProotLaunchSpec` 的关键参数：

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
非交互命令（`ProotLaunchSpec.from(command != null)`）额外设置 `CI=1`、`NO_COLOR=1`、`PAGER=cat`，
避免 git/man/apt 等挂起或输出转义序列；交互式 terminal 保持原环境。

### Android 兼容约束

实现通过 `-k 4.14.0` 让现代 glibc 避免选择 PRoot 无法可靠处理的新 syscall 路径，并用与 `-w` 一致的 `PWD` 作为防御性回退。不得默认设置 `PROOT_NO_SECCOMP=1`：PRoot 自身的 seccomp filter 用 `SECCOMP_RET_TRACE` 触发可靠的 syscall 翻译，禁用后在 Android 14+ 上可能出现 `mkdir`、`stat`、`chdir` 或 `getcwd` 的 `ENOSYS`。

x86_64 设备必须使用 x86_64 PRoot 和 Rootfs，arm64 设备必须使用 arm64 产物。架构不匹配导致的 `SIGILL` 不能通过关闭 seccomp 修复。

## 5. AI 工具

注册名和默认交互 requirement 如下。`None` 表示无需用户交互，`Approval` 表示执行前需要授权：

| 工具 | 默认 requirement | 行为 |
|------|-------------------------|------|
| `workspace_read_file` | `None` | 读取 UTF-8 文本或图片；单文件大小受限 |
| `workspace_write_file` | `None` | 写入 UTF-8 文本并返回文件元数据 |
| `workspace_edit_file` | `None` | 按 `old_text` / `new_text` 精确或宽松匹配替换，diff 只进入 UI metadata |
| `workspace_shell` | `Approval` | 在 Rootfs 中执行任意 shell 命令 |

Workspace 实体可用 `toolApprovals` 对各工具覆盖默认值。参数先由 `WorkspaceToolArguments` 的纯解析器校验，缺字段或错误类型直接失败，不进入审批。即使用户关闭写入或编辑审批，只要规范化后的目标路径不在 `/workspace` 或 `/tmp`，仍强制审批；例如 `/tmp/../etc/config` 属于 `/etc`，不能按原字符串前缀放行。
执行时 Manager 再次检查同一路径分类；区外写入必须携带从该调用已有审批决定派生的 `approvedByUser`，模型参数不能授予此能力。授权设置、会话审批和数据库结构保持既有协议。

Target Run 沿用同一工具定义并显式使用 `ToolInteractionAvailability.USER_INPUT_ONLY`：typed `UserInput` 可暂停并桥接宿主，
typed `Approval` 自动拒绝。因此默认配置下，子助手可以直接读写安全根，却不能直接运行 `workspace_shell`；用户撤销
Workspace 或工具权限后，当前 Turn 的 Provider schema 仍使用 START 时冻结的 `FrozenToolDefinition`；
执行时 `WorkspaceApplicationService.executeTool` 按当前配置 live fail-closed。下一 `START` 才从装配结果里撤下该工具。

### 文件工具

- `workspace_read_file` 要求 Rootfs 绝对路径。文本按 UTF-8 返回 `{path,text}`；图片保存为聊天文件后返回 Image part 和路径说明。
- 单次读取上限由 `MAX_READ_FILE_BYTES` 控制；大文件应改用 shell 的 `head`、`tail`、`grep` 等分段读取。
- 文件工具由 Manager 使用 native 目录描述符逐段打开路径（包含 mount anchor 祖先），拒绝符号链接；不先 canonical 检查后再沿原路径打开。需要跟随链接时使用遵循自身审批策略的 `workspace_shell`。
- `workspace_write_file` 支持 `overwrite`，写入先在已打开父目录内完成临时文件，再原子发布，保留已有文件权限；取消或失败不预先截断原文件。写目标为多硬链接文件时拒绝。
- `workspace_edit_file` 依次尝试 exact、line-trimmed 和 block-anchor 策略；除非 `replace_all=true`，匹配必须唯一。
- 编辑读取和写入共用同一描述符作用域，替换前检查原目标是否变化。已打开目录随后被重命名时保持原对象，不追随路径替换；此约束不是对拥有独立 Shell 能力的并发进程提供绝对隔离。
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
WorkspaceApplicationService.executeTool()
  -> WorkspaceToolSession.executeCommand()
  -> WorkspaceRepository.executeCommand()
  -> runInterruptible(Dispatchers.IO)
  -> WorkspaceManager.executeCommand()
  -> ProotShellRunner.execute()
  -> ProotLaunchSpec.from(...)
  -> ProcessBuilder.start()
  -> readResult(timeout, stdin)
```

stdout、stderr 和可选 stdin 使用独立 daemon 线程。
`readResult` 在 `stdin == null` 时立即关闭管道，向子进程声明没有输入；等待 EOF 的 CLI（`cat`、`read`、
`sort` 等）不会挂到超时。有输入时仍由唯一 `StreamWriter` 写入、flush 并 close。超时会 `destroyForcibly()`，返回 `exitCode=-1` 与 `timedOut=true`。协程取消通过 `runInterruptible` 转换为线程中断；`readResult()` 在中断路径强制销毁进程并继续传播取消，确保停止生成不会留下后台 PRoot。

## 7. 交互终端

`WorkspaceTerminalRuntime` 是所有交互终端的 application-scoped owner。它通过 service 层的 `WorkspaceTerminalSession` helper 创建 Termux PTY，并独占 session、创建 Job、tab 顺序、选中项和 shell-exit 清理。UI/VM 只持有 `WorkspaceTerminalTabUiModel`；UI 自己拥有的 `TerminalView` 以 `WorkspaceTerminalViewport` capability 按 tab id bind/unbind，不能取得 runtime-owned `TerminalSession`。页面离开或应用进入后台不关闭 PTY，进程死亡后也不持久化虚假的运行态。
关闭终端 Tab 前需要二次确认：确认态是 `WorkspaceTerminalPage` 的 UI 临时状态，确认后仍经
`WorkspaceApplicationService` → `WorkspaceTerminalRuntime` 串行关闭；tab 在确认期间因 shell exit 或
Workspace 删除而消失时自动清除 pending，不发送无意义命令。

Workspace command 仍由 `WorkspaceApplicationService` 拥有；持久化列表/文件预览和 terminal 聚合投影都由 `WorkspaceQueryService` 提供，其中 terminal 读口是 `observeTerminal(workspaceId)`。Query 不获得写能力，也不反向调用 ApplicationService。`WorkspaceTerminalViewport` 只表达 UI viewport capability，不是 session facade 或第二生命周期 owner。

创建中只发布 `PREPARING`，创建成功后发布 `READY`；Rootfs 未就绪、创建失败、取消或 shell exit 都走同一 remove 路径，失败 Tab 不留在 read model。Rootfs/PTY 异步创建失败由 runtime 在同一 Workspace projection 发布带唯一 id 的 typed `lastFailure`，VM 只把新 failure 映射为用户提示；主动关闭或取消不能伪造失败。单 Workspace 最多六个 Tab。rename/reorder/select/close 与创建保留都在 runtime mutex 内决定；资源 finish 在条目先从 read model 移除后执行。创建准备、模型工具执行和 `WorkspaceApplicationService` 的 UI 文件命令/install/delete 使用同一组固定条带 mutex，既阻止同一 Workspace 的 Rootfs/PTY/工具 TOCTOU，也不会按历史 Workspace id 无限保留锁对象。

`WorkspaceApplicationService.installRootfs` 与 `deleteWorkspace` 必须在同一 Workspace command gate 内先 `closeWorkspace` 并等待全部创建 Job/PTY 收口，再调用 Repository。删除协议先把 durable shell 状态置为 `BROKEN`，再由 `WorkspaceManager` 将文件树移入可恢复暂存位置；Repository 在暂存目录同级写入仅含原 shell 状态与 Assistant→Workspace binding 的删除 journal，随后才清理 Assistant 引用。物理删除开始前，Settings 拒绝或进程中断会由 journal 回填仍为空的原 binding 并恢复暂存树；开始删除后 journal 进入终态删除阶段，递归删除失败也保持 `BROKEN`，因为失败可能已删除部分树，绝不将残余目录伪装为 READY。后续删除继续清理暂存树，或者在目录已删除后由 `WorkspaceDAO.deleteById` 确认删除一条 durable identity；只有这项确认后才能清 journal。任一步骤失败时 `BROKEN` 记录与 journal 都作为重试身份保留；后续删除必须幂等，不能发布成功或留下无 durable 身份的孤儿 Rootfs。Workspace 管理页通过 `WorkspaceApplicationService`/`WorkspaceQueryService` 操作，不直连 Repository。

shell 工具与交互终端都消费同一份 `ProotLaunchSpec`：executable、loader、kernel spoof、`/workspace` bind、应用级 `/skills` `/upload`、内核文件系统以及 `PWD` 都来自该值对象。二者只把 spec 交给各自进程 adapter，不再手写第二套 argv/env/bind。受管 Tool Output 不挂载到 Rootfs；模型只能使用 conversation-scoped `read_tool_output` / `grep_tool_output` 回查。

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

删除 Workspace 时先把 Room 状态持久化为 `BROKEN`，再将磁盘目录移到 Manager-owned 暂存位置并写入删除 journal；Settings 成功清理所有 Assistant 的 `workspaceId` 引用后才标记并删除暂存树，最后由 `WorkspaceDAO.deleteById` 确认删除 Room 实体。Settings 拒绝或删除尚未开始时中断，完整性检查按 journal 恢复目录、原引用和原 shell 状态。标记物理删除后，递归删除失败或中断都不能假定目录完整：journal 与 `BROKEN` 状态保留，后续删除继续清理，只有树已不存在且 DAO 确认删到一行才清 journal。失败时保留 durable identity 供幂等重试。删除或状态变化后，下一次工具装配不会继续暴露旧 Workspace。

## 10. 维护与验证

修改 Workspace 时应覆盖：

- 路径逃逸、bind mount 优先级与内核文件系统限制；
- 真实工具注册名、审批覆盖、安全写根、结果 schema 与文本替换；
- 命令参数、环境、超时、输出排空与中断；
- Rootfs 归档逃逸、链接、取消与幂等修补；
- 真实设备上的匹配架构 Rootfs、Android 14+ shell、交互终端和取消清理。

PRoot 的 hash/ELF 静态契约不等同于设备验收。各支持 ABI 仍需在匹配 Rootfs 的真实 Android 环境验证 cwd、
文件操作、挂载、DNS/netlink、SysV shared memory、超时/取消、长输出和双 PTY；覆盖完成前必须明确标记为设备待验证。

PRoot 兼容参数（`-k` kernel spoof、seccomp 策略、环境变量与 flags）的唯一 owner 是 `ProotLaunchSpec`；
两个入口只把它交给各自的进程 adapter（`ProcessBuilder` 与 Termux PTY）。调整兼容参数只需修改并验证
`ProotLaunchSpec`；调整业务挂载时按 `ProotLaunchSpec.appBindMounts` 的暴露边界评估。
