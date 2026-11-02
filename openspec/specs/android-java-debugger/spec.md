# Android Java Debugger

## Purpose

Provides VSCode-based debugging of AOSP framework and system app Java layer code via JDWP attach to running Android processes. Designed for the common AOSP developer workflow of attaching to a userdebug/eng device with full root access, in either a fully local environment or a Remote-SSH setup where source lives on a remote Linux server while the device and adb live on the local machine.

## Requirements

### Requirement: Process Discovery

The extension SHALL provide a command that enumerates all debuggable Android processes on the connected device by reading JDWP capability. The command SHALL display the result as a list from which the user can pick a target.

#### Scenario: Local device available
- **WHEN** user invokes "Debugger with Android" with a device connected via adb
- **THEN** the extension lists at least one debuggable process and shows them in a selection UI

#### Scenario: No device connected
- **WHEN** user invokes "Debugger with Android" with no device connected
- **THEN** the extension shows a notification that no device is available, with guidance to connect a device

### Requirement: Process Discovery Spawn-Failure Cleanup

The extension's JDWP process enumeration SHALL handle failure of the `adb jdwp` capture subprocess without resource leakage or dangling state. When the capture subprocess fails to spawn, enumeration SHALL fail with a clear error after releasing the capture timer and terminating any spawned process. An enumeration attempt SHALL settle exactly once.

#### Scenario: Capture subprocess fails to spawn

- **WHEN** the `adb jdwp` capture subprocess emits an `error` event (e.g., adb binary missing or spawn rejected)
- **THEN** the capture timer is cleared and any spawned process is terminated
- **AND** the enumeration promise rejects with a clear error and never resolves partial results afterwards

#### Scenario: Normal capture window completes

- **WHEN** the capture subprocess exits within the capture window
- **THEN** the PID list parsed from stdout is returned exactly once, with the timer cleared on exit

### Requirement: JDWP Attach

After process selection, the extension SHALL establish a JDWP connection to the chosen process's JDWP socket and report successful attachment.

#### Scenario: Successful attach
- **WHEN** user selects a process
- **THEN** the extension sets up the JDWP socket forwarding and the debug session is initialized with the process suspended appropriately for breakpoint setup

#### Scenario: Attach failure
- **WHEN** the chosen process's JDWP socket cannot be reached (e.g., process died, permission denied)
- **THEN** the extension shows a clear error message and does not leave a stale forward

### Requirement: Line Breakpoints

The extension SHALL allow setting line breakpoints in any `.java` or `.kt` file in the workspace. When a breakpoint is hit in the target process, the extension SHALL suspend execution at that location and present the source file at the corresponding line.

#### Scenario: Breakpoint hit at known source location
- **WHEN** user sets a breakpoint in `Foo.java` at line 42 and the target process executes that line
- **THEN** execution suspends and VSCode opens `Foo.java` highlighting line 42

#### Scenario: Breakpoint in unindexed file
- **WHEN** user sets a breakpoint in a file whose classes are not yet in the source index
- **THEN** the extension adds that file's classes to the source index automatically and attempts to set the breakpoint

#### Scenario: Source/runtime version mismatch
- **WHEN** the workspace source line numbers do not match the running bytecode (e.g., different build)
- **THEN** the breakpoint may hit at a different line than expected; the extension SHALL show a warning in the status bar when detected

#### Scenario: Breakpoint inside an anonymous inner class
- **WHEN** user sets a breakpoint on a line inside the body of an anonymous inner class (e.g. inside `new Runnable() { public void run() { ... } }`)
- **THEN** the adapter resolves the runtime ReferenceType (`OuterClass$1`, `$2`, ...) via `vm.allClasses()` since the enclosing class's `allMethods()` does NOT include the anonymous class's methods
- **AND** places the JDI BreakpointRequest against the matching ReferenceType
- **AND** the breakpoint fires when execution reaches that line in the anonymous class

#### Scenario: Breakpoint in a not-yet-loaded class
- **WHEN** user sets a breakpoint on a line whose class is not loaded yet by the JVM
- **THEN** the adapter queues the breakpoint in `pendingJdiBps` with `verified: false`
- **AND** the adapter subscribes to JDI `ClassPrepareEvent` (enabled once at attach time)
- **AND** when the class (or any of its anonymous inner siblings) is loaded, the adapter retries placement automatically
- **AND** upon successful placement the adapter emits a `breakpoint` event with `verified: true`

#### Scenario: Breakpoint in a synthetic-lambda class
- **WHEN** user sets a breakpoint on a line inside a Kotlin lambda or Java lambda body
- **THEN** the adapter resolves the synthetic class (`OuterClass$$Lambda$1` or similar) the same way as anonymous inner classes
- **AND** if the synthetic class is not yet loaded, it is placed on `ClassPrepareEvent`
- **AND** when the runtime hits the line, the `stopped` event's `source.path` and `line` resolve back to the user's source file so VSCode can highlight the line correctly

### Requirement: 日志断点

扩展 SHALL 支持日志断点（输出消息但不挂起的断点）。日志消息可包含形如 `{expr}` 的占位符，其中 `expr` 是共享表达式语言（由 expression-evaluation 能力定义）中的任意表达式——包括局部变量、`this`、字段与数组访问、带参数的方法调用、静态成员、对象构造、运算符等。占位符表达式应在断点命中的栈帧中求值。

#### Scenario: 字段访问形式的日志断点
- **WHEN** 用户设置消息为 `"user={this.user.name}"` 的日志断点
- **AND** 断点命中
- **THEN** 扩展将格式化后的消息输出到调试控制台，并继续执行，不挂起

#### Scenario: 带参数方法调用的日志断点
- **WHEN** 用户设置消息为 `"sum={computeSum(a, b)}"` 的日志断点
- **AND** 断点在 `computeSum`、`a`、`b` 都在作用域内的栈帧中命中
- **THEN** 扩展将含该调用求值结果的消息输出到调试控制台，并继续执行，不挂起

#### Scenario: 表达式求值失败
- **WHEN** 日志断点中的占位符引用了不存在的字段
- **THEN** 扩展向调试控制台输出告警指明失败的表达式，将占位符替换为错误标记，并继续执行

### Requirement: 条件断点

扩展 SHALL 支持条件断点，其条件为共享表达式语言（由 expression-evaluation 能力定义）中的任意表达式——包括局部变量、`this`、字段与数组访问、带参数的方法调用、静态成员、对象构造、运算符等。条件应在断点命中的栈帧中由 adapter 求值；若条件求值为 false，则继续执行不挂起。当条件求值失败（解析错误或运行期错误）时，断点应挂起以便用户注意到该失败条件。

#### Scenario: 条件为真时挂起
- **WHEN** 用户设置条件断点 `this.count == 5`
- **AND** 断点在 `this.count == 5` 时命中
- **THEN** 执行挂起

#### Scenario: 条件为假时跳过
- **WHEN** 用户设置条件断点 `this.count == 5`
- **AND** 断点在 `this.count == 3` 时命中
- **THEN** 继续执行，不挂起

#### Scenario: 条件使用带参方法调用
- **WHEN** 用户设置条件断点 `items.size() > 3`
- **AND** 断点在 `items` 含 5 个元素的栈帧中命中
- **THEN** 执行挂起

#### Scenario: 条件求值失败时挂起
- **WHEN** 条件断点的表达式求值失败（如引用了不存在的字段）
- **AND** 断点命中
- **THEN** 执行挂起（让用户看到失败的条件，而非静默通过）

### Requirement: Step Operations

During a paused debug session, the extension SHALL support step over, step into, and step out operations.

#### Scenario: Step over a method call
- **WHEN** user pauses at a line that contains a method call
- **AND** user invokes step over
- **THEN** execution advances to the next line in the same frame, without descending into the called method

#### Scenario: Step into a method call
- **WHEN** user pauses at a line that contains a method call
- **AND** user invokes step into
- **THEN** execution advances to the first line of the called method

### Requirement: Stack Trace and Locals

The extension SHALL allow viewing the stack trace and local variables of a suspended thread. Each stack frame SHALL include source file path and line number when source is available in the workspace.

#### Scenario: View stack trace with source
- **WHEN** execution is suspended at a breakpoint
- **AND** user opens the stack panel
- **THEN** the stack frames are displayed, each annotated with source file path and line number that the user can click to navigate

#### Scenario: View local variables
- **WHEN** user opens the variables panel for a frame
- **THEN** local variables are displayed with their current values, with complex types expandable

### Requirement: Source Mapping

The extension SHALL maintain an in-memory source index that maps class fully-qualified names to workspace file paths (and vice versa) and caches line-to-bytecode-offset tables. The index SHALL be rebuilt at session start, drawing from explicitly-marked source dirs and any files containing active breakpoints.

#### Scenario: Mark a folder as a debug source dir
- **WHEN** user invokes "Mark as Debug Source Folder" on a workspace folder
- **THEN** the folder's absolute path is appended to the workspace's `sourceDirs` array, persisted via `ExtensionContext.workspaceState` (VSCode Memento, per-workspace SQLite)
- **AND** the extension's source index includes all `.java`/`.kt` files under that dir on the next session
- **AND** the storage is isolated per workspace: opening a different workspace shows a separate, independent list

#### Scenario: Resolve runtime class to source file
- **WHEN** the debug adapter receives a breakpoint hit at runtime class `com.android.server.am.AMS`
- **THEN** the adapter looks up the source index to find the workspace file containing that class
- **AND** if found, returns the source path and resolved line to VSCode

#### Scenario: Runtime class not in source dirs
- **WHEN** the runtime class is not present in any marked source dir
- **THEN** the extension suggests adding the relevant folder as a source dir via a notification action

#### Scenario: Runtime FQN is an anonymous inner class (dollar fallback)
- **WHEN** the runtime class FQN has a `$` suffix that is not in the source index (e.g. `com.example.Outer$1`, `com.example.Outer$Inner$1`)
- **THEN** the adapter strips at the first `$` and looks up the enclosing class (e.g. `com.example.Outer`) in the source index
- **AND** if the enclosing class is found, returns its source file as the resolved source path
- **AND** so VSCode highlights the correct line in the original source file

#### Scenario: First-run migration from legacy settings storage
- **WHEN** the extension activates in a workspace that has `vscode-android-debugger.sourceDirs` in `.vscode/settings.json` (single-folder) or in `.code-workspace` (multi-folder) but has an empty Memento
- **THEN** on the first call to `getAllDirs()`, the manager reads the legacy config, resolves each relative path to an absolute path, deduplicates, and writes the result to Memento
- **AND** the legacy settings entry is left untouched (no destructive cleanup) so the user can verify the migration before manually removing it
- **AND** subsequent reads come from Memento only

### Requirement: Multi-folder Workspace

The extension SHALL support multi-root workspaces (`.code-workspace` files), reading source dir configuration from the workspace's `workspaceState` Memento. Because Memento is keyed by workspace ID, the same physical folder can appear in multiple workspaces with independent source-dir lists.

#### Scenario: Multiple folders under one workspace
- **WHEN** user has two workspace folders `frameworks/base` and `packages/apps/Settings` open in a single `.code-workspace`
- **AND** the user marks `frameworks/base/services` and `packages/apps/Settings/src` as source dirs
- **THEN** the source index aggregates classes from both marked dirs
- **AND** the workspace's Memento contains the union as an array of absolute paths

#### Scenario: Same folder appears in two workspaces
- **WHEN** folder `/path/to/shared` is opened as a standalone workspace (single-folder) and also appears in `~/workspaces/all.code-workspace` alongside other folders
- **AND** the user marks `/path/to/shared/src` in the single-folder window
- **THEN** only the single-folder window's Memento contains `/path/to/shared/src`
- **AND** opening the `.code-workspace` shows an empty source-dir list (independent storage)
- **AND** marking a path in the `.code-workspace` window stores it in that workspace's Memento, not the single-folder one

### Requirement: OS-Assigned Random JDWP Port

The extension SHALL use OS-assigned ephemeral ports (not a fixed range) for `adb forward` and the local JDWP listener.

#### Scenario: Random port allocation
- **WHEN** user starts a debug session
- **THEN** the local extension host calls `net.createServer().listen(0)` to obtain a free port `LP` from the OS
- **AND** closes the probe listener immediately, keeping only `LP` for `adb forward tcp:LP jdwp:<pid>`
- **AND** passes `LP` to the Java Adapter via the DAP `attach` request so it can connect to `127.0.0.1:LP`

#### Scenario: Port collision (extremely rare)
- **WHEN** `adb forward` fails because `LP` is already in use (e.g., another process grabbed it between `listen(0)` and the `adb forward` call)
- **THEN** the local extension host retries up to 3 times with a fresh `listen(0)` allocation
- **AND** if all 3 attempts fail, shows: "Could not allocate JDWP port — please retry"
- **AND** does not start the debug session

### Requirement: TS 侧 SourceIndexer（共享逻辑）

类名解析与 `addDir` / `addFile` 逻辑 SHALL 在 TypeScript 端实现，位于 `extension/src/core/indexer/`。唯一的 `LocalIndexer` 实现同时服务于本地窗口与 Remote-SSH 窗口——因为扩展总是运行在 UI 宿主端，所有文件系统访问应通过 `vscode.workspace.fs` / `vscode.workspace.findFiles`，它们会透明地代理到远端文件系统（不应使用 Node `fs`，它只能看到本地磁盘）。Kotlin/Java adapter **不**应自行遍历文件系统或解析源文件。除类声明外，索引器还应提取每个文件的 import 声明（单类型与 on-demand，含 Kotlin `as` 重命名），供 adapter 解析表达式短名使用。

#### Scenario: TS 索引器解析 Java 文件

- **WHEN** 索引器通过 `vscode.workspace.fs.readFile(uri)` 读取 `.java` 文件
- **THEN** 它提取 `package` 声明以及所有顶层 class/interface/enum/record 声明
- **AND** 对每个声明构造完全限定名，并把 `fqn → canonicalPath(uri)` 加入索引映射
- **AND** 提取该文件的 import 声明到条目的 import 列表

#### Scenario: TS 索引器解析 Kotlin 文件

- **WHEN** 索引器读取 `.kt` 文件
- **THEN** 它提取 `package` 声明以及所有顶层 `class`/`object`/`interface`/`sealed class`/`data class` 声明
- **AND** 对每个声明构造完全限定名并加入映射
- **AND** 提取该文件的 import 声明（含 `import x.y.Z as A` 重命名）到条目的 import 列表

#### Scenario: 源枚举覆盖远端工作区

- **WHEN** 已标记的源目录属于某个工作区文件夹（本地或远端）
- **THEN** 索引器通过 `vscode.workspace.findFiles(new RelativePattern(dirUri, '**/*.{java,kt}'))` 枚举源文件——glob 在文件系统提供方宿主上执行，因此在 Remote-SSH 窗口中批量列举在远端运行
- **WHEN** 已标记的源目录不在任何工作区文件夹下
- **THEN** 索引器通过 `vscode.workspace.fs.stat` + `vscode.workspace.fs.readDirectory` 递归遍历（跳过 `build`/`.git`/点目录），从 UI 宿主仍可触达远端 FS
- **AND** 两条路径都不使用 Node `fs`

#### Scenario: Kotlin adapter 接收预构建索引

- **WHEN** TypeScript 扩展完成索引（单条 `LocalIndexer` 路径，本地或远端窗口）
- **AND** 一个调试会话开始
- **THEN** 会话初始化钩子（在 `extension.ts` 中接线）读取 `IndexManager.getAllEntries()` 并通过 `custom/syncSourceIndex` 自定义请求发送这些条目
- **AND** 每个条目携带其文件的 import 提取结果，供 adapter 解析表达式短名使用
- **AND** Kotlin adapter 的 `SourceIndex` 由该请求填充
- **AND** 不发送任何文件内容（只发送路径与 import 声明）；不使用临时文件；不使用 `--index-file` CLI 参数

#### Scenario: Kotlin adapter 使用索引解析断点

- **WHEN** VSCode 在远端窗口中发送 `source.path` 为完整 URI 的 `setBreakpoints`（如 `vscode-remote://ssh-remote%2Bhost/home/liux/.../AMS.java`）
- **THEN** adapter 通过 `normalizeSourcePath` 归一化（剥离 `vscode-remote://<authority>` / `file://<authority>` 前缀，得到裸 posix 路径）后再做任何 registry 或索引查找
- **AND** 归一化后的键与 `custom/syncSourceIndex` 条目的 `file` 字段匹配
- **AND** adapter 通过 `fileToFqns[key]` 解析 FQN，对每个 FQN 调用 JDI `VirtualMachine.classesByName(fqn)` 获取 `ReferenceType`，通过 `Method.locationsOfLine()` 将行号解析为 JDI 字节码偏移
- **AND** 创建 JDI `BreakpointRequest` 并向 VSCode 发送 `verified: true/false` 的 DAP 响应

#### Scenario: 跨环境统一的 canonical path 模型

- **WHEN** 任何文件键跨越边界（索引条目、断点 payload、adapter registry 键）
- **THEN** 使用 `extension/src/util/uri.ts` 定义的 canonical 形态：`file:` URI 使用 `uri.fsPath`（保留本地历史行为，如 `C:\x\Y.java`），其它 scheme 使用 `uri.path`（如 `/home/liux/Y.java`）
- **AND** Memento 中已标记的源目录以 URI 字符串（`uri.toString()`）存储，绝不使用 `uri.fsPath`（在 Windows UI 宿主上会破坏远端 URI）

### Requirement: No SSH Tunnel Required

The extension SHALL NOT spawn any SSH child process, SHALL NOT modify `~/.ssh/config`, SHALL NOT require an SSH server running on the local machine, and SHALL NOT require any user-side tunnel configuration.

#### Scenario: Extension activates in Remote-SSH without any tunnel
- **WHEN** user opens a `ssh-remote` workspace and the extension activates
- **THEN** no SSH child process is spawned by the extension
- **AND** no port forwarding is configured by the extension
- **AND** no SSH server on the local machine is required (works even if local is Windows with no SSH server)
- **AND** no private-key parsing is performed by the extension

#### Scenario: User has no manual SSH background setup
- **WHEN** user has never run `ssh myserver` manually and has no `~/.ssh/config`
- **THEN** the extension still works in scenario C1, because all cross-host file access uses VSCode's built-in tunnel (which the user already established when opening the remote workspace)
- **AND** no additional SSH setup is required by the extension

### Requirement: Local-Only JDK Requirement

The extension SHALL require a JDK only on the local machine (where the extension host runs). No JDK is required on the remote machine.

#### Scenario: Local JDK present
- **WHEN** user has JDK 8 or higher on the local machine
- **THEN** the extension activates normally and the Java Adapter can be launched locally

#### Scenario: Local JDK missing
- **WHEN** user does not have JDK 8 or higher on the local machine
- **THEN** the extension activation reports a clear error: "JDK 8+ is required on the local machine. Install from <link> and restart VSCode."
- **AND** all debug commands are disabled until the requirement is satisfied
- **AND** the extension does NOT attempt to detect or use a JDK on the remote machine

### Requirement: Setup Command Removed

The previous `Setup Cross-host Tunnel` command SHALL be removed entirely. There is no SSH tunnel to set up; Remote-SSH workspace access is handled transparently by VSCode.

#### Scenario: Command no longer registered
- **WHEN** the extension is installed
- **THEN** `vscode-android-debugger.setupTunnel` is NOT registered
- **AND** no replacement command exists (the extension is zero-configuration for Remote-SSH)

### Requirement: 增量源码索引更新

扩展 SHALL 在调试会话进行期间保持 Java Adapter 的 `SourceIndex` 与用户工作区状态同步，无需用户重新附加。中间过程的更新应使用 `custom/syncSourceIndex` DAP 自定义请求（全量替换；已删除早先的 `sourceDirs/add` 增量合并请求——客户端从未使用它，全量替换让 adapter 逻辑保持简单）。

#### Scenario: sourceDirs 配置在会话进行中变化（全量替换）

- **WHEN** 用户在一个或多个调试会话进行时新增或移除已标记的源目录
- **THEN** `SourceDirManager` 发出 change 事件
- **AND** `extension.ts` 桥接到 `IndexManager.rebuildFromDirs(getAllDirs(), maxFiles)`，重建内存中的索引
- **AND** 发出 `onIndexReady(allEntries)`，由 `extension.ts` 桥接到 `SessionManager.broadcastIndex(allEntries)`
- **AND** `broadcastIndex` 遍历每个存活的 `SessionContextImpl` 并调用 `ctx.dap.syncSourceIndex(entries)`，该调用通过 `custom/syncSourceIndex` 自定义请求发送新条目
- **AND** 每个会话中后续的 `setBreakpoints` 调用按新索引解析

#### Scenario: 在未索引文件中设置断点（通过 add 自动纳入）

- **WHEN** 用户在不属于任何已标记源目录的 `.java` 或 `.kt` 文件中设置断点，且调试会话处于活跃状态
- **THEN** `BreakpointManager` 发出 `onNewFileNeedsIndex([uris])`（只对不在已标记目录下的文件，判定经注入的 `isPathMarked` 回调接收 `vscode.Uri`）
- **AND** `extension.ts` 桥接到 `IndexManager.autoIncludeFiles(uris)`，在内存中解析并合并条目（通过 `vscode.workspace.fs.readFile` 读取，因此远端文件也能工作）
- **AND** `IndexManager` 随后发出 `onIndexReady(allEntries)` → `extension.ts` → `SessionManager.broadcastIndex` → 每个活跃会话调用 `ctx.dap.syncSourceIndex`
- **AND** 每个会话的 adapter 收到 `custom/syncSourceIndex` 带合并后的条目（全量替换，非部分合并——保持 adapter 逻辑简单）
- **AND** 断点在下一次 `setBreakpoints` 往返中正确解析

#### Scenario: 非 Java/Kotlin 断点的自动纳入在线路上保持静默

- **WHEN** 在非 `.java`/`.kt` 文件（如 `.txt`、`.gradle`、`.xml`）中添加断点
- **THEN** `BreakpointManager` 不发出 `onNewFileNeedsIndex`
- **AND** 不发送 `custom/syncSourceIndex`
- **AND** 每个 adapter 的索引不变

#### Scenario: 会话中途结束

- **WHEN** 某个会话的 adapter 进程退出（正常 `disconnect`、崩溃或用户取消）
- **THEN** 对应的 `SessionContextImpl` 被释放
- **AND** `SessionManager.sessions.delete(sessionId)` 执行
- **AND** 之后任何 `sourceDirs` 配置变化或断点变化仍会发出广播，但已释放的会话不在 map 中（不向其发送 DAP 请求）
- **AND** 新的 attach 创建全新的 `SessionContextImpl`

#### Scenario: Adapter 不再接受文件遍历请求

- **WHEN** Java Adapter 收到任何与源码索引相关的 DAP 请求（attach 后的初始 `custom/syncSourceIndex` 推送，或会话进行中的 `custom/syncSourceIndex`）
- **THEN** 它只接受条目列表形态：`Array<{fqn: string, file: string, sourceUri?: string, imports?: string[]}>`——不接受目录路径、文件内容、遍历指令（`imports` 携带的是为表达式名字解析提取的 import 声明，而非文件内容）
- **AND** adapter 不为源码索引访问任何文件系统（没有 `Files.walk`、没有解析正则）
- **AND** 所有 FS 遍历与解析都发生在 TS 侧

#### Scenario: DAP 回显使用客户端可解析的路径形态

- **WHEN** adapter 把源码路径回显给 VSCode（断点事件、`stopped` 事件、`stackTrace`、`loadedSources`）
- **THEN** 使用 display 形态，**不**使用裸 posix 内部键：若条目有 `sourceUri`（完整的 `vscode-remote://...` URI）则用 `sourceUri`，否则用 canonical 键（原生本地路径）
- **AND** 由扩展下推的断点 payload 遵循同一规则（`clientPath`：`file:` URI 用 fsPath，其它用完整 URI）
- **WHEN** Windows UI 宿主运行 Remote-SSH 窗口且断点命中
- **THEN** `stopped`/`stackTrace` 中的源路径是 VSCode 通过远端文件系统 provider 解析的 `vscode-remote://` URI——裸 posix 路径会渲染为 `\home\...` 而打不开文件、断点也无法点亮

### Requirement: JDK Detection

The extension SHALL detect a host JDK installation on the **local** machine at activation. If JDK 8 or higher is not available, the extension SHALL display a clear error message indicating the missing requirement, with installation guidance.

#### Scenario: JDK available
- **WHEN** user has JDK 8 or higher installed on the local machine
- **THEN** the extension activates normally and the Java adapter can be launched locally

#### Scenario: JDK missing
- **WHEN** user does not have JDK 8 or higher installed on the local machine
- **THEN** the extension activation reports a clear error: "JDK 8+ is required on the local machine. Install from <link> and restart VSCode."
- **AND** all debug commands are disabled until the requirement is satisfied
- **AND** the extension does NOT attempt to detect or use a JDK on the remote machine

### Requirement: Debug Console Output

Log breakpoint messages and other diagnostic information SHALL be emitted to the VSCode Debug Console with appropriate source-location prefixes.

#### Scenario: Log message output format
- **WHEN** a log breakpoint is hit with message `"user={this.user.name}"`
- **THEN** the Debug Console receives an output event with content like `[<sourceFile>:<line>] user=Alice`

### Requirement: Atomic Session Teardown

Every debug session SHALL be fully torn down on termination: the session's Java Adapter JVM SHALL be killed AND the session's `adb forward` for the session's JDWP port SHALL be removed. Both teardown steps happen in `onDidTerminateDebugSession`. Each session SHALL own its own JVM process and its own per-session `AdbDevice` reference — no global state is shared between sessions. Per-session teardown MUST be self-contained: terminating one session MUST NOT touch any resource owned by another.

#### Scenario: Normal session end

- **WHEN** user clicks VSCode's Stop button or the session ends for any reason
- **THEN** `onDidTerminateDebugSession` looks up the `SessionContextImpl` for that session id in `SessionManager.sessions`
- **AND** calls `ctx.dispose()` which: (a) reads the session's own `jdwpPort` (not any factory-tracked port), (b) calls `removeForward(jdwpPort)` on the session's own `AdbDevice`, (c) `kill()`s the session's own tracked `ChildProcess`, (d) removes the session from `SessionManager.sessions`
- **AND** the next attach starts from a clean slate: brand-new DAP port, brand-new JDWP port, brand-new JVM process, brand-new adb forward
- **AND** any other live sessions continue running undisturbed

#### Scenario: Two concurrent sessions, one terminates

- **WHEN** user has two active debug sessions (session-A and session-B) on different devices
- **AND** user stops session-A
- **THEN** only session-A's JVM is killed and only session-A's adb forward is removed
- **AND** session-B's JVM, adb forward, and SourceIndex remain intact
- **AND** session-B does not need to re-handshake or be re-attached

#### Scenario: Extension deactivated mid-session

- **WHEN** VSCode is reloading the extension or shutting down while one or more sessions are active
- **THEN** `deactivate()` calls `sessionManager.disposeAll()` which iterates every entry in `sessions` and calls `ctx.dispose()`
- **AND** for each session: JVM is killed, adb forward is removed
- **AND** any adb forward left over (force-quit where `deactivate` may not run) persists on the device until the user runs `adb kill-server`

#### Scenario: Session creation fails after adb forward succeeds

- **WHEN** user has invoked attach, picked a device + process, and `adb forward tcp:LP jdwp:<pid>` succeeded
- **AND** the JVM spawn step then fails (bad javaPath, port collision, etc.)
- **THEN** the partial-session cleanup path runs: the adb forward at `LP` is removed and the temp DAP port is released
- **AND** no zombie adb forward remains on the device

#### Scenario: VSCode-native Restart works via factory rebuild

- **WHEN** user clicks VSCode's debug toolbar Restart button while an Android debug session is active
- **THEN** VSCode terminates the current session — `onDidTerminateDebugSession` fires, looks up `SessionContextImpl` in `SessionManager.sessions`, and calls `ctx.dispose()` which kills the JVM and removes the adb forward
- **AND** VSCode then starts a new session using a clone of the original configuration (including `__device` / `__pid`)
- **AND** the factory lambda (registered by `SessionManager`) is invoked for the new session — it constructs a fresh `SessionContextImpl` via `SessionContextImpl.create(...)`, which allocates a NEW DAP port, a NEW JDWP port, a fresh adb forward, and spawns a fresh JVM, then awaits `READY`
- **AND** the factory returns `DebugAdapterServer(newDapPort)` to VSCode
- **AND** when the new session starts, the session initializer (wired in `extension.ts`) pushes the current `IndexManager` snapshot to the new session via the `custom/syncSourceIndex` custom request
- **AND** the new session reaches the device's JDWP socket via the freshly installed forward and debugging continues normally
- **AND** the previous session's `SessionContextImpl` is gone from `SessionManager.sessions` — no shared state carries over between restarts

#### Scenario: VSCode is force-quit mid-session

- **WHEN** the user closes VSCode without properly terminating the debug session (process kill, OS shutdown)
- **THEN** the `onDidTerminateDebugSession` hook never fires
- **AND** the extension's `deactivate()` calls `sessionManager.disposeAll()` which iterates every entry in `sessions` and calls `ctx.dispose()` (kills each tracked JVM and removes each tracked adb forward)
- **AND** any adb forward left over on the device (force-quit where `deactivate` may also not run) persists until the user runs `adb kill-server`; documented as Open Question 7

### Requirement: Custom Restart Command

The extension SHALL register a command `vscode-android-debugger.restartSession` (label: "Android: Restart Debug Session") that stops the active Android debug session and starts a new one against the same target PID with a brand-new forward, JVM, and DAP port. The command SHALL be visible in the command palette only when the active debug session is of type `android-java` (gated by `when: "debugType == 'android-java'"` in `package.json`).

#### Scenario: Restart command with active Android session

- **WHEN** user invokes "Android: Restart Debug Session" while an Android debug session is active
- **THEN** the command looks up the active session's `SessionContextImpl` in `SessionManager.sessions`
- **AND** reads the device + process from `ctx.device` and `ctx.process` (previously read from session configuration `__pid`)
- **AND** calls `vscode.debug.stopDebugging(session)` — the atomic-teardown hook fires and tears down the JVM + forward
- **AND** then calls `sessionManager.attachToProcess(ctx.device, ctx.process)` which starts a fresh attach flow (build session config, call `startDebugging`, factory constructs a new `SessionContextImpl`)
- **AND** the new session's ports, JVM, adb forward, and SourceIndex are all newly allocated

#### Scenario: Restart command with no active session

- **WHEN** user invokes "Android: Restart Debug Session" with no active debug session, or with an active session of a different type
- **THEN** the command shows a warning: "No active Android debug session to restart"
- **AND** does not throw or attempt any teardown

#### Scenario: Restart command loses `__pid` from an old session

- **WHEN** the active session was started by an older extension version that did not register a `SessionContextImpl` in `SessionManager.sessions` (e.g., no `__pid` stamped into the configuration)
- **THEN** the command shows a warning: "Session is missing target context; was started by an older extension version"
- **AND** does not invoke `sessionManager.attachToProcess`

### Requirement: Configurable Binary Paths

The extension SHALL expose two VSCode configuration keys for pointing at the adb and JDK binaries: `vscode-android-debugger.adbPath` (default `"auto"`) and `vscode-android-debugger.javaPath` (default `"auto"`). When the value is non-`"auto"` and the file exists, that path SHALL be used. When the file is missing, the extension SHALL throw a clear error rather than silently fall back. When the value is `"auto"`, the extension SHALL run a deterministic discovery ladder.

#### Scenario: User-configured adbPath
- **WHEN** user sets `vscode-android-debugger.adbPath` to `/opt/sdk/platform-tools/adb`
- **AND** the file exists
- **THEN** `findAdb` returns `{ adbPath: '/opt/sdk/platform-tools/adb', source: 'config' }`
- **AND** logs `adb path (configured): /opt/sdk/platform-tools/adb`

#### Scenario: User-configured adbPath points at a missing file
- **WHEN** user sets `vscode-android-debugger.adbPath` to a non-existent path
- **THEN** `findAdb` throws "Configured adb path '<path>' does not exist; update `vscode-android-debugger.adbPath` or set to 'auto' for auto-detection"
- **AND** does NOT silently fall back to PATH (wrong config is a config bug, surface it)

#### Scenario: Auto-detect adb (running process wins)
- **WHEN** `adb` value is `"auto"` and an `adb` daemon is already running on the user's machine
- **THEN** `findAdbAuto` calls `findRunningAdb()` first
- **AND** on Linux: walks `/proc/<pid>/cmdline` looking for `argv0 basename === 'adb'`, then reads `/proc/<pid>/exe` symlink and resolves it with `realpathSync` to follow symlink chains
- **AND** on Windows: spawns `powershell -NoProfile -NonInteractive -Command "Get-Process adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Path"` via `execFileSync` with `windowsHide: true`, parses output, returns first existing path
- **AND** on macOS: returns `null` (no `/proc`, no clean cross-process binary path reader) and falls through to the common-path / PATH steps
- **AND** if a running adb is found, that resolved absolute path is used and logged

#### Scenario: Auto-detect adb (common install paths)
- **WHEN** no running adb is found
- **THEN** `findAdbAuto` probes a small list of platform-specific common install locations: Linux `/opt/android-sdk/platform-tools/adb`, `~/Android/Sdk/platform-tools/adb`, etc.; macOS `~/Library/Android/sdk/platform-tools/adb`, `/opt/homebrew/bin/adb`, `/usr/local/bin/adb`; Windows `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, `%ProgramFiles%\Android\platform-tools\adb.exe`
- **AND** uses the first path whose `fs.statSync().isFile()` returns true

#### Scenario: Auto-detect adb (PATH fallback)
- **WHEN** no running adb and no common-path hit
- **THEN** `findAdbAuto` parses `$PATH` (split on `:` on Unix, `;` on Windows) and checks every directory for `adb` / `adb.exe` via `fs.statSync().isFile()`
- **AND** returns the first match as an absolute path
- **AND** the extension NEVER spawns a bare `"adb"` token — the resolved path is logged for traceability

#### Scenario: Auto-detect adb finds nothing
- **WHEN** no running adb, no common-path hit, no PATH hit
- **THEN** `findAdbAuto` throws "adb binary not found. Set `vscode-android-debugger.adbPath` to the absolute path of adb, or add it to $PATH"
- **AND** the attach command surfaces this error before any device interaction

#### Scenario: User-configured javaPath
- **WHEN** user sets `vscode-android-debugger.javaPath` to `/opt/jdk/bin/java`
- **AND** the file exists
- **THEN** `detectJdk` runs `java -version` against that path
- **AND** returns `{ javaPath, version }` when version >= 8
- **AND** logs `JDK detected: <path> (version <v>)`

#### Scenario: User-configured javaPath missing
- **WHEN** user sets `vscode-android-debugger.javaPath` to a non-existent path
- **THEN** `resolveJavaCandidates` throws "Configured java path '<path>' does not exist; update `vscode-android-debugger.javaPath` or set to 'auto'"

#### Scenario: Auto-detect JDK (JAVA_HOME)
- **WHEN** `javaPath` is `"auto"` and `$JAVA_HOME` is set
- **THEN** `resolveJavaCandidates` checks `$JAVA_HOME/bin/java` (or `java.exe` on Windows)
- **AND** adds it to the candidate list if `fs.existsSync` succeeds
- **AND** `detectJdk` then runs `java -version` and accepts the first candidate reporting version >= 8

#### Scenario: Auto-detect JDK (PATH)
- **WHEN** `JAVA_HOME` is unset or `$JAVA_HOME/bin/java` does not exist
- **THEN** `findJavaInPath` walks `$PATH`, checking each directory for `java` / `java.exe` via `fs.statSync().isFile()`
- **AND** returns the first hit

#### Scenario: JDK discovery deliberately skips platform common paths
- **WHEN** the user has a JDK installed but neither `JAVA_HOME` nor `PATH` is set
- **THEN** the extension SHALL NOT enumerate `/Library/Java/JavaVirtualMachines`, `C:\Program Files\Eclipse Adoptium\jdk-*`, distro package paths, etc.
- **AND** instead returns null and the caller surfaces a clear error: "JDK 8+ is required on the local machine. Set `vscode-android-debugger.javaPath` or set $JAVA_HOME / add java to $PATH"
- **BECAUSE** JDK install paths change with every update (e.g. `jdk-21.0.9.10-hotspot`), branch across too many distributions to enumerate usefully, and almost always come with `JAVA_HOME` set by the installer — sticking to `JAVA_HOME` + `PATH` is deterministic and easy to reason about

### Requirement: Attach Pre-Flight Checks

The attach command SHALL validate adb, JDK, and adapter jar BEFORE any device interaction. A bad JDK or missing adb path SHALL fail the command immediately, not after the user has selected a process.

#### Scenario: JDK missing at attach time
- **WHEN** user invokes "Debugger with Android" with no JDK on `$PATH` or `$JAVA_HOME` (and no `javaPath` config)
- **THEN** `prepareAttachContext` calls `detectJdk("auto")` which returns null
- **THEN** the command throws the JDK error BEFORE `adb devices` runs and BEFORE the QuickPick UI appears
- **AND** the user is shown the JDK error directly, no wasted device interaction

#### Scenario: adb path unresolvable
- **WHEN** user invokes "Debugger with Android" with no adb binary found
- **THEN** `prepareAttachContext` calls `findAdb("auto")` which throws the adb-not-found error
- **AND** the command fails BEFORE `adb devices` runs

#### Scenario: All pre-flight checks pass
- **WHEN** adb, JDK, and adapter jar are all valid
- **THEN** `prepareAttachContext` returns the resolved `{jdk, jarPath, adbResult, runner}` context
- **AND** the attach flow proceeds with device enumeration, process picker, then `attachToProcess` (forward → index → JVM → `startDebugging`)

### Requirement: Adapter Accepts Multiple Sessions

The adapter JVM SHALL loop on `ServerSocket.accept()` and serve a fresh DapServer on every accepted client, rather than exiting after the first disconnect. Each accepted client SHALL run on its own daemon thread with its own request-handling executor.

#### Scenario: VSCode disconnects and reconnects to the same JVM
- **WHEN** VSCode's native Restart sends `disconnect`, the DapServer shuts down and releases the client socket
- **AND** the accept loop continues to block on `serverSocket.accept()`
- **AND** when VSCode's new DAP client connects, the JVM accepts it and starts a new DapServer on a new thread
- **THEN** the reconnecting client is served by the same JVM, which never restarts the adapter
- **AND** the JVM does NOT exit — the only way out of the accept loop is `Thread.interrupt` (sent by `proc.kill('SIGTERM')` from `factory.killActive`)

#### Scenario: One DapServer's disconnect does not affect another's session
- **WHEN** two clients are connected to the same JVM (shouldn't happen in normal flow but defensive)
- **AND** one client disconnects
- **THEN** only that client's DapServer runs `server.shutdown()` and `sessionExecutor.shutdown()`
- **AND** the other client's DapServer is unaffected
- **BECAUSE** each client owns its OWN `ExecutorService` (`Executors.newCachedThreadPool { ... "dap-handler" ... }`) — sharing one executor across sessions would let `handleDisconnect`'s `executor.shutdown()` kill all sessions

#### Scenario: JVM receives SIGTERM
- **WHEN** the extension's `killActive()` calls `proc.kill()` on the tracked JVM
- **THEN** the SIGTERM interrupts the accept loop's blocking `accept()` call
- **AND** the catch block logs `serverSocket.accept 关闭, 退出 accept 循环: ...` and breaks
- **AND** `serverSocket.close()` runs
- **AND** the JVM process exits

### Requirement: adb Output Log Truncation

The adb runner SHALL truncate the stdout and stderr it logs on `proc.on('exit')` to prevent commands like `adb shell ps -A -o PID,NAME` (which return hundreds of process rows) from drowning the Output channel.

#### Scenario: Verbose adb command exits
- **WHEN** an adb command produces stdout longer than `STDOUT_LOG_MAX` (200 chars) — e.g. `adb shell ps -A`
- **THEN** the runner logs `exit: code=0 signal=null stdout="<first 200 chars>... [+<N> chars]" stderr="<truncated>"`
- **AND** the full stdout is still returned to the caller (truncation is for LOGGING only, not for command output)
- **AND** the user can see the truncation in the Output channel and infer how much was suppressed

#### Scenario: Normal-length output
- **WHEN** adb stdout is ≤ 200 chars (e.g. `adb devices` with one device)
- **THEN** the runner logs the full stdout verbatim — no truncation marker is shown

### Requirement: Single-Path TS SourceIndexer (Remote-SSH)

Indexing SHALL use `LocalIndexer` in every window (local and Remote-SSH): no workspace-side indexer implementation exists and no code path SHALL depend on the `exports` of a same-ID workspace-host extension instance (unreachable by VSCode design). The factory SHALL always resolve to `LocalIndexer` until a dedicated workspace-side helper extension exists. In a remote window the extension SHALL surface the degraded indexing mode on the status bar while indexing still works via the remote FS proxy.

#### Scenario: Single indexer path replaces the remote/fallback split

- **WHEN** indexing runs in any window (local or Remote-SSH)
- **THEN** `LocalIndexer` performs enumeration via `vscode.workspace.findFiles` (in-workspace dirs) and `vscode.workspace.fs` recursion (out-of-workspace dirs)
- **AND** no code path depends on a workspace-side `DebugRemoteApi` implementation (`remote/` stays empty) or on same-ID cross-host `exports`
- **AND** in a remote window the status bar shows the degraded mode and indexing still succeeds

#### Scenario: IndexerFactory seam is kept for the dual-extension future

- **WHEN** `createIndexer` runs (today, in any window)
- **THEN** it returns `LocalIndexer` — the probe for a remote indexer provider finds none because the workspace-side implementation is intentionally absent
- **AND** `IndexerFactory` and `RemoteIndexer` are retained as the injection seam for the future dual-extension architecture (a dedicated `extensionKind: ["workspace"]` helper extension)
- **AND** `RemoteIndexer` compiles and is constructible from any `RemoteIndexApi`-shaped object, so the future helper extension swap is confined to `IndexerFactory`

### Requirement: Tolerant Unmark (Marked-Dir Lifecycle Robustness)

The `unmark` command SHALL always be able to remove a stored marked-dir entry, even when the stored entry was written by a buggy older version under a wrong path form (e.g. a mangled Windows `C:\home\...` fsPath for a remote dir). `SourceDirManager.removeDir` SHALL match entries across all stored forms (URI string, `uri.path`, `fsPath`, Windows case-insensitively), SHALL return whether anything was removed, and the unmark command SHALL NOT gate on a `contains` precheck.

#### Scenario: Unmark a wrongly-stored remote dir

- **WHEN** the Memento contains a legacy mangled entry (e.g. `file:///c%3A/home/liux/projects/...` produced by `uri.fsPath` + `path.resolve` on a Windows UI host)
- **AND** the user runs *Unmark source dir* on the corresponding folder in a Remote-SSH window
- **THEN** `removeDir` matches and removes the entry by resolved-fsPath form comparison
- **AND** the command reports success instead of a "not a marked dir" error

#### Scenario: Unmark with no matching entry

- **WHEN** no stored entry matches the target in any form
- **THEN** `removeDir` returns `false` without mutating the Memento
- **AND** the command shows the "not a marked dir" message

### Requirement: Multi-Session Concurrency

The extension SHALL support multiple concurrent Android debug sessions active in the same VSCode window. Each session SHALL be independent: a failure or termination in one session SHALL NOT affect any other session. The user SHALL be able to attach to device-A/process-X and device-B/process-Y simultaneously.

#### Scenario: Two sessions attached concurrently

- **WHEN** user invokes "Attach to Process" twice in succession (e.g., once for an app on device-A, once for an app on device-B)
- **THEN** both sessions reach the running state
- **AND** each session has its own JDI connection to its own JDWP socket
- **AND** each session has its own SourceIndex (initial index pushed via `custom/syncSourceIndex` after session start; both populated from the shared in-memory `IndexManager` entries)
- **AND** each session's breakpoints, stack traces, variables, and step operations are isolated to that session

#### Scenario: One session's breakpoint hit does not affect other session

- **WHEN** two sessions are active
- **AND** a breakpoint in session-A hits
- **THEN** only session-A suspends; session-B continues running
- **AND** VSCode's debug UI shows session-A's stopped event with session-A's stack/variables
- **AND** session-B's call stack / threads remain in their last reported state

#### Scenario: Multiple sessions broadcast index updates

- **WHEN** user marks a new source dir while two sessions are active
- **THEN** `IndexManager` rebuilds the in-memory index
- **AND** `SessionManager.broadcastIndex` iterates both `SessionContextImpl` instances
- **AND** each session's adapter receives the updated entries via `custom/syncSourceIndex`
- **AND** subsequent `setBreakpoints` in either session resolves against the new index

### Requirement: Per-Session Resource Ownership

Every active Android debug session SHALL have a `SessionContextImpl` instance that is the sole owner of: the Java Adapter JVM `ChildProcess`, the session's `AdbDevice` (which holds the session's `activeForwards`), the DAP port, the JDWP port, and the `DapServerProxy`. There SHALL be no module-level singletons holding any of these. Resources SHALL be released in `SessionContextImpl.dispose()` exactly once.

#### Scenario: Session owns its JVM child process

- **WHEN** a session is constructed via `SessionContextImpl.create(session, device, process, options)`
- **THEN** the JVM is spawned as the factory's last step and `SessionContextImpl.adapterProc` holds the `ChildProcess` reference
- **AND** `dispose()` calls `adapterProc.kill()` exactly once (idempotent — calling `dispose` twice is a no-op)

#### Scenario: Session owns its adb forward tracking

- **WHEN** a session is constructed
- **THEN** `SessionContextImpl.create` builds a fresh per-session `AdbDevice` (from `adb/AdbDevice.ts`) and calls `forwardJdwp(pid)` to install the forward
- **AND** the `AdbDevice`'s `activeForwards` set records the port
- **AND** `dispose()` calls `removeForward(jdwpPort)` which runs `adb forward --remove tcp:<port>`

#### Scenario: Cross-session resource isolation

- **WHEN** two sessions are active
- **THEN** terminating session-A does NOT kill session-B's JVM
- **AND** terminating session-A does NOT remove session-B's adb forward
- **AND** session-B's DAP proxy remains functional and can still send custom requests to its own adapter

### Requirement: Initial Index Delivered via Session Initializer

The extension SHALL deliver the initial source index to a newly started debug session when `onDidStartDebugSession` fires: a session initializer (wired in `extension.ts` via `SessionManager.setSessionInitializer`) SHALL read the `IndexManager` snapshot and send it to that session via the `custom/syncSourceIndex` DAP custom request, followed by a fallback `custom/syncBreakpoints` push of the current breakpoint snapshot. The session configuration SHALL NOT carry an `fqnInfo` field.

#### Scenario: Initial index pushed after session start

- **WHEN** a new debug session starts and `onDidStartDebugSession` fires for an `android-java` session
- **THEN** the initializer first rebuilds the index from the currently marked source dirs (`IndexManager.rebuildFromDirs(getAllDirs(), maxFiles)`) — activation alone never builds it, and the pre-refactor flow also built the index on every attach
- **AND** reads `IndexManager.getAllEntries()`
- **AND** sends `custom/syncSourceIndex` with the entries (only when the array is non-empty)
- **AND** sends `custom/syncBreakpoints` with `BreakpointManager.getCurrentBreakpoints()` (re-resolving previously-deferred BPs against the now-populated index)
- **AND** failures of either push are logged as warnings and do not break the session

#### Scenario: Deferred setBreakpoints resolve with loaded index

- **WHEN** VSCode sends `setBreakpoints` BEFORE the index push has been applied (BPs queued in `pendingJdiBps`)
- **AND** `handleAttach` runs: connects JDI, creates `bpManager`, calls `drainPendingBps()`
- **AND** the `custom/syncSourceIndex` push arrives, populating the adapter's `SourceIndex`
- **THEN** each deferred BP is reprocessed against the now-populated index
- **AND** FQNs resolve correctly and `verified: true` is reported for successfully-placed BPs

#### Scenario: No temp file and no config fqnInfo

- **WHEN** any debug session is constructed or terminated
- **THEN** no file is written to or read from any temp directory by the extension
- **AND** no `--index-file` CLI argument is passed to the Java Adapter JVM
- **AND** the session configuration carries no `fqnInfo` field (the Kotlin `handleAttach` branch for it stays dormant)

### Requirement: Per-Session AdbRunner Serial Targeting

Every adb command issued by the extension on behalf of a debug session SHALL target that session's specific device serial via the `-s <serial>` argument. The `AdbRunner.exec()` and `AdbRunner.start()` APIs SHALL accept an optional `serial?: string` second parameter; when provided, the runner SHALL inject `-s <serial>` as the first argument to the spawned adb process.

#### Scenario: Forward targeting specific serial

- **WHEN** the session's per-instance `AdbDevice` installs the forward via `forwardJdwp(pid)`
- **THEN** it calls `runner.exec(['forward', `tcp:${port}`, `jdwp:${pid}`], this.serial)`
- **AND** the runner injects `-s <device.serial>` at the front: `adb -s <device.serial> forward tcp:<port> jdwp:<pid>`

#### Scenario: listDevices remains global

- **WHEN** `attach` command needs to enumerate devices (before a session exists)
- **THEN** it uses `AdbDeviceFinder.findAll()` which calls `runner.exec(['devices'])` WITHOUT a serial parameter
- **AND** the runner invokes plain `adb devices` (no `-s` injection)
- **AND** the returned list is presented to the user for selection (auto-skipped when exactly one ready device exists)

#### Scenario: Two sessions on different devices run concurrent adb commands

- **WHEN** session-A on `emulator-5554` and session-B on `emulator-5556` are both active
- **AND** session-A's SessionContextImpl calls `forwardJdwp`
- **THEN** only `emulator-5554` receives the forward command
- **AND** session-B's device is untouched

### Requirement: Adb Stdin Write Robustness

The adb runner SHALL guard stdin writes so that a child process exiting before consuming its stdin (EPIPE) never produces an unhandled error. The failed write SHALL be absorbed with a log entry, the pending command SHALL still settle via the child's exit event, and the extension host SHALL remain alive.

#### Scenario: Child exits before consuming stdin

- **WHEN** `execWithStdin` writes to a child whose stdin pipe has already closed
- **THEN** the resulting write error is logged rather than surfacing as an unhandled error
- **AND** the pending command promise still resolves with the child's exit result

#### Scenario: Stdin consumed normally

- **WHEN** the child consumes the written stdin and exits normally
- **THEN** command output is returned unchanged (the guard has no effect on the happy path)
