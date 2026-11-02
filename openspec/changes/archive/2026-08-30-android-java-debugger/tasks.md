## 1. Repository & Build Setup

- [x] 1.1 Create monorepo layout: `extension/` (TypeScript) and `adapter/` (Java) directories
- [x] 1.2 Initialize TypeScript extension with `package.json`, `tsconfig.json`, and VSCode extension manifest (publisher, activation events, contributes)
- [x] 1.3 Initialize Kotlin adapter with Gradle (`build.gradle.kts`), gson dependency, JDK 8 toolchain, fat-jar packaging via Shadow plugin
- [x] 1.4 Add root `Makefile` (or `package.json` scripts) for `make build`, `make test`, `make dev` that builds both sub-projects
- [x] 1.5 Set up `.gitignore` (node_modules, target, build, *.class, *.jar, .vscode/.cache)
- [x] 1.6 Implement "Hello World" extension activation: registers one no-op command and shows a notification, confirming the build pipeline works end-to-end

## 2. TS Side: adb Command Runner

- [x] 2.1 Implement `AdbRunner` class that spawns `adb` as a child process, returns stdout/stderr/exit code
- [x] 2.2 Implement `adb devices` check: parse output, detect "no devices" vs "device unauthorized" vs ready
- [x] 2.3 Implement `adb jdwp -l`: invoke, parse PID list (one PID per line)
- [x] 2.4 Implement `adb forward tcp:<port> jdwp:<pid>`: invoke, track created forwards for cleanup
- [x] 2.5 Implement `adb forward --remove tcp:<port>`: cleanup helper
- [x] 2.6 ~~Implement port allocation helper: pick free port from configured range (default `8000-8100`)~~ **SUPERSEDED by Section 14c** — Uses `net.createServer().listen(0)` for OS-assigned ephemeral ports instead of a fixed range. Keep no code from this task; remove the fixed-range port allocator if present.
- [x] 2.7 Implement adb path auto-detection: probe common paths then `PATH`; respect user setting `vscode-android-debugger.adbPath`
- [x] 2.8 Implement `AdbRunner` interface that returns Promises (or async iterators for streaming)

## 3. TS Side: Command Registration and Process Picker UI

- [x] 3.1 Register command `vscode-android-debugger.attach` (label: "Debugger with Android")
- [x] 3.2 Implement `runAttachCommand`: orchestrate adb → process list → QuickPick → adb forward → spawn adapter
- [x] 3.3 Implement QuickPick UI: list PIDs with optional metadata (process name if obtainable), search/filter input
- [x] 3.4 Wire up: on process selection, run adb forward, then proceed to adapter spawn
- [x] 3.5 Handle errors with `vscode.window.showErrorMessage` (no device, no debuggable process, forward failure)
- [x] 3.6 Show QuickPick as cancellable; clean up forward on cancel

## 4. Kotlin Adapter: Skeleton

- [x] 4.1 Implement `Main` class: entry point that reads JSON-RPC from stdin line-by-line and writes responses to stdout
- [x] 4.2 Define DAP message types (initialize, attach, setBreakpoints, etc.) using gson
- [x] 4.3 Implement `DapServer` skeleton: parse requests, route to handlers, send responses with correct sequence numbers
- [x] 4.4 Implement `JdiConnection`: open socket to `localhost:<port>`, attach via `AttachingConnector`, expose `VirtualMachine` accessor
- [x] 4.5 Implement `handleInitialize`: return adapter capabilities (supportsConditionalBreakpoints, supportsLogPoints, etc.)
- [x] 4.6 Implement `handleAttach`: receive host/port from request, call `JdiConnection.attach`, send success response
- [x] 4.7 Implement basic lifecycle: handle `disconnect` (clean up forwards, exit)

## 5. TS Side: Spawn Java Adapter and DAP Client

- [x] 5.1 Implement Java adapter spawner: locate the fat-jar in extension resources, spawn with `java -jar <jar>`, capture stdio
- [x] 5.2 Implement JDK detection on extension activation: locate `java` binary, check version ≥ 8, show error if missing
- [x] 5.3 Implement DAP client: send initialize request, send attach request, listen for events
- [x] 5.4 Wire up: on adapter start, send initialize + attach; receive `initialized` event, proceed to breakpoint setup
- [x] 5.5 Implement VSCode `DebugSession` creation: create session via `vscode.debug.startDebugging` with appropriate configuration

## 6. Kotlin Adapter: Source Index

- [x] 6.1 Define `SourceIndex` class with maps: `fqnToFile`, `fileToFqns`, `lineTables`
- [x] 6.2 Implement Java source parser: extract `package` declaration and top-level class names (including inner classes) via regex on file content
- [x] 6.3 Implement Kotlin source parser: handle `package` + `class`/`object`/`interface` at top level
- [x] 6.4 Implement `addDir(dirPath)`: walk directory, parse each `.java`/`.kt`, populate index
- [x] 6.5 Implement `addFile(filePath)`: parse single file, populate index (for breakpoint auto-include)
- [x] 6.6 Implement lookup APIs: `resolveFqnsByFile`, `resolveFileByFqn`, `getLineTable` (lazy fetch from JDI)
- [x] 6.7 Implement DAP custom request `sourceDirs/set`: receive dir paths from TS, populate index

## 7. TS Side: Source Dirs Management

- [x] 7.1 Register command `vscode-android-debugger.markSourceDir` (label: "Mark as Debug Source Folder")
- [x] 7.2 Implement `SourceDirsManager` backed by `ExtensionContext.workspaceState` (Memento) with key `vscode-android-debugger.sourceDirs`. `addDir` appends the absolute path; `removeDir` filters; `getAllDirs` returns the array.
- [x] 7.3 Implement: send configured source dirs to adapter on session start
- [x] 7.4 Implement: on breakpoint set in unindexed file, send single-file path to adapter for auto-include
- [x] 7.5 ~~Handle `ConfigurationTarget.Workspace` vs `WorkspaceFolder` correctly based on whether user opened single folder or workspace~~ **SUPERSEDED by Memento storage** — Memento is workspace-scoped automatically; no per-mode target selection or path-format normalization needed.
- [ ] 7.6 First-run migration: in `SourceDirsManager` constructor (or on first `getAllDirs()`), if Memento is empty AND legacy `vscode-android-debugger.sourceDirs` exists in any `.vscode/settings.json` (per folder) or in the workspace's `ConfigurationTarget.Workspace` config, resolve relative paths to absolute, deduplicate, write to Memento, log how many were migrated. Idempotent.

## 8. Kotlin Adapter: Breakpoint Resolution

- [x] 8.1 Implement `handleSetBreakpoints`: parse request, extract file path + line numbers
- [x] 8.2 For each breakpoint: query SourceIndex for file → class FQN(s)
- [x] 8.3 For each class FQN: query JDI `VirtualMachine.classesByName()` to get `ReferenceType`
- [x] 8.4 Query JDI `ReferenceType.methods()` to find method containing target line
- [x] 8.5 Query JDI `Method.locationsOfLine()` to get bytecode offset
- [x] 8.6 Create JDI `BreakpointRequest` with method + offset, enable, register event listener
- [x] 8.7 Send DAP `setBreakpoints` response with `verified: true` on success or `verified: false` with reason
- [x] 8.8 On `BreakpointEvent`: resolve hit location (class FQN + offset → file + line via SourceIndex)
- [x] 8.9 Send DAP `stopped` event with reason `breakpoint`, thread ID, and source location

## 9. Kotlin Adapter: Event Loop and Step Operations

- [x] 9.1 Implement `EventDispatcher`: background thread that polls JDI event queue and routes events
- [x] 9.2 Map JDI events to DAP events (BreakpointEvent → stopped, VMDisconnectEvent → terminated)
- [x] 9.3 Implement `handleContinue`: JDI `VirtualMachine.resume()`, send response
- [x] 9.4 Implement `handleNext` (step over): create JDI `StepRequest` with `STEP_LINE` + `STEP_OVER`, enable for thread
- [x] 9.5 Implement `handleStepIn`: JDI `StepRequest` with `STEP_INTO`
- [x] 9.6 Implement `handleStepOut`: JDI `StepRequest` with `STEP_OUT`
- [x] 9.7 Implement `handlePause`: JDI `VirtualMachine.suspend()` for all threads (or specific thread)
- [x] 9.8 After step completes (StepEvent), resume per JDI semantics, emit `stopped` event, disable StepRequest

## 10. Kotlin Adapter: Stack Trace and Variables

- [x] 10.1 Implement `handleThreads`: enumerate JDI threads, return as DAP threads
- [x] 10.2 Implement `handleStackTrace`: for thread, get JDI `StackFrame[]`, build DAP frames
- [x] 10.3 For each frame: resolve class FQN + line number via JDI `Location`, look up source path via SourceIndex
- [x] 10.4 Implement `handleScopes`: for frame, return local variable scope (and optionally receiver/globals)
- [x] 10.5 Implement `handleVariables`: for scope, get JDI `LocalVariable[]` (via `visibleVariables()`)
- [x] 10.6 Implement `VariableFormatter`: convert JDI `Value` to DAP `Variable` (handle primitives, references, arrays, primitive types)

## 11. Kotlin Adapter: Log Breakpoints

- [x] 11.1 Detect log breakpoint: when `setBreakpoints` request includes `logMessage`
- [x] 11.2 Parse logMessage template: split by `{...}` placeholders, build list of literal and expression segments
- [x] 11.3 Implement simple expression evaluator: field access chains (`a.b.c`), no-arg method calls (`a.b()`), array index (`arr[0]`), literals
- [x] 11.4 Implement evaluator using JDI: `ObjectReference.getValue(field)`, `ObjectReference.invokeMethod(method, args)`, `ArrayReference.getValue(index)`
- [x] 11.5 On log breakpoint hit: evaluate template, format result, emit DAP `output` event with category `console`
- [x] 11.6 After logging, resume thread (don't suspend on log breakpoint)
- [x] 11.7 On evaluation failure: emit warning event with placeholder replaced by `<error: ...>`, continue execution

## 12. Kotlin Adapter: Simple Conditional Breakpoints

- [x] 12.1 Detect conditional breakpoint: when `setBreakpoints` request includes `condition`
- [x] 12.2 Parse simple condition: `expr OP literal` where expr is field access, OP is `==`/`!=`/`>`/`<`/`>=`/`<=`/`!=null`/`==null`
- [x] 12.3 Set unconditional JDI breakpoint first
- [x] 12.4 On hit, evaluate condition; if false, call `threadReference.resume()` and skip stopped event
- [x] 12.5 On eval failure, emit warning and resume (treat as condition not met)

## 13. TS Side: Debug Console and Error UX

- [x] 13.1 Route DAP `output` events to VSCode Debug Console
- [x] 13.2 Implement status bar item showing current debug state (idle / indexing / attached)
- [x] 13.3 Implement progress notification during SourceIndex build
- [x] 13.4 Surface adapter errors via `vscode.window.showErrorMessage` with actionable buttons
- [x] 13.5 Implement source/runtime version mismatch detection (compare resolved line with hit line, warn if different)

## 14. Remote-SSH (Scenario C1) — Dual-Loaded Extension with Remote TS Indexer

### 14a. Extension skeleton & activation split

- [x] 14.1 Set `extensionKind: ["ui", "workspace"]` in `extension/package.json` so the extension loads on both local and remote VSCode hosts in a `ssh-remote` workspace.
- [x] 14.2 In `extension/src/extension.ts`, detect side via `vscode.env.remoteName`: undefined → "local side" (UI + adb + Java Adapter); defined → "remote side" (only exposes `buildIndex`). Only the active side's logic runs; the other side is a no-op stub.
- [x] 14.3 Create `extension/src/extension.local.ts` — local-side activation: registers `vscode-android-debugger.attach` command, status bar, QuickPick, `sourceDirs` TreeView. Coordinates index build via remote ext (or local fallback).
- [x] 14.4 Create `extension/src/extension.remote.ts` — remote-side activation: returns `{ buildIndex(sourceDirs) }` from `activate()`. Registers no commands, no UI.
- [x] 14.5 Wire `extension.ts` to load the right side based on `remoteName`:
  ```typescript
  export function activate(context: vscode.ExtensionContext) {
    if (vscode.env.remoteName) {
      return require('./extension.remote').activate(context);
    }
    return require('./extension.local').activate(context);
  }
  ```

### 14b. Shared TS SourceIndexer module

- [x] 14.6 Create `extension/src/source/indexer.ts` — shared indexer logic, exported as `async function buildIndex(sourceDirs: string[], options: BuildIndexOptions): Promise<FqnEntry[]>`. Used by both local and remote sides.
- [x] 14.7 Implement `findSourceFiles(sourceDir)`: uses `vscode.workspace.findFiles(new RelativePattern(sourceDir, '**/*.{java,kt}'), '**/build/**')`. Returns `Uri[]`.
- [x] 14.8 Implement `parseJavaFile(content: string, fileUri: Uri): string[]` — regex-extracts `package` declaration + all top-level class/interface/enum/record/nested-class names; returns list of FQNs.
- [x] 14.9 Implement `parseKotlinFile(content: string, fileUri: Uri): string[]` — same for `package` + `class`/`object`/`interface`/`sealed class`/`data class`.
- [x] 14.10 Implement main `buildIndex` loop: for each source dir, enumerate files; for each file, `vscode.workspace.fs.readFile` + parse; batch reads with `Promise.all` (batch size 50); report progress every 50 files or 1s; honor cancellation token.
- [x] 14.11 Implement pre-flight file count check: total across all source dirs; if > `index.maxFiles` (default 10000), throw `IndexTooLargeError` with estimated time; UI catches and asks for confirmation before retrying.

### 14c. Local-side (kind=ui) implementation

- [x] 14.12 In `extension.local.ts`, before `sourceDirs/set`, decide indexing strategy:
  - If `vscode.env.remoteName` is defined: try `await ext.activate(); await ext.exports.buildIndex(sourceDirs)` first.
  - On success: use returned entries directly.
  - On failure: catch and fall back to local `buildIndex(sourceDirs)` call (same module, different execution context).
- [x] 14.13 Wire indexer progress to VSCode progress notification via `vscode.window.withProgress` (title: "Indexing N/M files…", cancellable: true).
- [x] 14.14 Add status bar entry: shows "Remote indexer: fast / slow fallback" so user knows which path is active.
- [x] 14.15 Refactor `extension/src/commands/attach.ts` — remove any `remoteName` branching for adb/Adapter (they're always local); only the indexing branch differs.

### 14d. Remote-side (kind=workspace) implementation

- [x] 14.16 Implement `extension.remote.ts`'s `activate()`:
  ```typescript
  export function activate(_context: vscode.ExtensionContext): RemoteExtApi {
    const api: RemoteExtApi = {
      async buildIndex(sourceDirs: string[]): Promise<FqnEntry[]> {
        return await buildIndex(sourceDirs, { token: new vscode.CancellationTokenSource().token });
      },
    };
    return api;
  }
  ```
  Note: remote side doesn't show VSCode progress UI directly; the local side wraps the call in `withProgress` so the user sees one unified progress notification.
- [x] 14.17 Verify the remote ext is loaded on the remote side automatically when user opens `ssh-remote` workspace. No manual command needed.

### 14e. Java Adapter simplification

- [x] 14.18 Refactor `adapter/src/main/kotlin/com/anomaly/debugger/source/SourceIndexer.kt` — strip out the parser and directory walker; keep only map storage and lookup APIs:
  - `set(entries: List<Map<String, String>>)` — populate from TS-provided data
  - `resolveFqnsByFile(filePath: String): List<String>`
  - `resolveFileByFqn(fqn: String): String?`
  - Removed `addDir`, `addFile`, regex parsers, `java.nio.file` filesystem usage from this file
  - Deleted unused `JavaSourceParser.kt` and `KotlinSourceParser.kt`
- [x] 14.19 Update `adapter/src/main/kotlin/com/anomaly/debugger/server/DapServer.kt` — change `handleSourceDirsSet` to accept the new payload shape `{entries: [{fqn, file}]}` and call `SourceIndexer.set(entries)`. Removed the `addDir(dir)` path entirely.

### 14f. Cleanup of old artifacts

- [x] 14.20 Deleted `extension/src/ssh/exec.ts` and `extension/src/ssh/config.ts` (the ssh/ directory has been removed). `tunnel.ts`/`discovery.ts` never existed. Also deleted `extension/src/commands/setupTunnel.ts` and the now-unused `extension/src/adb/port.ts`.
- [x] 14.21 Updated `extension/src/extension.ts` and `extension/src/util/config.ts`: removed `sshMode` config, `ssh.localHost`/`ssh.localUser`/`ssh.localPort` configs, `jdwpPortRange` config, `setupTunnel` command, and `SshAdbRunner` adb wrapper. Added `index.maxFiles` config (default 10000).
- [x] 14.22 Updated `README.md`: removed all "Setup Cross-host Tunnel" / "ssh -R" instructions; added a section explaining the dual-extension Remote-SSH pattern (remote ext walks files natively, local ext handles UI + adb + Adapter, no SSH tunnel required).

### 14g. Verification

- [ ] 14.23 Manual test: with a `ssh-remote` workspace open + a device USB-connected locally, click attach and verify ① adb runs locally, ② index builds in seconds (not minutes), ③ breakpoints resolve correctly, ④ Java Adapter connects to `localhost:LP` without tunnel. **Blocked on real device.**
- [ ] 14.24 Manual test: simulate remote ext failure (e.g., temporarily break the remote activation), verify local ext falls back to its own cross-host indexing and the session still works (slowly). **Blocked on real device.**

## 17. Incremental Source Index Updates During Active Session

Keeps the Java Adapter's SourceIndex in sync with workspace changes after the initial attach. Two triggers:
(a) `sourceDirs` config change → full re-build → `sourceDirs/set` (replace)
(b) New breakpoint in unindexed `.java`/`.kt` file → single-file parse → `sourceDirs/add` (merge)

### 17a. Java Adapter: add `sourceDirs/add` DAP request

- [x] 17.1 Add `add(entries: List<Map<String, String>>)` method to `adapter/src/main/kotlin/com/anomaly/debugger/source/SourceIndexer.kt` — populates the underlying `SourceIndex` via the existing merge-safe `SourceIndex.add()` (upsert on `fqnToFile`, append on `fileToFqns`).
- [x] 17.2 Add `sourceDirs/add` handler in `adapter/src/main/kotlin/com/anomaly/debugger/server/DapServer.kt` — accepts `{entries: [{fqn, file}]}`, calls `indexer.add(entries)`, returns `{indexedClasses}`.

### 17b. TS side: index-sync coordinator

- [x] 17.3 Create `extension/src/debug/indexSync.ts` — `DebugIndexSync` class:
  - Constructor: takes `DapClient`, `SourceDirsManager`, and `maxFiles`
  - `pushFullIndex(dirs)` — build full index → `sourceDirs/set`
  - `addFiles(files: string[])` — single-file parse → `sourceDirs/add`
  - `dispose()` — clear state, no-op afterwards
  - Reuses `parseJavaFile` / `parseKotlinFile` from `source/indexer.ts`
- [x] 17.4 Wire lifecycle in `extension/src/commands/attach.ts` — create `DebugIndexSync` after the initial `sourceDirs/set`; dispose it on `dap.on('exit')` and on `dap.on('event', {event: 'terminated'|'exited'})`.
- [x] 17.5 Expose the live `DebugIndexSync` instance to `extension.local.ts` via a module-level singleton (`extension/src/debug/indexSyncRegistry.ts`) so the watchers can access it.

### 17c. TS side: watchers

- [x] 17.6 In `extension/src/extension.local.ts`, add a watcher on the Memento-backed source-dirs list (e.g., a custom event emitted by `SourceDirsManager` after `addDir` / `removeDir`, since VSCode no longer emits `onDidChangeConfiguration` for Memento writes): when the list changes AND a sync is active, call `sync.pushFullIndex(await dirsManager.getAllDirs())`.
- [x] 17.7 In `extension/src/extension.local.ts`, add `vscode.debug.onDidChangeBreakpoints` listener: for each `e.added` breakpoint in `.java`/`.kt`, check `dirsManager.contains(path)`; if false AND sync is active, call `sync.addFiles([path])`.
- [x] 17.8 i18n: add logging strings for "Re-indexing after source dir change" and "Auto-included file: {path}". No user-visible UI strings needed (silent on the wire).

### 17d. Verification

- [ ] 17.9 Manual test: in an active session, add a new source dir → verify index re-builds and breakpoints in the new dir resolve after the re-build. **Blocked on real device.**
- [ ] 17.10 Manual test: in an active session, set a breakpoint in a file under an unmarked dir → verify the file is auto-included and the breakpoint verifies. **Blocked on real device.**

## 18. Inner Class & Synthetic Lambda Breakpoint Support

### 18a. Adapter: location resolution

- [x] 18.1 In `BreakpointManager.findLocationsForLine(filePath, line, indexedFqns, hintType=null)`:
  - Step 1: direct FQN lookup via `vm.classesByName(fqn)` for each indexed FQN, source-name-verified.
  - Step 2: hint class — O(1) check against the supplied `ReferenceType` (no `vm.allClasses()` walk).
  - Step 3: broad walk — only when steps 1+2 fail AND the enclosing class is loaded; filter `vm.allClasses()` by `${fqn}$*` prefix; early-break on first match.
- [x] 18.2 Add private helper `BreakpointManager.findLocationInSource(rt, line, expectedSourceName)` to verify `location.sourceName() == expectedSourceName` before accepting.
- [x] 18.3 `BreakpointManager.init` enables `ClassPrepareRequest` once at attach time (no source filter) so the adapter can react to classes loaded later.

### 18b. Adapter: dispatcher & server wiring

- [x] 18.4 `EventDispatcher.handleEvent` recognizes `ClassPrepareEvent` and tags it as `"class-prepare"`.
- [x] 18.5 `DapServer.handleJdiEvent` for `"class-prepare"` branch:
  - `set.resume()` first (default policy is `SUSPEND_ALL`; target VM must not stop on class prepare).
  - Call `tryDrainForClass(className, loadedType)` to retry placement of pending BPs whose FQN namespace matches the new class or whose enclosing class matches.
  - Pass `loadedType` as `hintType` so the retry hits step 2 (O(1)) and skips the broad walk.
- [x] 18.6 `tryDrainForClass(className, loadedType)` — namespace filter (`loadedClassName == fqn || startsWith("$fqn$")`), per-class retry, no work when `pendingJdiBps` is empty.
- [x] 18.7 `tryPlaceBp` accepts `hintType: ReferenceType? = null` and forwards to `findLocationsForLine`.

### 18c. Adapter: source index $N fallback

- [x] 18.8 `SourceIndex.resolveByFqn(fqn)` — when the exact FQN is not found, strip at the first `$` and look up the enclosing class. Return whatever it finds (or null).
- [x] 18.9 `SourceIndex.resolveByPrimaryFqn` delegates to `resolveByFqn` so stack-frame source resolution works for anonymous inner classes.

### 18d. Performance & logging

- [x] 18.10 `Logging.debugEnabled: AtomicBoolean`, set via `DEBUGGER_ANDROID_DEBUG=1` env var or `Logging.setDebugEnabled(true)`.
- [x] 18.11 `Logging.debug(msg)` early-returns when disabled; `Logging.debug { "..." }` lambda form skips string concat entirely.
- [x] 18.12 Drain per-BP log moved from INFO to DEBUG.
- [x] 18.13 `findLocationsForLine` step 3 skipped when `results.isNotEmpty()` (direct FQN hit) or `hintType != null` (common fast path).

### 18e. Verification

- [x] 18.14 Manual test: breakpoint in `new Runnable() { public void run() { ... } }` — verified the BP fires AND `stopped` event source path/line resolve back to the source file.
- [x] 18.15 Manual test: breakpoint set BEFORE class is loaded — verified placement on `ClassPrepareEvent`.
- [x] 18.16 Manual test: disable/re-enable BP preserves the BP id (state machine: ACTIVE/DISABLED/REMOVED).

## 19. Session Lifecycle, Forward Tracking, and Restart Command

Atomic session teardown on every terminate (kill JVM + remove forward), custom restart command that does a full re-attach, shared `AdbJdwpController` so forward tracking survives across modules, and factory-owned JVM lifecycle using `ChildProcess` liveness (no TCP probe).

### 19a. Adapter: loop on accept() with per-session executor

- [x] 19.1 Refactor `adapter/src/main/kotlin/.../Main.kt#runTcp`: replace the single-shot `accept()` with `while (!Thread.currentThread().isInterrupted) { serverSocket.accept(); Thread { DapServer(sessionExecutor, ...).run() }.start() }`. Each accepted client gets a fresh `DapServer` AND a fresh `Executors.newCachedThreadPool { Thread(it, "dap-handler").apply { isDaemon = true } }`. Each client's `finally` runs `sessionExecutor.shutdown()` + `awaitTermination(2s)` + `client.close()`.
- [x] 19.2 Verify the JVM survives VSCode's native Restart: send `disconnect`, observe the JVM's `accept()` returns to blocking state, observe the new session's DAP client gets accepted and a new `DapServer` starts on it.

### 19b. TS: AndroidJavaFactory owns the JVM ChildProcess

- [x] 19.3 Add fields `adapterProc: ChildProcess | undefined` and `currentPort: number | undefined` to `AndroidJavaFactory`.
- [x] 19.4 Add `setActiveAdapter(proc, port)` — registers a JVM, killing any prior tracked JVM first (idempotent).
- [x] 19.5 Add `killActive()` — calls `proc.kill()` (try/catch) and clears `adapterProc` + `currentPort`. Idempotent.
- [x] 19.6 Add `getPort()` — returns `currentPort`.
- [x] 19.7 Keep `clearPort()` and `setPort(port)` as no-ops (legacy callers in `attach.ts#cleanup` still call `clearPort`; the new model makes them irrelevant but kept for compatibility).
- [x] 19.8 Add `setRestartContext({ javaPath, jarPath, entries })` — stores the three pieces the factory needs to spawn a fresh JVM from `createDebugAdapterDescriptor`.
- [x] 19.9 Make `createDebugAdapterDescriptor(session)` async. Read `session.configuration.__adapterPort`. If it equals `currentPort` AND `isProcAlive(adapterProc)`, return `new vscode.DebugAdapterServer(cfgPort)`. Otherwise, if `javaPath`/`jarPath` are present, spawn a fresh JVM (`allocateFreePort()` → `AdapterLauncher.launch(...)` with `--index-file=<writeIndexFile(entries)>` → `waitForAdapterReady(proc, 10s)` → `setActiveAdapter`). If restart context missing, throw `restartNoContext`.
- [x] 19.10 Add helper `isProcAlive(proc) = proc.exitCode === null && proc.signalCode === null`. Do NOT TCP-probe the port.
- [x] 19.11 Wire `attach.ts#attachToProcess` to call `factory.setRestartContext({ javaPath, jarPath, entries: indexEntries })` before launching the JVM, then `factory.setActiveAdapter(adapterProc, dapPort)` after `waitForAdapterReady` succeeds.

### 19c. TS: shared AdbJdwpController singleton

- [x] 19.12 Create `extension/src/debug/jdwpRegistry.ts` — module-level singleton: `setJdwpController(c)`, `getJdwpController()`, `clearJdwpController()`.
- [x] 19.13 In `attach.ts#runAttachCommand` (and `runAttachToPidCommand`), after constructing the `AdbJdwpController`, call `setJdwpController(controller)`.
- [x] 19.14 Update `cleanup()` in `attach.ts` to accept `jdwpController` and call `jdwpController.removeForward(jdwpPort)` if a forward was set up before the failure.
- [x] 19.14b In `androidJavaFactory.ts#spawnFreshAdapter`, after `tmpController.forwardJdwp(pid)` allocates the new JDWP port for a toolbar Restart, also call `setJdwpController(tmpController)` so the registered controller's `activeForwards` reflects the latest forward. Without this the next `onDidTerminateDebugSession` would call `getJdwpController().removeForward(newPort)` against the original attach-time controller, fail the `activeForwards.has(port)` guard, and leak the new forward on the device across every subsequent restart.

### 19d. TS: atomic teardown on terminate + deactivate

- [x] 19.15 In `extension.local.ts#onDidTerminateDebugSession`: read `factory.getJdwpPort()` AS THE PRIMARY SOURCE (the factory tracks the LATEST JDWP port), call `getJdwpController().removeForward(port)` (`.catch(err => log)`), then `factory.killActive()`. **CRITICAL**: read `factory.getJdwpPort()` BEFORE `factory.killActive()` because `killActive` clears the tracking. Fall back to `session.configuration.__jdwpPort` only if the factory is blind (extension reload between sessions). Reading the session config alone is WRONG because VSCode's toolbar Restart clones the FIRST session's configuration for every subsequent restart — `__jdwpPort` in the config stays at the original attach-time port across all restarts; removing that stale port would short-circuit on `removeForward`'s `activeForwards.has(port)` guard and LEAK the actual forward on every restart after the first.
- [x] 19.15b Add `factory.getJdwpPort(): number | undefined` method to `androidJavaFactory.ts` that returns `this.currentJdwpPort`. Document why it's authoritative over the session config.
- [x] 19.16 In `extension.local.ts#deactivate`: call `factory.killActive()` then `getJdwpController()?.cleanupAllForwards()` (fire-and-forget).

### 19e. TS: custom restart command

- [x] 19.17 Create `extension/src/commands/restartSession.ts#runRestartSessionCommand(statusBar, deps)`:
  - Read `vscode.debug.activeDebugSession`; bail with `restartNoActiveSession` warning if absent or wrong type.
  - Read `session.configuration.__pid`; bail with `restartNoPid` warning if absent.
  - `await vscode.debug.stopDebugging(session)` (triggers atomic teardown).
  - `await runAttachToPidCommand(statusBar, deps, pid)` (full re-attach).
- [x] 19.18 In `extension/src/commands/attach.ts`, add `runAttachToPidCommand(statusBar, deps, pid)`:
  - Run `prepareAttachContext(statusBar)` first (re-validates adb/JDK/jar).
  - Construct a new `AdbJdwpController` and register it.
  - Call `attachToProcess({...pick: { pid, name: 'pid <pid>' }, ...})`.
- [x] 19.19 Stamp `__adapterPort`, `__jdwpPort`, `__pid` into the `DebugConfiguration` passed to `vscode.debug.startDebugging`. Without `__pid` the restart command cannot find the target.
- [x] 19.20 In `extension/package.json` `contributes.commands`, register `vscode-android-debugger.restartSession` with title `%commands.restartSession.title%` and icon `$(debug-restart)`.
- [x] 19.21 In `extension/package.json` `contributes.menus.commandPalette`, add an entry for the restart command with `when: "debugType == 'android-java'"` so it only appears when an Android session is active.
- [x] 19.22 In `extension/package.nls.json` and `extension/package.nls.en.json`, add `commands.restartSession.title` translation.
- [x] 19.23 In `extension/src/extension.local.ts`, register the command: `vscode.commands.registerCommand('vscode-android-debugger.restartSession', () => runRestartSessionCommand(statusBar, {...}))`.
- [x] 19.24 In `extension/src/extension.local.ts#onDidStartDebugSession`, add a code comment explaining why `factory.clearPort()` is NOT called here (the JVM outlives the session and should be reused for restart).

### 19f. Verification

- [ ] 19.25 Manual test: normal attach → stop → verify adb forward list has no entry for the session's port (`adb forward --list`) and no `java -jar adapter.jar` process running. **Blocked on real device.**
- [ ] 19.26 Manual test: VSCode-native Restart while an Android session is active → verify it fails (ECONNREFUSED) and the user is told to use the custom command. **Blocked on real device.**
- [ ] 19.27 Manual test: Android: Restart Debug Session while an Android session is active → verify a brand-new JVM pid, brand-new DAP port, brand-new JDWP port, all `__adapterPort`/`__jdwpPort`/`__pid` are freshly allocated in the new session's config. **Blocked on real device.**
- [ ] 19.28 Manual test: force-quit VSCode while a session is active → verify no leaked JVM (`ps aux | grep java`) and no leaked forward (`adb forward --list` after adb kill-server). **Blocked on real device.**

## 20. Configurable Binary Paths and Discovery

User-configurable `adbPath` and `javaPath` with strict semantics (configured-but-missing throws). Auto-detection ladders that prefer running processes, fall back to common install paths, then `$PATH`, then throw a clear error.

### 20a. Config keys

- [x] 20.1 In `extension/src/util/config.ts`, add `javaPath: string` to `ExtensionConfig` and `cfg.get<string>('javaPath', 'auto')`.
- [x] 20.2 In `extension/package.json` `contributes.configuration.properties`, declare `vscode-android-debugger.javaPath` with `default: "auto"` and description `%config.javaPath.description%`.
- [x] 20.3 In `extension/package.nls.json` and `extension/package.nls.en.json`, add `config.javaPath.description` translation.

### 20b. adb resolution ladder

- [x] 20.4 Rewrite `extension/src/adb/path.ts#findAdb(configuredPath)`:
  - If configured and exists → `{adbPath, source: 'config'}`.
  - If configured but missing → throw `errAdbConfiguredMissing(path)`. No fallback.
  - Otherwise call `findAdbAuto()`.
- [x] 20.5 Implement `findAdbAuto()`: `findRunningAdb()` → per-platform `COMMON_ADB_PATHS[platform]` → `findAdbInPath()` → throw `errAdbNotFound`.
- [x] 20.6 Implement `findRunningAdb()`: Linux walks `/proc/<pid>/cmdline` (`argv0 basename === 'adb' || last === 'adb'`), resolves `/proc/<pid>/exe` via `realpathSync`; Windows spawns `powershell -NoProfile -NonInteractive -Command "Get-Process adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Path"` via `execFileSync(timeout: 5000, windowsHide: true)` and parses output; macOS returns null.
- [x] 20.7 Implement `findAdbInPath()`: splits `$PATH` (on `:` Unix / `;` Windows), checks each dir for `adb` / `adb.exe` via `fs.statSync().isFile()`.
- [x] 20.8 Implement `isExecutableFile(p) = fs.statSync(p).isFile()` (note: Node has no portable X-bit check; regular file is the best proxy).
- [x] 20.9 Reduce `AdbPathResult.source` to `'config' | 'auto'`. Remove old `'running' | 'common' | 'PATH' | 'fallback'` variants.
- [x] 20.10 Add i18n keys: `errAdbConfiguredMissing`, `errAdbNotFound`, `logAdbConfigured`, `logAdbConfiguredMissing`, `logAdbRunningFound`, `logAdbAutoFound`, `logAdbFoundInPath`. Sync to both `bundle.l10n.zh-cn.json` and `bundle.l10n.en.json`.
- [x] 20.11 Wire `extension.local.ts#activate` and `commands/attach.ts#prepareAttachContext` to read `getConfig().adbPath`.

### 20c. Java resolution

- [x] 20.12 Rewrite `extension/src/util/jdk.ts#detectJdk(configuredPath)`:
  - `resolveJavaCandidates(configuredPath)`: configured (throw on missing) → `$JAVA_HOME/bin/java` (if `existsSync`) → `findJavaInPath()`.
  - For each candidate, `runJavaVersion(javaPath)` (spawn `java -version`, parse `version "X..."` from stderr). Return first with version ≥ 8.
- [x] 20.13 Remove the old `enumeratePlatformJavaPaths` (which listed `/Library/Java/JavaVirtualMachines`, `C:\Program Files\Eclipse Adoptium\jdk-*`, etc.). JAVA_HOME + PATH only.
- [x] 20.14 `findJavaInPath()`: same shape as `findAdbInPath`, looking for `java` / `java.exe`.
- [x] 20.15 `runJavaVersion(javaPath)`: spawn, capture stderr, regex `version "(\d+)..."`, return `{javaPath, version: parseInt(m[1])}`. Return null on non-zero exit.
- [x] 20.16 Add i18n keys: `errJavaConfiguredMissing`, `logJavaConfigured`, `logJavaFromJavaHome`, `logJavaFoundInPath`. Sync both l10n files.
- [x] 20.17 Wire `extension.local.ts#activate` and `commands/attach.ts#prepareAttachContext` to read `getConfig().javaPath`.

### 20d. Attach pre-flight (static checks at front)

- [x] 20.18 In `extension/src/commands/attach.ts`, extract `prepareAttachContext(statusBar)` as a separate async function that runs `findAdb(config.adbPath)`, `detectJdk(config.javaPath)`, `AdapterLauncher.resolveAdapterJar(extensionPath)`. Returns `{jdk, jarPath, adbResult, runner}`.
- [x] 20.19 In `runAttachCommand` and `runAttachToPidCommand`, call `prepareAttachContext` BEFORE any device interaction (`new AdbJdwpController`, `listDevices`, `listJdwpProcesses`, `pickProcess`).
- [x] 20.20 On `detectJdk` returning null, throw `getJdkErrorMessage()` from `prepareAttachContext` (no need to even instantiate `AdbJdwpController`).

### 20e. Verification

- [ ] 20.21 Manual test: with `vscode-android-debugger.adbPath` set to a real path, verify attach uses that path (log line: `adb path (configured): <path>`). **Blocked on real device.**
- [ ] 20.22 Manual test: with `vscode-android-debugger.adbPath` set to a non-existent path, verify the attach command fails with `errAdbConfiguredMissing` BEFORE `adb devices` runs. **Blocked on real device.**
- [ ] 20.23 Manual test: with `adbPath` default and no adb in `PATH`, no common-paths hit, no running adb → verify the attach command fails with `errAdbNotFound` BEFORE any device interaction. **Blocked on real device.**
- [ ] 20.24 Manual test: with `vscode-android-debugger.javaPath` set to a real path with version < 8 → verify `detectJdk` rejects it and returns null, surfacing the JDK error. **Blocked on real JDK < 8 install.**

## 21. Log Hygiene and Temp File Hygiene

Truncate verbose adb output before logging; use a single unified temp dir for index files with periodic stale-file sweeping; clean up temp files on early failure paths.

### 21a. adb log truncation

- [x] 21.1 In `extension/src/adb/runner.ts`, add `STDOUT_LOG_MAX = 200` and `STDERR_LOG_MAX = 500` constants.
- [x] 21.2 Add `truncateForLog(text, max)`: if `text.length <= max` return as-is; otherwise return `${text.slice(0, max)}... [+${text.length - max} chars]`.
- [x] 21.3 Apply `truncateForLog` to `stdout.trim()` (with `STDOUT_LOG_MAX`) and `stderr.trim()` (with `STDERR_LOG_MAX`) in the `proc.on('exit')` log. Truncation is for logging only — `AdbResult` still carries the full strings.

### 21b. Unified index temp dir

- [x] 21.4 Create `extension/src/util/indexFile.ts` with `INDEX_TMP_DIR = path.join(os.tmpdir(), 'd4a-index')`.
- [x] 21.5 Implement `writeIndexFile(entries)`:
  - `fs.mkdirSync(INDEX_TMP_DIR, { recursive: true })`.
  - Filename: `index-${process.pid}-${Date.now()}-${random6}.json` where `random6 = Math.random().toString(36).slice(2, 8)`.
  - `fs.writeFileSync(filePath, JSON.stringify(entries), 'utf-8')`.
  - Return `filePath`.
- [x] 21.6 Implement `sweepStaleIndexFiles(maxAgeMs = 24 * 60 * 60 * 1000)`:
  - `fs.readdirSync(INDEX_TMP_DIR)` (catch missing-dir → no-op).
  - For each entry: `fs.statSync` → if `isFile && now - mtimeMs > maxAgeMs` then `fs.unlinkSync`. Catch per-file errors silently.
- [x] 21.7 In `extension/src/extension.local.ts#activate`, call `sweepStaleIndexFiles()` (non-blocking best-effort).
- [x] 21.8 In `extension/src/commands/attach.ts#cleanup`, call `fs.promises.unlink(indexFile).catch(() => {})` if `indexFile` was set. Avoids leaking the temp file when attach fails between `writeIndexFile` and JVM start.
- [x] 21.9 In `adapter/src/main/kotlin/.../Main.kt`, ensure the adapter deletes the index file in `loadInitialIndex`'s `finally` block (this was already implemented; verified).

### 21c. Verification

- [ ] 21.10 Manual test: run `adb shell ps -A -o PID,NAME` via the extension (e.g. via a hypothetical "list processes" command) and verify the Output channel shows `... [+<N> chars]` instead of dumping all rows. **Blocked on real device.**
- [ ] 21.11 Manual test: write a fake stale index file in `${TMPDIR}/d4a-index/` with mtime > 24h ago, then activate the extension, and verify the file is deleted. **Manual — easy to test.**
- [ ] 21.12 Manual test: kill the extension mid-attach (after `writeIndexFile` but before JVM start), then verify no leftover `index-<pid>-<ts>-<rand>.json` files in `${TMPDIR}/d4a-index/`. **Manual — easy to test.**

## 16. Final Integration and Polish

- [x] 16.1 Document supported scenarios in README with examples
- [x] 16.2 Write troubleshooting section covering common Remote-SSH failures
- [ ] 16.3 Manual test plan against a real Android device (local scenario) **Blocked on real device**
- [ ] 16.4 Manual test plan against a real Android device (Remote-SSH scenario) **Blocked on real device**
- [ ] 16.5 Test against Lambda-bearing code (e.g., `Runnable` in framework) **Blocked on real device**
- [ ] 16.6 Test against large AOSP source (frameworks/base, 30000+ .java files) **Blocked on real AOSP checkout**
- [ ] 16.7 Verify JDK 8 compatibility (build and run on JDK 8 if available) **Blocked on JDK 8 availability**
- [ ] 16.8 Package release: extension `.vsix`, Java fat-jar, README, LICENSE **Blocked on signing/registry**
