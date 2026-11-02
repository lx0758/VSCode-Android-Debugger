## ADDED Requirements

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

## REMOVED Requirements

### Requirement: Simple Conditional Breakpoints
**Reason**: 表达式求值管线的升级把断点条件从"单个字段访问与字面量比较"扩展为完整的共享表达式语言，"简单"这一限定不再是独立的行为面。
**Migration**: 由本 delta 中的新 `条件断点` 需求替代——同样的场景由完整表达式条件覆盖（既有条件断点继续工作，其条件按扩展后的语法求值）。

## MODIFIED Requirements

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
