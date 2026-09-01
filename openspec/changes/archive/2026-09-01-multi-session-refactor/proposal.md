## Why

当前 MVP 实现用全局单例（`AndroidJavaFactory.adapterProc` / `indexSyncRegistry.current` / `jdwpRegistry.controller`）管理所有调试会话状态，只能支撑单会话。任何第二次 attach、任何并发 restart、任何 toolbar Restart 都会与全局状态争抢，造成资源泄漏、配置错位、`adb forward` 泄漏等问题。架构设计阶段已经敲定完整的多会话架构（详见 `docs/ARCHITECTURE.md`），现在需要把设计落到代码上。

## What Changes

- **拆分 `AndroidJavaFactory`**：**BREAKING** —— 把当前混在一起的"全局单例 + descriptor 工厂 + restart 缓存"拆掉。factory 变成 `SessionManager` 内的匿名 lambda，**只读** session config 返回 `DebugAdapterServer(dapPort)`，不持有任何状态
- **新增 `SessionContextImpl`**（per-session 资源所有者）：每个会话构造一次，自己分配 `dapPort` / `jdwpPort`、调用 `adb forward`、spawn JVM、保存 `AdbJdwpController` 引用、构造 `DapServerProxy`。`dispose()` 一次性完成 JVM kill + forward remove
- **新增 `SessionManager`**：管理 `sessions: Map<sessionId, SessionContextImpl>`，自己注册 `onDidStartDebugSession` / `onDidTerminateDebugSession` / `factory`。暴露 `attachToProcess` / `restartActiveSession` / `broadcastIndex` / `broadcastBreakpoints` 等 API
- **新增 `IndexManager`**：自建 `Indexer`，监听 `SourceDirManager.onDidChange` 重建内存索引。**BREAKING** —— 删除 `<tmpdir>/d4a-index/` 临时文件方案，索引条目改走 DAP attach 请求 `arguments.fqnInfo` 字段
- **新增 `BreakpointManager`**：自己注册 `onDidChangeBreakpoints`，暴露 `onBreakpointsChanged` 和 `onNewFileNeedsIndex` 两个回调（由 `Extension.ts` 桥接）
- **重命名 `SourceDirsManager` → `SourceDirManager`**（单数）：同时承担 UI 组件（`StatusBar` / `Decoration` / `TreeView`）的持有者
- **`AdbRunner` 接口扩展**：增加 `serial?: string` 参数，runner 自动注入 `-s <serial>` 前缀
- **删除模块**：`debug/androidJavaFactory.ts` / `debug/indexSync.ts` / `debug/indexSyncRegistry.ts` / `debug/jdwpRegistry.ts` / `source/breakpointFiles.ts`
- **Kotlin adapter 侧配套**：**BREAKING** —— `Main.kt` 删除 `loadInitialIndex`；`DapServer.kt` 构造器去掉 `initialIndexEntries` 参数；`handleAttach` 中新增 `indexer.add(args.get("fqnInfo"))`（在 `drainPendingBps()` 之前）
- **重构 `AttachCommand`**：退化成纯 UI（pickDevice + pickProcess + 调 `sessionManager.attachToProcess`），不负责资源创建
- **重构 `Extension.local.ts → Extension.ts`**：只剩"创建 4 个 Manager + 桥接回调 + 注册全局命令"

## Capabilities

### New Capabilities
（无 —— 重构不引入新的 capability 边界，只是内部架构变化）

### Modified Capabilities
- `android-java-debugger`: 多会话架构替换全局单例。删除 `Shared AdbJdwpController` / `Adapter JVM Lifecycle Owned by Factory` / `Index Temp File Hygiene` 三条 requirement；新增 `Multi-Session Concurrency` / `Per-Session Resource Ownership` / `Index Passed via DAP Attach Arguments` 三条 requirement；修订 `Atomic Session Teardown` / `Custom Restart Command` / `TS-Side SourceIndexer` 三条 requirement

## Impact

- **TypeScript 文件**：
  - 删：`src/debug/androidJavaFactory.ts` / `src/debug/indexSync.ts` / `src/debug/indexSyncRegistry.ts` / `src/debug/jdwpRegistry.ts` / `src/source/breakpointFiles.ts` / `src/extension.local.ts`
  - 拆：`src/source/indexer.ts` → `src/core/indexer/{Indexer, LocalIndexer, RemoteIndexer, IndexerFactory}.ts`
  - 移：`src/source/dirs.ts` → `src/core/SourceDirManager.ts`；`src/source/{decoration, treeView, context}.ts` → `src/ui/`
  - 新：`src/core/{SessionManager, BreakpointManager, IndexManager}.ts` + `src/core/session/{SessionContext, SessionContextImpl, DapServerProxy}.ts`
  - 改：`src/commands/attach.ts` 简化、`src/commands/restartSession.ts` 删除（合并到 SessionManager）、`src/adb/runner.ts` 加 serial 参数
  - 改：`src/extension.ts` dispatcher 内部指向新 `Extension.ts`
- **Kotlin 文件**：
  - 改：`adapter/src/main/kotlin/.../Main.kt` 删 `loadInitialIndex`
  - 改：`adapter/src/main/kotlin/.../DapServer.kt` 构造器签名简化 + `handleAttach` 加 fqnInfo 加载
- **用户可见行为**：attach / restart / breakpoint / source dirs 等命令的外部行为不变；多会话并发从"不支持"变为"支持"
- **测试**：MVP 阶段无自动化测试，重构以"行为不变 + 多会话新增"为验收
