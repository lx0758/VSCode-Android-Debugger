## 1. Slice 1 — SourceDirManager 抽离（低风险）

- [x] 1.1 创建 `src/core/SourceDirManager.ts`，将 `src/source/dirs.ts` 的 `SourceDirsManager` 类迁移过去；构造函数接受 `vscode.ExtensionContext`（从 `context.workspaceState` 取 Memento）；事件类型改为 `DirsChangeCallback: () => void`；保留 `getAllDirs / addDir / removeDir / contains / findCoveringAncestor / getDirsForUriSync / onDidChange` 全部方法和 `migrateLegacyConfigIfNeeded` 私有逻辑。验证：`npm run compile` 通过。
- [x] 1.2 更新所有引用 `source/dirs` 的 import 路径到 `core/SourceDirManager`（包括 `extension.local.ts` / `source/decoration.ts` / `source/treeView.ts` / `commands/attach.ts` / `commands/markSourceDir.ts` 等）。验证：`npm run compile` 0 error。
- [x] 1.3 删除 `src/source/dirs.ts`。验证：`git grep "source/dirs"` 无业务代码命中。
- [x] 1.4 F5 启动调试，验证 `vscode-android-debugger.markSourceDir` / `unmarkSourceDir` 命令行为不变（标记 / 取消 / 重复标记 / 父目录覆盖提示）。验证：用户视角无回归。

## 2. Slice 2 — indexer 包拆分（中风险）

- [x] 2.1 在 `src/core/indexer/` 下创建 `Indexer.ts`，导出 `IndexOptions` / `FqnEntry` / `SourceEntry` 接口和 `Indexer` 接口（`buildIndex(dirs, options)` / `dispose()`）。验证：文件结构与 ARCHITECTURE.md §5.2 一致。
- [x] 2.2 创建 `src/core/indexer/LocalIndexer.ts`，把 `src/source/indexer.ts` 里的 `findSourceFiles` / `findSourceFilesViaFs` / `parseJavaFile` / `parseKotlinFile` / `readAndParse` 迁移为 `LocalIndexer` 类的方法。验证：`buildIndex` 在 UI 侧行为不变（含 progress 报告和 IndexTooLargeError）。
- [x] 2.3 创建 `src/core/indexer/RemoteIndexer.ts`，实现 `buildIndex` 通过 `vscode.extensions.getExtension<DebugRemoteApi>(uiExtId).exports.buildIndex(...)` 调用远端。验证：Remote-SSH 场景 fast path 仍走 remote ext。
- [x] 2.4 创建 `src/core/indexer/IndexerFactory.ts`，根据 `vscode.env.remoteName` 选择 Local / Remote。验证：factory 选择逻辑与 ARCHITECTURE.md §5.3 一致。
- [x] 2.5 更新 `src/source/indexer.ts` 的 import（保留 `RemoteExtApi` interface 在 `extension.remote.ts`），删除已迁移的 `buildIndex` / `parseJavaFile` / `parseKotlinFile` 等导出。验证：`npm run compile` 0 error。
- [x] 2.6 F5 启动 attach，验证 source index 行为不变（BP verified、断点解析）。验证：单 session 端到端 attach + breakpoint 设置通过。

## 3. Slice 3 — BreakpointManager 抽离（中风险）

- [x] 3.1 创建 `src/core/BreakpointManager.ts`，导出 `BreakpointEntry` / `BreakpointFile` / `BreakpointsChangeCallback` / `NewFileNeedsIndexCallback` 类型和 `BreakpointManager` 类。验证：类型与 ARCHITECTURE.md §5.4 一致。
- [x] 3.2 实现 `BreakpointManager` 构造：接受 `vscode.ExtensionContext` + `SourceDirManager`（用于 auto-include 判定），注册 `vscode.debug.onDidChangeBreakpoints` 到 context subscriptions。验证：构造时立即生效，无需调用方介入。
- [x] 3.3 实现 `parseChange(e)`：从 `e.added / removed / changed` 提取 `BreakpointFile[]` 列表 + 新文件路径列表（含 `enabled=false` BP）。验证：与 `extension.local.ts` 原 `pushBreakpointsForFiles` 行为等价。
- [x] 3.4 实现 `getCurrentBreakpoints()` / `onBreakpointsChanged(cb)` / `onNewFileNeedsIndex(cb)`。验证：API 签名与设计文档一致。
- [x] 3.5 把 `extension.local.ts` 里的 `pushBreakpointsForFiles` / `pushAllBreakpoints` / `collectBreakpoints` 调用点改走 `breakpointManager`（暂未注册的 callback 用临时引用）。验证：`npm run compile` 通过，运行时 BP 同步行为不变。
- [x] 3.6 删除 `src/source/breakpointFiles.ts`（tracker 已被 BreakpointManager 吸收）。验证：`git grep "breakpointFiles"` 无业务代码命中。
- [x] 3.7 F5 验证：toggle BP（启用 / 禁用 / 删除）都正确同步；BP 在 unindexed 文件时触发 auto-include。验证：用户视角无回归。

## 4. Slice 4 — AndroidJavaFactory 拆分（中风险）

- [x] 4.1 创建临时文件 `src/core/session/SessionContextImpl.ts`（暂作为承载，先不动 factory 调用），把 `AdapterLauncher` 和 `PortAllocator` 作为内部类迁入（`AdapterLauncher.launch(opts)` + `PortAllocator.allocateFreePort()`）。验证：编译通过，行为与 `src/debug/adapterLauncher.ts` + `portAllocator.ts` 等价。
- [x] 4.2 删除 `src/debug/adapterLauncher.ts` 和 `src/debug/portAllocator.ts`，更新 import。验证：`git grep` 无业务代码命中。
- [x] 4.3 删除 `src/debug/jdwpRegistry.ts` 和 `src/debug/indexSyncRegistry.ts`。验证：`git grep` 无业务代码命中。
- [x] 4.4 瘦身 `src/debug/androidJavaFactory.ts`：删除 `setActiveAdapter` / `killActive` / `setRestartContext` / `spawnFreshAdapter` / `setPort` / `clearPort` / `getJdwpPort` 方法，保留 `createDebugAdapterDescriptor` 但只读 `session.configuration.__adapterPort` 返回 `DebugAdapterServer(port)`，加内部 `adapterPorts: Map<sessionId, number>` 由 Slice 5 写入。验证：编译通过。
- [x] 4.5 F5 验证：单 session attach + 终止完整流程；中途手动 stopDebugging 后再次 attach 也能恢复。验证：MVP 现有流程不破。

## 5. Slice 5 — SessionContextImpl 引入（高风险）

- [x] 5.1 创建 `src/core/session/SessionContext.ts`，导出 `DeviceInfo` / `ProcessInfo` / `SessionContext` 接口（含 `session` 字段）。验证：与 ARCHITECTURE.md §5.6 一致。
- [x] 5.2 创建 `src/core/session/DapServerProxy.ts`，实现 `syncBreakpoints(files)` / `setSourceDirs(entries)` / `addSourceDirs(entries)` 包装 `session.customRequest`。验证：API 签名与设计文档一致。
- [x] 5.3 完整实现 `SessionContextImpl`：构造时 `await PortAllocator.allocateFreePort()` / `await PortAllocator.allocateJdwpPort()` / `await jdwpController.forwardJdwp()` / `AdapterLauncher.launch()` / `await waitForAdapterReady`；`dispose()` 一次性 release 所有资源；持有 `dapPort` / `jdwpPort` 为 readonly public。验证：单实例构造成功，dispose 幂等。
- [x] 5.4 创建 `src/core/SessionManager.ts`，导出 `SessionContext` / `SessionContextImpl` / `SessionManager` / `SessionManagerDeps`。验证：类型与 ARCHITECTURE.md §5.9 一致。
- [x] 5.5 实现 `SessionManager` 构造：注册 `onDidStartDebugSession` / `onDidTerminateDebugSession` / `registerDebugAdapterDescriptorFactory('android-java', ...)` 匿名 lambda。验证：注册时机在 context.subscriptions.push 内，dispose 随 context。
- [x] 5.6 实现 factory lambda 内：`new SessionContextImpl(session, cfg.__device, {pid, name}, {javaPath, jarPath, fqnInfo, adbRunner})` + `sessions.set(session.id, ctx)` + 返回 `DebugAdapterServer(ctx.dapPort)`。验证：单 session 启动时 SessionContextImpl 构造成功。
- [x] 5.7 实现 `onSessionStart` / `onSessionTerminate`：`onSessionStart` 触发 `sessionInitializerCallback(ctx)`；`onSessionTerminate` 调 `ctx.dispose()` + `sessions.delete`。验证：terminate 后 sessions Map 不含已终止 session。
- [x] 5.8 实现 `attachToProcess(device, process, folder?)`：构造 config（含 `__device / __pid / fqnInfo` 占位）调 `vscode.debug.startDebugging`。验证：可被 UI 命令调用。
- [x] 5.9 实现 `restartActiveSession()` / `restartSession(sessionId)`：从 sessions Map 取 ctx，调 `vscode.debug.stopDebugging(ctx.session)`，再调 `attachToProcess(ctx.device, ctx.process)`。验证：restart 后 ports / JVM / forward 全是新分配的。
- [x] 5.10 实现 `broadcastIndex` / `broadcastBreakpoints`：遍历 sessions，对每个 ctx 调 syncIndex / syncBreakpoints（catch 错误不抛出）。验证：所有活跃 session 都收到更新。
- [x] 5.11 `extension.local.ts` 里旧的 `onDidStartDebugSession` / `onDidTerminateDebugSession` 钩子改走 SessionManager 注册；保留 `DebugIndexSync` 作为 fallback 路径（不动）。验证：MVP 流程不破。

## 6. Slice 6 — Extension.ts 重写 + 桥接 + fqnInfo（中风险）

- [x] 6.1 K1 修改 `adapter/src/main/kotlin/.../Main.kt`：删除 `loadInitialIndex(args)` 函数和它的调用；`DapServer` 构造不再传 `initialIndexEntries`。验证：Kotlin 编译通过。
- [x] 6.2 K2 修改 `adapter/src/main/kotlin/.../DapServer.kt`：构造器去掉 `initialIndexEntries: List<Map<String, String>>` 参数；删除 `init` 块里的 `indexer.add(initialIndexEntries)`；在 `handleAttach` 里 JDI attach + bpManager 创建之后、`drainPendingBps()` 之前加 `args.get("fqnInfo")?.asJsonArray?.let { ... indexer.add(list) ... }`。验证：Kotlin 编译通过，attaching 后立即 setBreakpoints 能 verified=true。
- [x] 6.3 T1 `SessionManager.attachToProcess` 的 config 改为 `config.fqnInfo = this.indexManager.getAllEntries()`；factory lambda 内 `fqnInfo: cfg.fqnInfo as FqnEntry[]` 注入 SessionContextImpl options。验证：session config 携带 fqnInfo 字段。
- [x] 6.4 T2 重写 `IndexManager`：删除 `getIndexFilePath` / `atomicWriteIndexFile` / `dispose` 里的 unlink，删除 `<tmpdir>/d4a-index/` 相关代码；保留 `getAllEntries` / `onIndexReady` / `addFiles` / `resolveByFqn` / `resolveFqnsByFile`。验证：纯内存实现，编译通过。
- [x] 6.5 T3 创建 `src/Extension.ts`：作为 dispatcher，UI 侧激活时 `await activateUI(context)`；实现 `activateUI`：解析 `adb / jdk / jarPath`，创建 4 个 Manager，注册跨 Manager 回调桥接，注册全局命令。验证：与 ARCHITECTURE.md §6 一致。
- [x] 6.6 T4 把 `src/source/decoration.ts` / `treeView.ts` / `context.ts` 移到 `src/ui/`，更新 import。验证：`npm run compile` 通过。
- [x] 6.7 T5 重写 `src/commands/attach.ts`：只剩 `pickDevice` + `pickProcess` + `sessionManager.attachToProcess(device, process)`，删除所有资源创建 / spawn JVM / port 分配逻辑。验证：attach 命令外部行为不变。
- [x] 6.8 T6 在 `Extension.ts` 注册桥接：`idx.onIndexReady → ses.broadcastIndex`；`bpm.onBreakpointsChanged → ses.broadcastBreakpoints`；`bpm.onNewFileNeedsIndex → idx.addFiles`；`ses.setSessionInitializer → ctx.syncBreakpoints(bpm.getCurrentBreakpoints())`。验证：4 个 Manager 通过 Extension 桥接形成完整数据流。
- [x] 6.9 T7 删除 `src/extension.local.ts`。验证：`git grep` 无业务代码命中。
- [x] 6.10 T8 删除 `src/commands/restartSession.ts`（合并到 `SessionManager.restartActiveSession`），更新 import。验证：`vscode-android-debugger.restartSession` 命令仍可用。
- [x] 6.11 T9 删除 `src/source/breakpointFiles.ts`（已被 BreakpointManager 吸收）。验证：`git grep` 无业务代码命中。
- [x] 6.12 端到端验证：开两个 attach（同一设备两个 process 或两个设备），两个 session 同时存活；任一 terminate 都不影响另一个；sourceDirs 变更同时广播到两个；BP 切换只影响对应 session。验证：多会话核心场景通过。

## 7. Slice 7 — 收尾（低风险）

- [x] 7.1 删除 `src/debug/indexSync.ts` 和任何 fallback 旁路 stub。验证：`git grep` 无业务代码命中。
- [x] 7.2 删除 `src/debug/androidJavaFactory.ts`（factory lambda 已内联到 SessionManager）。验证：`npm run compile` 通过。
- [x] 7.3 把 `docs/ARCHITECTURE.md` 里的代码片段与实际实现做一次 diff，更新不一致的地方（端口分配、options 字段、callback 签名等）。验证：文档与代码对齐。
- [x] 7.4 运行 `openspec validate multi-session-refactor --strict`，修复任何 validation 错误。验证：`openspec validate` 0 error / 0 warning。
- [x] 7.5 最终回归测试：F5 启动 → attach → toggle BP（enabled / disabled / deleted）→ restart → terminate → 第二次 attach → 开第二个 attach 验证多会话 → sourceDirs 变更验证广播 → BP 切换验证增量同步。验证：MVP 全流程 + 新增多会话场景全部通过。

## 8. 实现期架构对齐（Post-Design Revisions，见 design.md R1-R9）

- [x] 8.1 adb 模块三件套：删除 `devices.ts` / `jdwp.ts` / `path.ts` / `runner.ts`，新建 `adb/AdbRunner.ts`（惰性二进制解析内聚）+ `adb/AdbDevice.ts`（per-instance forward 簿记，替代设计稿 AdbJdwpController）+ `adb/AdbDeviceFinder.ts`。验证：`npm --prefix extension run build` 通过，attach 枚举设备与 forward 行为不变。
- [x] 8.2 Manager 零依赖改造：`SessionManagerDeps` 收窄为 `{javaPath?, adbRunner?, getFqnInfo?}`；`BreakpointManager` 构造注入 `isPathMarked` callback；断点契约独立 `core/BreakpointTypes.ts`；`rg` 验证四 Manager 相互 import 为零。
- [x] 8.3 UIManager + UiBridges：新建 `ui/UIManager.ts`（statusBar/treeView/decoration/contextKey + 全部 7 命令）与 `ui/UiBridges.ts`（`SourceDirsBridge`/`SessionBridge`）；`SourceDirManager` 退化为纯数据组件（`addDir/removeDir` 只收 path）；`commands/attach.ts` 改吃 bridge callback。验证：mark/unmark/树/装饰/状态栏/context key 全部恢复工作。
- [x] 8.4 删除 `uiActivate.ts`（activateUI 内联 extension.ts，JDK 预检内聚 `util/jdk.ts#ensureJdkOrWarn`）与 `ui/UiExtApi.ts`（workspace→UI 反向通道，Memento per-host 隔离失效 + 竞态）。验证：`git grep` 无残留引用。
- [x] 8.5 remote/ 补齐：`extension.remote.ts` → `remote/DebugRemoteApiImpl.ts`（类化）+ `remote/DebugRemoteApi.ts` 接口（type-only 复用 `Indexer` 契约）。验证：Remote-SSH 场景 buildIndex API 不变。
- [x] 8.6 Indexer 注入：`createIndexer({extensionId, onFallback})` → 构造注入 `new IndexManager(indexer)`；降级时状态栏 `setIndexerMode('fallback')`；修正 `RemoteIndexer.dispose()` 误触发 fallback。验证：降级路径可观测。
- [x] 8.7 初始索引通道收口：config `fqnInfo` 不再写入（attach-arguments 通道休眠，Kotlin handleAttach 分支保留兼容）；初始索引经 `sessionInitializer` 的 `ctx.dap.syncSourceIndex` 推送 + `custom/syncBreakpoints` 兜底；**推送前先 `rebuildFromDirs` 刷新索引**（修复回归：持久 IndexManager 激活后无初始构建触发点，entries 恒空 → 全部 BP `verified=false` / 断点不触发）。验证：attach 后日志出现 `initial index push: N entries` + `custom/syncSourceIndex`，BP verified=true、断点可命中。
- [x] 8.8 交互与可观测性：单 ready 设备跳过选择框 + picker 词条 zh-cn/en 本地化；adapter 日志 TCP 模式走 stdout（`Logging.useStdout`）+ 52 处模块 tag；extension 侧 `[adapter]` / `[adapter stderr]` 标记。验证：输出通道日志可读性提升，stdio 模式不受影响。
- [x] 8.9 清理与修复：out/ 陈旧产物清理；修复重构丢失的源目录管理 UI 装配（treeView 注册、decoration provider、context key、命令）；`source/{treeView,decoration,context}.ts` → `ui/sourceDirs{TreeView,Decoration,Context}.ts` 语义化改名。
- [x] 8.10 文档同步：`docs/ARCHITECTURE.md` 全文对齐实际实现（架构图/目录/职责/接口签名/时序/迁移清单/决策记录 D14-D21），delta spec 对齐初始索引投递与 AdbDevice 命名。验证：`npm --prefix extension run build` 通过，文档 grep 无过时引用。
- [x] 8.11 自定义 DAP 命令治理：wire 命令统一加 `custom/` 前缀（`sourceDirs/set` → `custom/syncSourceIndex`，正名——payload 是 fqn→file 索引而非目录；`syncBreakpoints` → `custom/syncBreakpoints`）；删除死命令 `sourceDirs/add`（TS 侧从未调用，双侧删除 `addSourceDirs`/`handleSourceDirsAdd`）；DapServerProxy 方法名与 wire 命令对齐（`syncBreakpoints`/`syncSourceIndex`）；双侧日志字符串同步。验证：`npm run build`（Gradle + tsc）通过，全仓 grep 无旧 wire 名残留。
- [x] 8.12 Remote-SSH 修复（design.md R9）：新建 `util/uri.ts`（canonical key / URI 解析 / `isUnderDir` / context 多形态）；`SourceDirManager` Memento 改存 URI 字符串 + 读时升级 legacy 条目 + `removeDir` 宽容匹配返回 boolean；`BreakpointManager`/`IndexManager`/`LocalIndexer` canonical key + `vscode.Uri` 事件；`LocalIndexer` 远程可达化（`findFiles(RelativePattern)` + `vscode.workspace.fs` 递归，弃 Node fs）；`markSourceDir` unmark 移除 contains 前置拦截；删除留空 workspace 侧实现 `remote/`，`IndexerFactory`/`RemoteIndexer` 保留为将来双插件架构对接缝隙（`RemoteIndexApi` 内联契约）；Kotlin `normalizeSourcePath` 归一化 `vscode-remote://` source.path；`extension.ts` 去分发逻辑（createIndexer 接线保留 + remoteName 降级提示）；i18n 死词条清理。文档同步：ARCHITECTURE.md（§5.1/5.2/5.5 重写 + 迁移清单 + D22-D24）、delta spec（REMOVED "Remote-Side TS SourceIndexer API" + ADDED "Single-Path TS SourceIndexer"/"Tolerant Unmark"）。验证：`npm run build` 通过，`openspec validate --strict` 通过。
- [x] 8.13 adapter basename 平台无关化（Remote-SSH 实测回归）：`findLocationsForLine` 用 `File.separatorChar` 切 basename，Windows 宿主 + posix 源键时 `sourceName()` 比对恒失败 → 断点永不生效（本地正常、远程全挂的经典平台耦合）。新建 `util/SourcePaths.kt#sourceBasename`，按**路径自身形态**选分隔符（含 `/` 用 `/` 切，否则 `\`——不依赖宿主 OS，覆盖 Windows 宿主收 posix 键与 Linux 宿主双场景），替换 DapServer(4)/DtoTypes/BreakpointManager 共 6 处；`handleSyncBreakpoints` 不再吞 `tryPlaceBp` 的真实 reason（落 INFO 日志，提示措辞同步修正）。验证：`npm run build` 通过。
- [x] 8.14 DAP 回显双轨（第二轮实测：断点能触发但 UI 空心 + 堆栈帧找不到文件）：根因 = adapter 用 posix 内部键回显 `source.path`，Windows 客户端解析成本机路径 `\home\...`。TS：`FqnEntry` 增加可选 `sourceUri`（远程 = `vscode-remote://...` 完整 URI）、`BreakpointManager` payload 改发 `clientPath`（file→fsPath / 其余→完整 URI）、`util/uri.ts` 新增 `clientPath`；Kotlin：`FileEntry.uri` + `handleSyncSourceIndex` 透传 `sourceUri` + `displayPathByFile`（canonical key → 请求原样路径）+ 全部 DAP 输出（breakpoint/stopped 事件、stackTrace、loadedSources）改走 `displayPath`/`displayPathFor`，内部键与 registry 保持 posix 不变。验证：`npm run build` 通过。
