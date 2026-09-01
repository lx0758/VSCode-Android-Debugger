## Context

MVP 阶段（单会话单设备）通过 3 个模块级单例支撑：`AndroidJavaFactory.adapterProc`、`indexSyncRegistry.current`、`jdwpRegistry.controller`。这套设计对单会话可行，但任何第二次 attach、并发 restart、toolbar Restart 都会与全局状态争抢，导致资源泄漏、配置错位、`adb forward` 泄漏等问题。完整的多会话架构已经写在 `docs/ARCHITECTURE.md`（与实现同步更新），本文档只记录实现路径与决策要点，不重复架构内容。

**主要约束**：
- 不引入新依赖（保持 TypeScript / Kotlin / VSCode API）
- 用户可见命令、状态机、UI 行为不变
- Kotlin adapter 侧改动要小（保证可维护）
- 迁移以 slice 为单位，每个 slice 结束都能跑 attach / restart / terminate 单测流程

## Goals / Non-Goals

**Goals:**
- 拆掉 3 个模块级单例，资源归 SessionContextImpl 所有
- 支持 N 个会话并发（每个独立 lifecycle、互不干扰）
- 索引数据从"落盘 → JVM 启动时读"改为"内存 → DAP attach arguments"
- Manager 自治：每个 Manager 自己处理自己的事，不依赖其他 Manager 实例

**Non-Goals:**
- 不改 VSCode debug 协议（仍走标准 DAP + 自定义请求，现名 `custom/syncSourceIndex` / `custom/syncBreakpoints`；原名 `sourceDirs/set` / `sourceDirs/add` / `syncBreakpoints`，`add` 已删除、其余已加 `custom/` 前缀，见 R8）
- 不改 Java Adapter 与设备的交互协议（仍走 JDWP / JDI）
- 不改 UI 命令的外部语义（`debugger-for-android.attach` 等命令 ID 不变）
- 不引入新的 capability 边界（spec 仍为 `android-java-debugger`，所有 delta 都在其中）
- 不做性能优化（重构以"行为不变"为验收基准，性能在后续 change 评估）

## Decisions

### D1：factory 形态 → SessionManager 内的匿名 lambda

**选择**：删除 `AndroidJavaFactory` 类，factory 是 `SessionManager` 构造时通过 `vscode.debug.registerDebugAdapterDescriptorFactory('android-java', { createDebugAdapterDescriptor: async (session) => { ... } })` 注册的匿名对象。

**理由**：
- 现有 `AndroidJavaFactory` 持有 `adapterProc / currentPort / currentJdwpPort / restartRunner / restartPid / sourceIndexEntries` 等状态，本质是"全局单例"
- 拆成 lambda 后，stateful 逻辑全部进入 `SessionContextImpl`，factory 只负责返回 `DebugAdapterServer(dapPort)`
- `dapPort` 由 `SessionContextImpl` 分配（构造里 `allocateFreePort()`），factory 只需从 `cfg.__dapPort` 或 `SessionContextImpl` 实例上拿

**备选**：保留 `AndroidJavaFactory` 类但改造成 stateless utility class。需要再开一个文件，类空方法多，价值不大。

### D2：SessionContextImpl 在 createDebugAdapterDescriptor 内构造

**选择**：SessionContextImpl 不在 AttachCommand 里构造，而是在 `factory.createDebugAdapterDescriptor(session)` 被调用时构造。

**理由**：
- 此时 `session.id` 已存在（VSCode 先建 session 再调 factory），sessions Map 可直接用 sessionId 做 key，**无需 attachId / sessionToken**
- `createDebugAdapterDescriptor` 是 async，SessionContextImpl 构造也是 async（要 `await forwardJdwp` / `await waitForAdapterReady`），二者兼容
- `await waitForAdapterReady` 之后才返回 `DebugAdapterServer(dapPort)`，保证 VSCode 连过来时 JVM 已 ready

**备选**：SessionContextImpl 在 AttachCommand 里构造（spawn JVM 提前），session config 里带 `__dapPort` 让 factory 读。问题：JVM 已经在跑了但 session 还没建（用户取消后 cleanup 麻烦）。

### D3：索引传递方式 → DAP attach arguments.fqnInfo

**选择**：删除 `--index-file=<path>` CLI 参数和 `<tmpdir>/d4a-index/` 临时文件方案。索引条目作为 session config 的 `fqnInfo` 字段写入，VSCode 自动放进 DAP `attach` 请求的 `arguments.fqnInfo`。Kotlin 侧在 `handleAttach` 里 `indexer.add(args.get("fqnInfo"))`。

**理由**：
- VSCode 等待 attach 响应后才发 setBreakpoints，adapter 在 handleAttach 里 indexer.add 早于 drainPendingBps，早到的 BP 重放时索引已就位
- 跨 VSCode 实例零冲突（entries 走 DAP 内存，不写共享文件）
- 无文件管理负担（无 atomic write、无 stale 文件扫描、无跨 PID 隔离问题）

**备选**：
- (a) per-session temp file：每个 session 写自己的临时文件，SessionContextImpl dispose 时删除。问题：仍有文件管理负担；JVM `--index-file` 路径要走 CLI 转义
- (b) 完全 in-memory，attach 后用 `sourceDirs/set` 自定义请求补传：问题：VSCode 在 attach 之前会发 setBreakpoints，setBreakpoints 到达时索引还没补传，BPs 全部 verified=false

### D4：Manager 间通信 → 回调 + Extension.ts 桥接

**选择**：每个 Manager 暴露回调接口（`onIndexReady` / `onBreakpointsChanged` / `onNewFileNeedsIndex`），Manager 之间不知道彼此实例。Extension.ts 在 activate 时注册跨 Manager 桥接。

**理由**：
- SourceDirManager 不需要知道 IndexManager 的存在，只 emit `onDidChange`
- BreakpointManager 不需要知道 IndexManager 的存在，只 emit `onNewFileNeedsIndex`
- IndexManager 不需要知道 SessionManager 的存在，只 emit `onIndexReady`
- SessionManager 不需要知道 BreakpointManager / IndexManager 的存在，只接收 setSessionInitializer 回调
- 单元测试友好：每个 Manager 可独立 mock

**备选**：Manager 之间互相依赖（构造注入）。问题：循环依赖风险，单元测试需要构造完整对象图。

### D5：资源归属 → SessionContextImpl 私有

**选择**：`SessionContextImpl.adapterProc` / `jdwpController` / `dapPort` / `jdwpPort` / `proxy` 全部是 private 或 readonly instance field。`dispose()` 一次性释放所有。

**理由**：
- 拆掉 `jdwpRegistry`（每个 session 自己的 controller）
- 拆掉 `factory.{currentPort, currentJdwpPort, restartRunner, restartPid}`（SessionContextImpl 自己持有）
- 拆掉 `factory.spawnFreshAdapter`（restart 走 SessionManager.restartActiveSession 全程 stop+re-attach）

**备选**：保留 factory.spawnFreshAdapter 做 toolbar Restart 路径。问题：仍需要 factory 缓存 restart context，与 per-session ownership 矛盾。

### D6：UI 组件归属 → SourceDirManager 私有

**选择**：`StatusBarManager` / `SourceDirDecorationProvider` / `SourceDirsTreeProvider` 在 `SourceDirManager` 构造里 new，并 push 到 `context.subscriptions`。Extension.ts 不再单独 new 这些组件。

**理由**：
- StatusBar/Decoration/TreeView 全部依赖 SourceDirManager 数据源（getAllDirs / contains / findCoveringAncestor）
- 由数据源持有 UI 是数据驱动 UI 的自然归属
- Extension.ts 不需要知道 UI 组件的存在，只 `new SourceDirManager(context)` 就完事

**备选**：Extension.ts 单独 new UI 组件。问题：UI 组件与数据源分离，跨 slice 迁移容易遗漏 UI 刷新逻辑。

### D7：AdbRunner 接口扩展 → 加 serial 参数

**选择**：`AdbRunner.exec(args: string[], serial?: string)` 和 `start(args: string[], serial?: string)`。runner 内部自动注入 `-s <serial>` 前缀。

**理由**：
- `AdbJdwpController` 构造时绑定 deviceSerial，后续所有 exec 只需传业务参数
- 调用方不需要关心 `-s` 拼装细节
- listDevices 这种全局命令不传 serial

**备选**：调用方手动拼 `-s` 数组前缀。问题：每个 adb 命令点都要写一遍 `runner.exec(['-s', serial, 'forward', ...], ...)`，容易遗漏。

## Risks / Trade-offs

### R1：Kotlin 改动跨语言协调风险

**风险**：`Main.kt` 和 `DapServer.kt` 各 5 行改动。开发跨语言 diff 时容易在 Main.kt 漏删 `loadInitialIndex`，或者 DapServer.kt 的 indexer.add 顺序与 drainPendingBps 顺序颠倒。

**Mitigation**：
- tasks.md 里 K1 / K2 两个明确 task 单元测试：`attach 后立即 setBreakpoints 是否 verified=true`
- 文档 §5.10 已写出 diff 摘要和顺序约束
- 改动前后跑一遍 MVP 测试流程（attach / restart / terminate）

### R2：多会话内存占用风险

**风险**：每个 session 一个 JVM 进程（50-100MB）+ SourceIndex（取决于项目规模，几 MB）。同时开 5 个会话可能吃 500MB+ 内存。

**Mitigation**：
- MVP 阶段不做并发限制
- 文档 §10 决策记录 D1 留口子：未来要加并发数限制时，可在 SessionManager 加 `attachToProcess` 前的 check
- 用户视角看 StatusBar，每个 session 的状态独立显示

### R3：slice 迁移期间"行为不变"的回归风险

**风险**：7 个 slice 顺序迁移过程中，每步都可能引入细微行为差异（例如 slice 3 的 BreakpointManager 抽取后，原 extension.local.ts 里的 "auto-include + push affected" 逻辑被拆到 Manager + 桥接链路，与原行为可能在边缘 case 上不一致）。

**Mitigation**：
- 每个 slice 末尾强制走完单测流程：F5 → attach → 加断点 → restart → 终止 → 第二次 attach
- 重点关注：auto-include 何时触发、断点文件路径解析、enabled=false 的 BP 是否正确传递
- slice 5（引入 SessionContext）之前不允许删 `extension.local.ts`，保证 fallback 路径可用

### R4：remote-side `__device / __pid` 序列化兼容性

**风险**：session config 里的 `__device` / `__pid` 字段依赖 VSCode config 不被 transform。但 VSCode 1.85+ 的 `DebugConfigurationProvider` 默认会做 `launch.json substitution`，可能干扰自定义字段。

**Mitigation**：
- 使用纯小写 + 双下划线前缀（`__device` / `__pid`），不与任何 VSCode 已知变量冲突
- 不使用 `${variable}` 语法（不会被替换）
- 如果 VSCode 未来引入新的 transform，slice 5 末尾测试覆盖

### R5：IndexManager 内存索引的 GC 压力

**风险**：`IndexManager.entries` 长期驻留，频繁 addFiles / rebuild 可能触发 Map 的反复分配。

**Mitigation**：
- `updateMaps` 用 `clear()` + 重建，对 10k entries 量级耗时 < 10ms
- addFiles 走增量 path，只 add 不 clear
- 现阶段不优化（不符合 Non-Goal 中的性能优化条款）

## Migration Plan

7 个 slice 顺序迁移，每个 slice 跑通单测流程：

### Slice 1 — SourceDirManager 抽离（低风险）
**目标**：把 `src/source/dirs.ts` 移到 `src/core/SourceDirManager.ts`，更新 import 路径。功能不变。
**验证**：`npm run compile` 通过；`debugger-for-android.markSourceDir` / `unmarkSourceDir` 命令行为不变。

### Slice 2 — indexer 包拆分（中风险）
**目标**：`src/source/indexer.ts` 拆成 `src/core/indexer/{Indexer, LocalIndexer, RemoteIndexer, IndexerFactory}.ts`。`buildIndex` / `parseJavaFile` / `parseKotlinFile` 逻辑迁移，文件名按架构图统一。
**验证**：buildIndex 行为不变；Remote 路径仍调 `extension.remote.ts` 的 buildIndex API。

### Slice 3 — BreakpointManager 抽离（中风险）
**目标**：把 `src/source/breakpointFiles.ts` 的 `BreakpointFileTracker` + `extension.local.ts` 里的 `pushBreakpointsForFiles` / `pushAllBreakpoints` / `collectBreakpoints` 合并到 `src/core/BreakpointManager.ts`。构造注入 `SourceDirManager` 用于 auto-include 判断。**回调接口先空着**，等 Slice 6 注册桥接。
**验证**：断点同步行为不变（pushBreakpoints incremental、enabled=false 状态保留）。

### Slice 4 — AndroidJavaFactory 拆分（中风险）
**目标**：
- 删 `src/debug/jdwpRegistry.ts` 和 `src/debug/indexSyncRegistry.ts`
- 把 `src/debug/adapterLauncher.ts` 和 `src/debug/portAllocator.ts` 内联到 `SessionContextImpl`（这个文件**先临时创建**，作为后续 slice 的承载）
- `AndroidJavaFactory` 类瘦身：只剩 `adapterPorts: Map<sessionId, dapPort>`，移除 `setActiveAdapter` / `killActive` / `setRestartContext` / `spawnFreshAdapter` 等所有方法
**验证**：单 session attach / restart / terminate 完整流程通过；attempts 2 的 restart 走 SessionManager.stopDebugging + attachToProcess（此 slice 内先实现 stub）。

### Slice 5 — SessionContextImpl 引入（高风险）
**目标**：
- 新建 `src/core/session/SessionContextImpl.ts`（含 inner classes: PortAllocator / AdapterLauncher / AdbJdwpController）
- 新建 `src/core/session/DapServerProxy.ts`
- 新建 `src/core/session/SessionContext.ts` (interface)
- 把 `factory.createDebugAdapterDescriptor` 改成构造 SessionContextImpl + 写入 `factory.adapterPorts`
- 新建 `src/core/SessionManager.ts`，包含 sessions Map + onDidStart/Terminate 处理
- `extension.local.ts` 里 `onDidStartDebugSession` / `onDidTerminateDebugSession` 改成由 SessionManager 注册
- **暂时保留旧路径**：原 `DebugIndexSync` 暂不删，作为旁路
**验证**：单 session attach / restart / terminate 完整流程通过；临时 DebugIndexSync 路径与 SessionContextImpl 路径行为一致。

### Slice 6 — Extension.ts 重写 + 桥接（中风险）
**目标**：
- 删 `src/commands/restartSession.ts`（合并到 SessionManager.restartActiveSession）
- 写 `src/Extension.ts`：纯桥接 + 全局命令
- 删 `src/extension.local.ts`
- 注册 4 个 Manager 的回调桥接（idx→ses, bpm→idx/ses, bpm→idx auto-include）
- 删 `src/source/decoration.ts` / `treeView.ts` / `context.ts`，迁移到 `src/ui/`
- 删 `src/source/breakpointFiles.ts`（已被 BreakpointManager 吸收）
- `AttachCommand` 改成纯 UI：pickDevice + pickProcess + 调 SessionManager.attachToProcess
- `IndexManager` 替换：删除 `getIndexFilePath` / `atomicWrite` 等文件相关方法，纯内存实现
- `fqnInfo` 走 DAP attach arguments：TypeScript 端 `config.fqnInfo = indexManager.getAllEntries()`，Kotlin 端 Main.kt 删 `loadInitialIndex`，DapServer.kt `handleAttach` 加 `indexer.add(args.get("fqnInfo"))` 在 drainPendingBps 之前
**验证**：
- 单 session attach / restart / terminate 完整流程通过
- **关键**：开两个 attach（不同设备）同时存活，互不干扰
- sourceDirs 变更触发 broadcast，两个 session 都收到
- 断点切换触发 broadcast，两个 session 都收到

### Slice 7 — 收尾（低风险）
**目标**：
- 删旧 `DebugIndexSync` 和旁路 stub
- 删 `AndroidJavaFactory.ts`（工厂 lambda 内联在 SessionManager 后整个文件空了）
- 文档更新：把 `docs/ARCHITECTURE.md` 里的代码片段同步到实际实现
- 添加 `openspec validate` 到 CI（如已配置）
**验证**：完整 MVP 测试 + 多会话测试 + restart 测试通过；`openspec validate` 通过。

### 回滚策略

每个 slice 都是独立可回滚的——slice N+1 失败可以回滚到 slice N 的代码状态。具体做法：
- slice 4-5 期间保留 `DebugIndexSync` 作为旧路径的旁路，直到 slice 6 验证通过
- slice 6 完成后 `extension.local.ts` 已经被删，回滚需要 git revert 整个 slice
- Kotlin 改动（slice 6 的 K1/K2）回滚时 Main.kt 和 DapServer.kt 一起 revert

## Open Questions

（无——所有架构决策已在 ARCHITECTURE.md 的 §10 决策记录中敲定，本文档不引入新的开放问题。）

## Post-Design Revisions（实现期修订）

7 个 slice 执行完毕后，实现围绕"**Manager 零相互依赖**"原则做了一轮架构对齐。以下修订**取代**上文对应决策中被取代的部分（原文保留作为历史记录）：

### R1：索引传递方式修订（取代 D3 的 TS 侧实现）

**变化**：初始索引不再写入 session config `fqnInfo` 字段。改为：会话 `onDidStartDebugSession` 时由 extension.ts 的 `sessionInitializer`（经 `SessionManager.setSessionInitializer` 注入）读取 `IndexManager.getAllEntries()`，经 `ctx.dap.syncSourceIndex(entries)`（wire 名 `custom/syncSourceIndex`）推送，随后 `ctx.dap.syncBreakpoints(...)` 兜底断点快照。

**理由**：
- `setSessionInitializer` 机制在 Manager 零依赖改造中恢复后，初始推送天然归位于 initializer 闭包（数据在闭包里取，SessionManager 不持有 IndexManager）
- **initializer 在推送前先按当前已标记目录 `rebuildFromDirs` 刷新索引**（激活后除 mark/unmark 外没有其他重建触发点；重构前流程每次 attach 现建索引，此步保证等价语义），否则 `entries` 恒为空、所有 BP `verified=false`
- `SessionContextOptions.fqnInfo` / `SessionManagerDeps.getFqnInfo` 仍构造/注入，但当前无消费点（`getFqnInfo` 保留为将来复用口子）
- Kotlin 侧 `handleAttach` 的 `fqnInfo` 解析分支保留为休眠兼容路径；`pendingJdiBps` 暂存机制保证早到 BP 在索引就位后正确 resolve

### R2：UI 组件归属修订（取代 D6）

**变化**：`StatusBarManager` / `SourceDirsTreeProvider` / `SourceDirDecorationProvider` / context key 与**全部 7 个用户命令**的注册统一归 `ui/UIManager.ts`，不再由 `SourceDirManager` 持有。UI 层与 core 的唯一接触面是 `ui/UiBridges.ts` 的两个 callback 契约（`SourceDirsBridge` / `SessionBridge`）；`SourceDirManager` 退化为纯数据组件（Memento + 事件）。`uiActivate.ts` 删除，UI 侧激活内联为 `extension.ts#activateUI`（JDK 预检内聚 `util/jdk.ts#ensureJdkOrWarn`）。

**理由**：
- D6 的"数据源持有 UI"让 UI↔Manager 边界不对称，且 SourceDirManager 因此背上了 UI 生命周期
- Manager 零依赖原则下，UI 层只允许依赖 callback 契约（构造参数 `{ dirs, session }`），Manager 不再被 UI 反向感知
- 命令注册收敛到 UIManager 后，extension.ts 只剩"创建 Manager + 桥接 + Bridge 构造"，职责更纯

### R3：adb 模块三件套（修订 D7 的载体）

**变化**：设计稿中的 `AdbJdwpController` inner class 升格为独立模块 `adb/AdbDevice.ts`（per-instance forward 簿记语义不变）；新增 `adb/AdbDeviceFinder.ts`（设备发现，返回 `AdbDevice[]`）；`path.ts` + `runner.ts` 合并为 `adb/AdbRunner.ts`，二进制探测（`findAdb`）内聚为模块私有实现，`DefaultAdbRunner` 首次使用时惰性解析（构造只收配置值，默认 `'auto'`）。

**理由**：设备抽象独立后可被 attach 命令（设备枚举）与会话上下文（forward）复用；文件名/职责与架构图一致。

### R4：删除 UiExtApi 反向通道

**变化**：`ui/UiExtApi.ts` 及 workspace→UI 的初始索引推送逻辑删除。理由：Memento 按 host 隔离使该通道天然失效；且与 initializer 推送构成竞态。索引单写入口 = UI 侧 initializer。

### R5：Indexer 策略构造注入

**变化**：`IndexManager` 不再自建 Indexer。激活入口 `createIndexer({ extensionId, onFallback })` 一次创建（Remote 不可用时降级 Local 并通知状态栏 `setIndexerMode('fallback')`），构造注入 `new IndexManager(indexer)`；`rebuildFromDirs(dirs, maxFiles)` / `autoIncludeFiles(files)` 为纯数据方法，由 extension.ts 桥接调用。

### R6：SessionManager API 形态微调

**变化**：`SessionManagerDeps` 收窄为 `{ javaPath?, adbRunner?, getFqnInfo? }`（全部可选 + 运行时兜底）；VSCode 钩子注册从构造函数拆为显式 `register(context, extensionPath)`；`SessionContextImpl` 改为私有构造 + `static create()` 异步工厂（失败自清理半成品）；`attachToProcess` 返回 `Promise<boolean>`；同 session 重复询问 factory 时复用已运行 JVM 的 DAP 端口（瞬断重连）。

### R7：交互与可观测性补充

- 设备选择仅一台 ready 设备时自动跳过选择框；picker 词条本地化（zh-cn/en bundle）
- adapter 日志：TCP 模式（`--port=`）下 `Logging` 输出切到 stdout（DAP 走 socket 互不干扰），52 处调用点加模块 tag（`[Main]` `[Server]` `[BP]` 等）；extension 侧 stdout 标 `[adapter]`、stderr 标 `[adapter stderr]`
- `SourceDirManager.addDir/removeDir` 签名简化为只收 `path`；断点数据契约独立为 `core/BreakpointTypes.ts`

### R8：自定义 DAP 命令命名治理

**变化**：自定义请求统一加 `custom/` 前缀，与标准 DAP 命令在 wire 层显式区分：
- `sourceDirs/set` → `custom/syncSourceIndex`（正名：payload 是 fqn→file 索引条目，不是"目录"；与 `custom/syncBreakpoints` 动词风格统一）
- `syncBreakpoints` → `custom/syncBreakpoints`
- `sourceDirs/add`（增量合并）删除——TS 侧从未调用（广播一律全量替换），双侧删除 `DapServerProxy.addSourceDirs` / Kotlin `handleSourceDirsAdd`

**理由**：
- `custom/` 前缀在 wire 层把自定义请求与标准 DAP 命令（`setBreakpoints` 等）显式区分，杜绝未来标准新增同名命令的冲突
- TS 侧 `DapServerProxy` 是唯一的 wire 名书写点（方法名保持干净形式），Kotlin `DapServer` 分发表是唯一的解析点——改名只碰两处

### R9：Remote-SSH 修复（路径模型统一 + 跨宿主死代码删除）

**背景**：打包安装到 Remote-SSH 窗口后源目录索引/断点全部失效。日志+代码+官方文档三层定位出三个根因：
1. **假路径**：`markSourceDir` 把远程 URI 的 `uri.fsPath`（Windows UI 宿主上被毁成 `\home\liux\...`）经 `path.resolve` 补上假 `C:` 前缀后存进 Memento——LocalIndexer 在 UI 宿主上 ENOENT、0 files；
2. **键不一致**：`BreakpointManager.toEntry` 用 `uri.fsPath` 产断点键（`\home\...`），而 VSCode 标准 `setBreakpoints` 在远程窗口发完整 URI（`vscode-remote://ssh-remote%2Bhost/...`），同一文件两种键；
3. **死代码**：`RemoteIndexer` 跨宿主调 `DebugRemoteApiImpl`——但 `extensionKind` 数组只是偏好序，VSCode **只选一个宿主**（`["ui","workspace"]` 实际只跑 UI 宿主，用户日志只有 `[DISPATCHER UI]`），且官方文档明确**跨宿主 `exports` 不可达**，整条链路不可实现。

**决策**：
- **路径模型**（`util/uri.ts` 单点实现）：Memento 存储 = URI 字符串（读时升级 legacy 条目）；内部键 = canonical path（`file:` → `fsPath`，其余 → `uri.path`）；adapter 侧 `normalizeSourcePath` 剥离 URI 前缀对齐；context key 发全形态超集（toString/path/fsPath）保证本地/远程菜单 `when` 都命中
- **workspace 侧实现删除留空**：`remote/`（DebugRemoteApi+Impl）删除——同 ID 双宿主 exports 不可达 + workspace 半边不激活，原链路不可实现；**`IndexerFactory` / `RemoteIndexer` 保留为将来双插件架构的对接缝隙**（规划：`extensionKind:["workspace"]` helper 扩展在 workspace 宿主原生跑索引，主插件经跨宿主通道调用，届时 `extensionId` 指向 helper、`RemoteIndexApi` 内联契约替换为 helper API 类型）；现行 `LocalIndexer` 统一走 `findFiles(RelativePattern)`（工作区内，glob 在远端批量执行）+ `vscode.workspace.fs` 递归（工作区外，弃 Node fs）
- **unmark 健壮性**：`removeDir` 移除 `contains` 前置拦截、多形态宽容匹配（URI/fsPath/大小写不敏感）、返回 boolean——错误存储的路径也能解除
- **降级提示**：`remoteName` 非空 → createIndexer onFallback + extension.ts 双保险置状态栏 `setIndexerMode('fallback')`（措辞改为 remote proxy，删除 RemoteIndexer 相关死词条）
- **adapter 侧平台无关 basename**（Remote-SSH 实测回归后补）：`findLocationsForLine` 的 `sourceName()` 校验原来用 `File.separatorChar` 切 basename——adapter 跑在 Windows UI 宿主而远程键是 posix 路径时切不开（整个路径当 basename），`sourceName` 比对恒失败 → 断点永远放置不了。收敛到 `util/SourcePaths.kt#sourceBasename`：**按路径自身形态选分隔符**（含 `/` 用 `/` 切——posix 绝对键必含 `/`，Linux 文件名合法含 `\` 也安全；Windows 键不含 `/`（NTFS 保留字符）落到 `\` 分支），不依赖宿主 OS——键的风格由数据来源决定，不由运行环境决定；6 处调用点替换。顺带修复 `custom/syncBreakpoints` 吞掉 `tryPlaceBp` 真实 reason 的问题（落 INFO 日志 + 提示措辞改为"类尚未被 JVM 加载或该行不可放置"）
- **回显双轨**（放置成功后第二轮实测：断点能触发但 UI 不亮 + 堆栈帧找不到文件）：adapter 回显给 VSCode 的 DAP `source.path` 不能用 posix 内部键——Windows 客户端把它解析成本机路径（`\home\...`）。TS 索引条目增加可选 `sourceUri`（远程 = `vscode-remote://...` 完整 URI，本地省略），断点 payload 的 `source.path` 改为 `clientPath`（同语义）；Kotlin `FileEntry.uri` 保存回显形态，全部 DAP 输出（breakpoint/stopped 事件、stackTrace、loadedSources）走 `displayPath`（`uri ?: path`），内部键与 registry 不变

**验证**：`npm run build`（Gradle + tsc）通过；`openspec validate --strict` 通过。
