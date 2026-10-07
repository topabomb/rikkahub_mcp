# 工作区架构与执行参考

本文档描述本地 Workspace 的持久化、Rootfs、PRoot 进程、AI 工具与交互终端，以及企业远程文件管理的独立边界。消息生成如何装配工具见 [turn-step-execution.md](turn-step-execution.md)，工具参数与结果契约见 [prompts-and-tools.md](prompts-and-tools.md)。

## 1. 能力与隔离边界

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
| `RootfsFileHandle` / `WorkspaceDirectoryHandle` / `WorkspaceFileAccess` | Manager 内部的受控目录/文件描述符 IO；不读取配置、不拥有审批或持久化事实 |
| `WorkspaceFileSystem` | `FILES` / `LINUX` 存储区内的安全相对路径操作 |
| `WorkspaceShellRunner` | 阻塞式命令执行接口与进程 I/O 收集 |
| `ProotLaunchSpec` | 两类 PRoot 启动共用的 executable、bind、cwd、env 与 argv |
| `ProotShellRunner` | 把 `ProotLaunchSpec` 交给 `ProcessBuilder` 执行 |
| `RootfsInstaller` | 下载、校验路径、解压，并在兼容性验证和 patch 后替换 Rootfs |
| `RootfsCompatibility` | 实际 PRoot ELF 架构、对应默认镜像及 guest executable/解释器兼容校验；不拥有安装状态或进程 |
| `RootfsPatcher` | 修补 DNS、hosts、hostname、locale、group 与临时目录 |
| `WorkspaceRepository` | Room 实体、协程调度、安装状态和 Manager 调用；不作为 Workspace UI API |
| `WorkspaceApplicationService` | Workspace typed command 的唯一 owner；UI、模型 Rootfs 操作、安装/删除与终端 mutation 共用互斥协议，并提供受授权约束的图片、PDF 描述符和媒体读取能力 |
| `WorkspaceQueryService` | 把 Workspace 列表/详情投影为 `WorkspaceUiModel`，并提供文件列表、文本预览和 `observeTerminal` 读口；UI 不直接访问 Repository 或 Manager |
| `WorkspaceTerminalRuntime` | application-scoped PTY、创建 Job、Tab/选中项和 shell-exit 生命周期唯一 owner |
| `WorkspaceTools` | 注册 `workspace_*` schema、审批与结果形状；执行只使用受限 `WorkspaceToolSession` capability |
| `WorkspacePtySession` | 通过 Termux PTY 提供用户交互终端 |

`workspace` Gradle 模块不依赖应用 UI；`app` 模块负责 Room、Compose、文件上传、工具注册和 DI。
Workspace 工具由 `TurnToolSetFactory` 在 Master/Target 共用的 `TurnCommitter` 管道中装配；工具执行事实经 `TurnCheckpoint` / `FinalizeTurn` 写入，不另开落库路径。

`TurnToolSetFactory` 只从 `WorkspaceQueryService` 取得 typed readiness 与审批投影。真正执行时，`WorkspaceApplicationService.executeTool` 在 per-workspace gate 内重新校验 Workspace 仍存在且为 `READY`，再交付只含 Rootfs read/write/update/command 的 `WorkspaceToolSession`；工具代码不能取得 Repository。删除或状态变化分别通过 `WorkspaceToolUnavailableException` 映射为 `workspace_unavailable`、`workspace_not_ready`，企业访问撤销映射为 `tool_not_permitted`，不会冒充未知运行时异常。`workspace_edit_file` 的 read/replace/write 整体不会与 UI 写入、安装或删除交错。

Compose、ViewModel、聊天文件补全、cwd 选择和已编辑文件导出都只能依赖 `WorkspaceQueryService` / `WorkspaceApplicationService`；不得持有 `WorkspaceRepository` 或 Room `WorkspaceEntity`。`WorkspaceUiModel` 只公开 UI 所需的 id、名称、typed `WorkspaceShellStatus` 和工具审批投影，不把持久化实体或 `shell_status` 字符串编码当作页面协议。字符串只存在于 Room 边界，并由 `WorkspaceEntity.resolvedShellStatus` 一次解析；未知值按 `BROKEN` fail-closed，不能意外开放工具或终端。

`WorkspaceDocumentsProvider` 仅负责稳定的 `root` / `ws/{root}/{path}` URI、Cursor、MIME、取消信号与通知；数据读取和命令分别交给现有 QueryService / ApplicationService，不访问 DAO、Manager 或磁盘。SAF 暴露显式共享的已注册 Workspace，不使用当前域作为第二目录状态。所有数据入口等待恢复就绪，查询不创建缺失目录；未知或已删除路径不产生虚构游标项，`isChildDocument` 同样校验注册与存在。

命令按已注册 workspaceId 复用既有 stripe gate，双目录操作按 stripe 去重、排序加锁并复验 root 映射。底层 `WorkspaceDirectoryHandle` 复用现有 JNI 描述符 IO，逐层 NOFOLLOW；可写 FD 先验证 regular file 和单硬链接，再截断。创建使用独占名称，重命名/移动只使用 `RENAME_NOREPLACE`，没有复制删除回退。复制在目标 Workspace 既有 `tmp/` 内完成独占临时树再原子发布到文件区，避免占用用户文件名或暴露未完成副本，失败清理本次未发布副本；删除不跟随树内符号链接，可能部分完成时直接报错供重试，不新增文件树事务表。

文件描述符移交前的取消归原打开调用清理；成功移交后由外部客户端持有和关闭。Workspace gate 保护打开与移交，不覆盖外部客户端之后的每次读写，也不承诺切域或删除能够撤销已打开的 FD。

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
| `/upload` | 原生读取经 ArtifactStore；Shell 为本次授权输入副本 | 原生只读；Shell 可修改副本，原文件不变；PTY 不自动挂载 |
| `/dev`、`/proc`、`/sys` | Android 对应目录 | 仅存在时挂载，不允许文件 API 直接读取 |

`WorkspaceManager.resolveRootfsPath()` 负责把规范化的 Rootfs 绝对路径映射回宿主文件。`RootfsPath.parse` 消除 `.` / `..` 与重复分隔符，拒绝越过 guest 根、NUL 和反斜线；审批与执行共用规范化后的路径。bind mount 按目标路径长度降序匹配，避免较短前缀抢先命中；`/workspace` 映射到当前 Workspace 文件区，其他路径落到 `linux/`。`/upload` 是保留入口：`WorkspaceToolSession` 将读取交给 ArtifactStore，校验原 RealmAccess 的主体、ACTIVE 和发布状态；WorkspaceManager 拒绝直接解析及任何写入/编辑，即使已审批也不能回落到 Linux 同名目录。内核文件系统只能通过 shell 访问。

`WorkspaceStorageArea.FILES` 和 `LINUX` 用于管理页面的直接文件操作；AI 工具使用 Rootfs 绝对路径，以便与 shell 看到同一命名空间。

### 文件导出与编辑草稿

`WorkspaceApplicationService.exportFiles` 接收固定的 workspaceId、area 和相对路径集合，逐项创建 SAF document，记录 provider 返回的 URI 与实际名称；不查找覆盖同名外部文件，不持久化树授权。源 stat 与复制使用原 per-workspace gate，外部 provider 的 create/open/close/delete 在 gate 外，避免目标也是本应用 DocumentsProvider 时回入同一 gate。源在复制开始时重新校验，不承诺选择时的内容快照。

`WorkspaceManager.exportFile` 复用 `WorkspaceDirectoryHandle.open` 的逐层 NOFOLLOW 与 regular-file 校验，关闭输入描述符，借用输出流；因此普通导出、Linux 文本预览、图片读取和消息文件分享都拒绝源路径中的符号链接。Repository 用 `runInterruptible` 调度复制，循环按块检查中断；任意外部 provider 的阻塞 IO 不保证即时结束。

批量结果只有在输出 close 成功后才记为成功。取消停止后续项，保留已完成文档，只清理本项取得且未完成的 document；清理失败作为 suppressed 保留。单文件/分享由打开方关闭输出并展示原诊断。选择与 picker 请求保存在内存；重建后缺少原请求身份的回调被拒绝。运行中的批量任务由 WorkspaceDetailVM 持有，旋转可继续观察结果，离开其生命周期取消。

本地工作区与聊天文件分享共用 `WorkspaceApplicationService.shareFile`，系统交付前副本归本次调用；
交接失败或取消清理未交付副本。成功交付后保留至少 24 小时，在后续分享入口回收过期文件，
不沿用远程分享的启动清理协议。

`WorkspaceDetailVM` 在恢复前台时刷新当前目录，避免外部修改后沿用旧大小和修改时间。文件图片通过 `WorkspaceApplicationService.imageSource` 绑定 workspace/area/entry，直接 stat 复验修订和实际字节上限，不依赖有数量上限的目录列表，不另建预览副本或缓存 owner。

本地 `WorkspaceFileSystem.readText` 和 Linux 区预览通过 `decodeWorkspaceText` 严格解码，拒绝非法编码和 NUL，
保留原 BOM、换行及字面正文，避免把二进制或替换字符当作可保存的源码。
本地编辑正文由以 id/area/path 为键的 `FileEditorState` 持有，保存走 `WorkspaceApplicationService.writeText`，成功后更新原正文基线。未保存退出需确认，保存期间禁止退出；失败保留正文和诊断，取消恢复操作状态。该草稿不进入 Bundle 或 durable store，Activity/进程重建重新读取已发布文件。共享正文视口和输入法规则见 [UI 架构](ui-architecture.md)。

## 4. PRoot 执行契约

`workspace/proot-lock.json` 是唯一机器可读 manifest，固定 PRoot/Termux Packages 来源、版本、源码 archive 校验、
构建参数以及各 ABI artifact 的路径、SHA-256 和 ELF 契约。`workspace/PROOT.md` 记录来源与许可证；PRoot 源码、
patch/build scripts、第三方许可证、静态链接依赖的可重链接材料和适用安装信息共同构成独立 Release 合规门禁，
provenance URL 不能代替该义务。`workspace/tools/build-proot.py` 从固定源码、依赖和本地终止补丁构建双 ABI；
默认校验最终 hash，候选构建只输出仓库外待审核产物。可复现状态以 manifest 的实际独立重建结果为准，不能代替设备验收。

Android Process 的终止请求由 PRoot 自身处理：启动前阻塞 TERM/QUIT，guest 恢复原 signal mask，tracer 在登记首个子进程、
安装 handler 后才解除阻塞。两个信号共用既有 tracee registry 和 event loop，持续终止并回收包括晚到 fork/clone 事件中的子进程。
Shell 保持 ProcessBuilder 和分离管道；WorkspacePtySession 使用现有 PID 发 TERM，不新增 wrapper 或进程监督 owner。

`ProotLaunchSpec` 统一构造 executable、Rootfs、bind、cwd、环境与命令参数；具体 argv 以类型实现为准。

- `--root-id` 只伪装 guest UID/GID，不赋予 Android root 权限。
- `--kill-on-exit` 在初始 guest 退出时终止其余 tracees；主动关闭必须通过 TERM/QUIT 让 PRoot 回收，不能 SIGKILL tracer。
- 命令作为位置参数传入，避免再次拼接和转义。
- cwd 必须先由 `WorkspaceManager` 验证为 `files/` 下存在的目录，再转换为 `/workspace` 路径。
- 环境用 `env -i` 清空后显式设置 `HOME`、`PATH`、`TERM`、locale 与 `PWD`。
非交互命令（`ProotLaunchSpec.from(command != null)`）额外设置 `CI=1`、`NO_COLOR=1`、`PAGER=cat`，
避免 git/man/apt 等挂起或输出转义序列；交互式 terminal 保持原环境。

### Android 兼容约束

实现通过 `-k 4.14.0` 让现代 glibc 避免选择 PRoot 无法可靠处理的新 syscall 路径，并用与 `-w` 一致的 `PWD` 作为防御性回退。不得默认设置 `PROOT_NO_SECCOMP=1`：PRoot 自身的 seccomp filter 用 `SECCOMP_RET_TRACE` 触发可靠的 syscall 翻译，禁用后在 Android 14+ 上可能出现 `mkdir`、`stat`、`chdir` 或 `getcwd` 的 `ENOSYS`。

x86_64 设备必须使用 x86_64 PRoot 和 Rootfs，arm64 设备必须使用 arm64 产物。架构不匹配导致的 `SIGILL` 不能通过关闭 seccomp 修复。

`RootfsCompatibility` 读取实际打包 PRoot 的 ELF machine，默认镜像按该事实选择 Ubuntu arm64 或 amd64。不能用模拟器品牌或 `SUPPORTED_ABIS` 中包含某架构推断：原生桥可令列表同时含 x86_64 与 arm64。Installer 在 staging 校验 env/bash 的 64 位 ELF、动态解释器和相同架构，并完成 patch 后才替换原目录；guest 绝对符号链接只在 guest 根解析。启动完整性检查对既存不兼容根标记 BROKEN，Shell 与 PTY 入口再复验。无 QEMU 跨架构执行或默认禁用 seccomp 的旁路。

guest 启动入口与解释器的 LOAD segment 必须满足 `Os.sysconf(_SC_PAGESIZE)` 返回的实际页大小：alignment 为 2 的幂且不小于该值，虚拟地址与文件偏移对页大小同余。读失败不回退猜测 4 KB。这是保守准入，不是完整动态依赖解析或运行成功保证。Ubuntu 24.04.3 amd64 的 4 KB 对齐不适用于 x86_64 16 KB AVD；使用 4 KB AVD 验证 Linux 执行，并在 16 KB AVD 验证拒绝和原目录保全。对应 arm64 镜像使用 64 KB 对齐，保留原启动路径；真实 arm64 执行仍需设备验证。

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

`workspace_shell` 的 `uploads` 显式列出本次需要的 `/upload/<file>`，最多 `MAX_WORKSPACE_UPLOADS` 个，总量不超过 `MAX_WORKSPACE_UPLOAD_BYTES`。参数解析拒绝无效路径和规范化重复；ArtifactStore 在生命周期锁内复验并复制，WorkspaceApplicationService 持有单次临时目录。空列表也绑定空目录，不暴露历史输入。副本可修改，原 Artifact 不变；需要保留的结果须明确写到共享 `/workspace`。复制及命令取消均由原调用完成取消与清理，目录清理不跟随符号链接。

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

`WorkspaceApplicationService.executeTool` 经原 Workspace command gate 取得执行能力；
Repository 用 `runInterruptible` 调度 `ProotShellRunner`，进程和流归本次命令。

stdout、stderr 和可选 stdin 使用独立 daemon 线程。
`readResult` 在 `stdin == null` 时立即关闭管道，向子进程声明没有输入；等待 EOF 的 CLI（`cat`、`read`、
`sort` 等）不会挂到超时。有输入时仍由唯一 `StreamWriter` 写入、flush 并 close。超时会 `destroyForcibly()` 并等待实际进程退出，返回 `exitCode=-1` 与 `timedOut=true`。协程取消通过 `runInterruptible` 转换为线程中断；`readResult()` 保留原中断，销毁并等待实际退出后才传播。重复中断或销毁失败也不能提前释放输入副本；清理失败附加到原错误。Session 锁只覆盖授权和文件复制，不包住 Shell 等待；既有 Workspace 命令门仍保护安装与删除互斥。该协议约束应用交付的输入及其生命周期，不把 PRoot 声称为恶意进程的内核安全边界。

## 7. 交互终端

终端视口、IME 与系统栏由 [UI 架构](ui-architecture.md)维护，PTY 生命周期不随页面布局变化。

`WorkspaceTerminalRuntime` 是所有交互终端的 application-scoped owner。它通过 service 层的 `WorkspacePtySession` 创建 Termux PTY，并独占原 RealmAccess、session、创建 Job、字节 writer、tab 顺序、选中项和 shell-exit 清理。UI/VM 只持有 `WorkspaceTerminalTabUiModel`；UI 自己拥有的 `TerminalView` 以 `WorkspaceTerminalViewport` capability 按 tab id bind/unbind，不能取得 runtime-owned `TerminalSession`。页面离开或应用进入后台不关闭 PTY，进程死亡后也不持久化虚假的运行态。
关闭终端 Tab 前需要二次确认：确认态是 `WorkspaceTerminalPage` 的 UI 临时状态，确认后仍经
`WorkspaceApplicationService` → `WorkspaceTerminalRuntime` 串行关闭；tab 在确认期间因 shell exit 或
Workspace 删除而消失时自动清除 pending，不发送无意义命令。

零 tab 显示已退出，只有 PREPARING 显示创建中；退出后不自动重试。非主动关闭的非零进程退出由 Runtime 发布既有 typed failure，正常退出与主动 close 的进程终止保持普通空态。

条目按 Workspace root 与原 RealmAccess（含完整企业 Session）投影，所有条目和 viewport 状态限制在 Main。创建中为 `PREPARING`，成功后为 `READY`；关闭先变为 `CLOSING` 并关闭视图访问，实际进程退出且 writer 完成后才移除。关闭失败保留原条目供重试。尚未首次布局的 PID 0 不发送进程信号。单 Workspace 跨域合计最多六个 Tab，配置与数据库不保存运行态。

页面操作捕获原 RealmSelection。绑定、resize、按键、粘贴与 UI 命令等待 Session 准入；锁忙不等于授权失效。切域在发布新选择前永久关闭旧 viewport 的访问、IME、选择句柄和延迟滚动回调，并取消尚未准入的操作。切回同一有效 Session 时用新视图接回原 PTY。切换在关闭旧视图访问后失败或取消时，Session owner 保留原域但推进选择版本，旧视图永不复活。退出通过原 PTY owner 关闭该企业 Session 的终端。

Termux view 与 emulator 源码在既有 Workspace 模块维护，来源和修改见 `workspace/TERMINAL-SOURCE.md`。协议编码、屏幕更新与自动响应仍在 Main；`WorkspacePtySession.write(byte[], offset, count)` 是唯一字节交付边界，原条目复制字节并由串行 IO writer 排空。Main 使用单个字节缓冲区合并逐字符输入，以单个合并唤醒信号驱动 writer，不为每个字符保留队列节点。待交付总字节量包含正在写入的批次，超限明确关闭并提示失败。Session 锁不包住可能阻塞的 ByteQueue 写入；取消先停止 PTY 以释放阻塞写，再等待 writer 完成。已编码的输入和自动回复始终归原 PTY，不改投新域。

Rootfs/PTY 异步失败由 runtime 在同一 Workspace projection 发布带唯一 id 的 typed `lastFailure`，VM 只映射新 failure。创建准备、模型工具执行和 `WorkspaceApplicationService` 的 UI 文件命令/install/delete 使用同一组固定条带 mutex，不按历史 Workspace id 无限保留锁对象。

`WorkspaceApplicationService.installRootfs` 与 `deleteWorkspace` 必须在同一 Workspace command gate 内先 `closeWorkspace` 并等待全部创建 Job/PTY 退出，再调用 Repository。删除与故障恢复协议见下文“状态与删除”。

shell 工具与交互终端都消费同一份 `ProotLaunchSpec`：executable、loader、kernel spoof、`/workspace` bind、应用级 `/skills`、内核文件系统以及 `PWD` 都来自该值对象。二者只把 spec 交给各自进程 adapter，不再手写第二套 argv/env/bind。Shell 另传单次调用的 `/upload` 副本挂载；全局挂载表与 PTY 均不包含应用上传目录。受管 Tool Output 不挂载到 Rootfs；模型只能使用 conversation-scoped `read_tool_output` / `grep_tool_output` 回查。

## 8. Rootfs 安装与修补

`RootfsInstaller` 支持 gzip 与 xz 压缩的 tar：

```text
ensureWorkspace
  -> 下载到 tmp
  -> 解压到 staging
  -> 解压期间校验落盘路径及相对链接不逃逸 staging
  -> RootfsCompatibility 校验 guest 入口、解释器、ABI 与页对齐
  -> RootfsPatcher.patch(staging)
  -> 删除旧 linux 并 rename staging -> linux
  -> 清理 archive / staging
```

下载和解压循环检查线程中断，页面取消安装后可以尽快停止。tar 解压支持普通文件、目录、symlink、hardlink、GNU long name/link 与 PAX 路径；所有落盘路径都经过 canonical containment 检查。

`WorkspaceManager.hasRootfsFiles` 只判断 Linux 目录是否非空，不推断它可执行。Shell 的缺失诊断采用相同的目录存在性语义，是否可运行统一由 `RootfsCompatibility` 校验；不得再用宿主 `File(linuxDir, "bin/sh").isFile` 判断 guest 入口，合法的 `/bin -> /usr/bin` 绝对链接只在 guest 根下解析。

`RootfsPatcher` 是幂等修补器：

- 保留已存在且有效的非本地 DNS；否则写入设备 DNS 或默认 DNS；
- 补齐 IPv4/IPv6 localhost、hostname 和 `LANG`；
- 为 Android supplementary GID 追加可读 group 名；
- 确保 `/tmp`、`/var/tmp` 与 `/root` 存在并具有所需权限。

应用启动时 `cleanupAllTempDirs()` 清理每个 Workspace 的 PRoot temp、Rootfs `/tmp` 与 `/var/tmp`；后续执行或 patch 会按需重建。

## 9. 安装状态与删除

Workspace shell 状态使用 `DISABLED`、`INSTALLING`、`READY` 和 `BROKEN`。只有 READY 注册工具和打开终端；安装失败进入 BROKEN，READY 的 Rootfs 缺失可回到 DISABLED。启动时残留 INSTALLING 一律转为 BROKEN 并保留原文件，允许重新安装；旧 root 仍有效不能证明被中断的安装已成功发布，也不能使界面永久停留在安装中。

删除 Workspace 时先把 Room 状态持久化为 `BROKEN`，再将磁盘目录移到 Manager-owned 暂存位置并写入删除 journal；Settings 成功清理所有 Assistant 的 `workspaceId` 引用后才标记并删除暂存树，最后由 `WorkspaceDAO.deleteById` 确认删除 Room 实体。Settings 拒绝或删除尚未开始时中断，完整性检查按 journal 恢复目录、原引用和原 shell 状态。标记物理删除后，递归删除失败或中断都不能假定目录完整：journal 与 `BROKEN` 状态保留，后续删除继续清理，只有树已不存在且 DAO 确认删到一行才清 journal。失败时保留 durable identity 供幂等重试。删除或状态变化后，下一次工具装配不会继续暴露旧 Workspace。

## 10. 企业远程文件

远程文件不属于本地 Workspace、Assistant.workspaceId 或 PRoot。`RemoteWorkspaceService` 拥有客户端请求、
临时管理会话和未交付副本；远端文件事实仍由 Core/Agent Space 管理。`PlatformWorkspaceClient` 通过原
`PlatformConnection.control` 和企业 Bearer 访问 workspace 状态、目录、内容及条件写入，不直连 DAV，
不依赖 MCP 发布、Applied Snapshot 或模型执行准入。线类型由 `generate-enterprise-wire.py` 消费合同生成。

早期 Core 兼容只在 workspace 状态读取边界处理：精确识别缺路由响应和已发布的旧投影，隐藏可选入口，
不影响原企业功能。缺少 `serviceState` 的旧协议尚未校验文件请求的固定空间身份，不能补默认值后启用。
后续主动刷新会重新探测以发现服务升级；业务 404、认证错误、代理错误页和其他合同错误仍保留真实诊断。

两个入口消费同一摘要。状态刷新按 RealmSelection、Session、连接上下文合并并防止旧结果发布；实际读取失败
保留独立读取错误，只有状态复验及显式目录读取成功才恢复文件入口。状态刷新不下载文件或启动常驻轮询。
`RemoteWorkspaceService.queryState` 区分首次未知、刷新和查询失败，资源摘要只在成功投影证明 agentSpaceId
与有效生命周期时出现。首次检查、协议未提供和明确未开通隐藏卡片；serviceState=ENABLED 不证明用户已开通。
首次查询失败的诊断和重试放在连接区域。已开通资源刷新时保留卡片但关闭文件快捷入口，失败保留待确认状态；
成功确认无资源或已删除则移除摘要。查询失败不改写成未开通，状态刷新成功不清除尚未复验的 readError。
文件读取失败可使在途状态投影失效；刷新完成仍按原请求实例结束刷新标记，不能覆盖后续请求的状态。
管理 handle 固定选择版本、Session、连接、agentSpaceId 与 bindingRevision，只存在内存。
切域在 Session 锁内撤销并取消、锁外等待实际请求及清理；退出等待远程请求后才排空平台租约。
变更地址期间禁止登记新操作。普通状态刷新不会撤销正在进行且身份仍有效的读取，但阻止新写入抢先使用旧结果。
新请求先登记原会话任务；遇到同身份刷新时在锁外等待，完成后重新校验原 handle 再准入。
因此 SAF 返回与前台状态刷新可以并发，等待期间撤权仍会取消任务并清理本次创建的文档。

普通文件下载 GET 只接纳完整 200；列表响应有界，文件内容按 64 KiB 块传输，不建立内存大文件缓存。
重定向和认证器重发关闭。GET 沿 `readClient` 的 OkHttp 只读恢复策略，在响应头接收前恢复可恢复连接失败，
也可能对 408 或 `Retry-After: 0` 的 503 再次请求；正文读取中断不重放下载。
文件接口的 `401 invalid_credential` 只说明本次 access token 被拒绝。`PlatformEnterpriseService` 在原 Session
及连接下复用刷新锁与 token 比较替换，只有 refresh credential 明确失效、远端 Session 到期或身份撤销才进入企业退出。
目录、文本和内存图片读取在恢复 token 后最多重试一次，每次重建本次缓冲；持有 SAF/预览/分享输出资源的下载及所有写入
不重新发送，保留原错误，恢复失败作为 suppressed cause 保留。第二次 access token 拒绝仍是本次请求失败，不能据此关闭整个空间。
写入已收到确定拒绝后，取消后续凭据恢复不把该结果改为 UNKNOWN；未得到确定结果的发送后取消仍保留待核实目标。
写请求关闭连接自动重试且 body 为 one-shot。新文件用 If-None-Match，已有普通文件
必须有强 ETag，覆盖目标也必须使用用户确认的目标版本。目录递归删除必须显式确认。
Core 条件失败为 412 `file_version_conflict`、锁定为 423 `file_locked`、普通冲突为 409；旧部署的同 code 409 仍保留真实诊断。
这些拒绝不清除草稿、不重放写入；媒体 416 同样终止当前版本读取，不退化成整文件下载。
UNKNOWN 与异常成功状态、发送后 IO 中断均不能当作失败后可重试；同身份、连接和空间的目标在当前进程内
保留待核实记录，读取目标并由用户确认后才解除。关闭页面不会清除此记录，进程重启不自动恢复或重放任务。

`RemoteWorkspaceVM` 串行执行批量命令并保留逐项结果。SAF 文档在完整输出关闭后才交付；失败只删除本项新建文档，
清理错误附加到原诊断。取消同时关闭已取得的本地流和网络 Call，并等待工作退出。系统 Provider 自身阻塞在打开/关闭
调用时仍取决于该 Provider 返回，不能用 UI 提前结束替代资源退出。
分享使用 FileProvider 只读 URI，系统接收成功前副本仍归 service，失败或撤权清理；已交付 share 副本保留 24 小时，
后续启动清理过期文件。预览关闭删除其副本，启动清理上次进程遗留的未交付预览。

文本只支持严格 UTF-8、2 MiB 内及统一换行；一个 BOM 作为元信息，第二个 U+FEFF 保留正文。
保存沿读取正文那次 GET 的 ETag，失败保留草稿，另存使用新文件条件。
`RemoteWorkspaceVM` 持有单个原 handle/path 的内存编辑会话。`remoteWorkspaceEntry` 使用页面 UUID 作为
Nav3 的 `clazzContentKey`，窗口或 Activity 重建复用原 VM、FileEditorState、读取版本及待处理 SAF 请求，
恢复后的刷新仍使用 VM 捕获的原 selection。关闭或撤权清除；替换 Session 不复活旧授权，UNKNOWN 写入不重放。
进程死亡没有原 VM，导航 key 不恢复临时 selection，文件页要求显式返回重开；不从 Bundle/磁盘恢复正文或授权。分享副本使用短随机私有名和安全短扩展名，
FileProvider 保留原展示名；Intent 使用 URI 实际 MIME，避免真实副本类型与接收应用看到的类型不一致。
图片内容校验像素上限，PDF 逐页原生渲染，资源均有上限；HTML 只作源码，SVG 支持源码及静态渲染，外部打开仍是显式动作。
`validateWorkspaceImage` 独立于模型附件准入：位图限制 24 MiB / 1600 万像素，SVG 限制 2 MiB、节点及深度，
拒绝脚本、DTD/实体、外部资源和递归引用；不支持的 SVG 保留源码入口。
`RestrictedMarkdown` 禁止原始 HTML、HTML/SVG/Mermaid 渲染预览及外部图片，
相对图片仍经原 handle 授权读取；不会向富文本渲染器暴露 Bearer URL。
`WorkspaceFileRules.relativeImage` 只在 Markdown URL 边界严格解码一次百分编码，支持编码的空格和中文，
保持字面加号；非法转义、非法 UTF-8、编码路径别名及越根引用仍拒绝。HTTP 调用始终传逻辑路径，由 URL builder 编码。

### 文件管理与预览

列表筛选和选择由页面管理；批次进度由实际循环的 `activeIndex/batchSize` 和当前目标发布，只在已知长度时显示传输比例，字节传完仍等待远端结果。完成后提供汇总及逐项诊断，UNKNOWN 保留读取核验入口；新批次或清除已知结果不能删除未核实路径。

`WorkspaceFileRules.child` 在提交前校验名称。非法名称和另存同源的预期拒绝保留原对话框、输入和可展开诊断，不发远程写入；其他异常保留完整诊断。目录目标不能是源位置、源目录自身或其内部。批次结束或取消先清空旧目录再开放动作，同时保留结果、待核实记录和编辑草稿；仅未取消且原句柄仍有效时刷新。新目录发布后只保留仍存在的选择，空选择不执行批量操作。

列表摘要显示可见项目数，以及目录响应提供的可选 `usedBytes/availableBytes`。它们是 DAV 对当前目录报告的容量，缺失时省略；不从文件大小求和或推算企业总配额。Android 不调用 Core Admin 的资源采样接口。

预览与编辑使用共享文件正文组件和文本类型识别，布局、代码高亮与动作可达性见 [UI 架构](ui-architecture.md)。
远程仍沿 `WorkspaceText` 的 BOM/换行与 ETag 协议。
读取失败保留原诊断，不能冒充格式不支持；刷新复验原状态与句柄。PDF 逐页渲染并释放旧 bitmap，失败回收本次副本，缩放不提高渲染分辨率或资源上限。展示与动作见 [文件页面](ui-architecture.md#6-文件页面与编辑)。

远程客户端当前只管理文件；没有远程终端、离线镜像、持久后台传输或直接把远程文件加入聊天附件的接口。

### 大文件音视频

本地与远程 `mediaSource` 均限制单文件不超过 4 GiB，长度、位置和边界使用 Long。
本地通过 `WorkspaceDirectoryHandle` 的 NOFOLLOW/regular-file 校验取得单个描述符，整个播放期持有同一文件，
使用 `pread` 按位置读取；每次读前后复验原工作区与文件属性。页面取消关闭描述符，不复制整份视频到缓存。

远程先以 HEAD 取得实际长度与强 ETag，后续每段 GET 使用同一 ETag 的 If-Match 与有界 Range。
客户端严格验证 206、Content-Range、响应 ETag、精确字节数和 identity 编码；不接受服务器以 200 全文件响应代替范围，
也不在 seek 后接纳新版本。401 只按原会话的现有协议恢复 token 后重试一次，范围与版本不变。
服务端不具备这些条件时显示诊断，用户仍可显式下载或外部打开，不偷偷缓存完整大文件。

`MediaPreviewDataSource` 首段最多 256 KiB，其余读窗口最多 8 MiB；播放器目标缓冲 24 MiB，
这是缓冲目标而非进程总内存上限。每次读取缓存也复验访问权；暂停保留有界缓冲以支持拖动后的画面更新；离页和后台停止播放器读取，
撤权停止并清空当前媒体。关闭与重开使用独立读取会话，已取消的迟到结果不能进入新会话。
本地属性与远程 ETag 复验不替代文件系统快照；解码格式仍取决于设备支持的容器与编解码器。

## 11. 维护与验证

修改 Workspace 时应覆盖：

- 路径逃逸、bind mount 优先级与内核文件系统限制；
- 真实工具注册名、审批覆盖、安全写根、结果 schema 与文本替换；
- 命令参数、环境、超时、输出排空与中断；
- Rootfs 归档逃逸、链接、取消与幂等修补；
- 真实设备上的匹配架构 Rootfs、Android 14+ shell、交互终端和取消清理。

PRoot 的 hash/ELF 静态契约不等同于设备验收。各支持 ABI 仍需在匹配 Rootfs 的真实 Android 环境验证 cwd、
文件操作、挂载、DNS/netlink、SysV shared memory、超时/取消、长输出和双 PTY；覆盖完成前必须明确标记为设备待验证。
