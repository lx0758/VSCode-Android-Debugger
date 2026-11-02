# Android Java Debugger — Design

## Context

This is a greenfield project. The proposal establishes why we are building this and what it should do. This document explains how the implementation is shaped, focusing on architectural decisions, key trade-offs, and the rationale behind them.

The core problem: VSCode needs to debug Android framework / system app Java code via JDWP attach. The user is an AOSP developer who:
- Has a `userdebug`/`eng` device with root, on their own machine
- Has full AOSP source in their workspace (not decompiled)
- May work in a Remote-SSH setup where source lives on a remote Linux server while the device and adb live on the local machine

Two main runtime constraints drive the architecture:

1. **VSCode speaks DAP; Android VMs speak JDWP.** The adapter process is the bridge.
2. **In Remote-SSH, the TS extension runs on the remote machine** (where the workspace is mounted by VSCode Remote), but the device and adb are on the local machine. adb commands and JDWP socket access must cross the SSH boundary.

## Goals / Non-Goals

**Goals:**
- Implement a working debug adapter (TS extension + Java process) that satisfies the spec requirements
- Make the Remote-SSH scenario (C1) feel as natural as the local scenario after initial setup
- Keep the Java adapter small enough that a single developer can maintain it (~1500-2000 lines of Java)
- Use only JDK built-ins (`com.sun.jdi.*`) on the Java side, with `gson` for JSON — no Eclipse OSGi, no Java-debug dependency

**Non-Goals:**
- Detailed implementation of every utility function (covered by tasks.md instead)
- Performance optimization beyond "responsive" (sub-second response on local, sub-2-second on Remote-SSH)
- Cross-platform install automation (we provide scripts and docs; users run them)

## Decisions

### Decision 1: Java adapter, not Go

**Choice:** Implement the debug adapter as a Java process that uses `com.sun.jdi.*` to talk to JDWP.

**Rationale:**
- JDI provides a complete, tested implementation of JDWP client functionality: virtual machine attach, class/method/field resolution, line tables, breakpoints, step, variable access, and expression evaluation
- For log breakpoints with `{this.field.method()}` placeholders, JDI's `ObjectReference.invokeMethod()` handles the work natively
- All JDI APIs we need are present in JDK 1.3+ (so JDK 8 baseline is fine), avoiding JDK 9+ `--add-opens` requirements
- The user is an AOSP developer and already has a JDK; no JRE dependency is added beyond what they have

**Alternatives considered:**
- *Go adapter with custom JDWP*: would avoid JRE dependency, but require implementing ~3000-5000 lines including line table parsing, class resolution, and a mini expression evaluator. Expression evaluation would be limited. Cost: roughly double the implementation effort.
- *Reuse `microsoft/java-debug`*: appears attractive (it supports attach mode and is what `vscode-java-debug` uses), but it is built as an Eclipse OSGi plugin with heavy dependency footprint (`org.eclipse.jdt.*`, `org.osgi.*`, `lsp4j`). Using it standalone would require either running Equinox OSGi (heavy, complex debugging) or extracting classes (license/dependency issues). The minimal standalone path is cleaner.

### Decision 2: JDK 8 as minimum runtime

**Choice:** Target Java 8 source/target compatibility; require JDK 8+ to run the adapter.

**Rationale:**
- All JDI APIs we use (`VirtualMachine`, `EventRequestManager`, `StackFrame.visibleVariables`, `ObjectReference.invokeMethod`) have been available since JDK 1.3-1.4
- Avoiding JDK 9+ module system means no `--add-opens` ceremony at startup
- Java 8 syntax (no records, no `var`) keeps the adapter readable for AOSP developers

**Alternatives considered:**
- *JDK 11*: gains some niceties but not needed; would also require `--add-opens` for some JDI internals
- *JDK 17*: latest LTS, but the user would need to install it; no JDI feature we need requires it

### Decision 3: In-memory source index, no persistence

**Choice:** SourceIndex lives in the Java adapter's memory only; rebuilt on each session start.

**Rationale:**
- AOSP single-folder index build time is ~10 seconds when scoped to marked roots — acceptable
- Persistence adds invalidation complexity (file changes between sessions)
- Simpler implementation: no cache format to design, no staleness bugs to debug

**Alternatives considered:**
- *Persistent JSON cache*: would speed up repeat sessions, but adds invalidation logic for file edits / git pulls / branch switches. Marginal benefit (10s is fine).
- *Compile-time index*: not feasible for AOSP users who don't always build the source.

### Decision 4: Per-workspace Memento storage for source roots (revised)

**Choice:** Source dir configuration is stored in `ExtensionContext.workspaceState` (VSCode Memento, per-workspace SQLite) as a JSON array of absolute paths. The key is `vscode-android-debugger.sourceDirs`.

**Rationale:**
- Memento is automatically scoped by VSCode to the workspace identity (single-folder workspace, multi-folder `.code-workspace`, or Remote-SSH workspace each get a distinct SQLite database). There is no ambiguity about which file holds the data, no path-format normalization needed, and no migration when the user switches between single-folder and multi-folder modes.
- Paths are stored as absolute strings, so the indexer doesn't need to know about workspace folder prefixes or `asRelativePath` rules.
- Source dirs are inherently per-developer state (each engineer points at the parts of AOSP they care about). They do not belong in shared git-tracked config. The previous `.vscode/settings.json` approach forced them into the workspace's version-controlled settings, which mixed personal debug preferences with project-wide configuration.
- Memento persists across VSCode restarts and survives workspace reopens. It is cleared only by explicit user action (`Workspaces: Clear Workspace Storage...`).

**Migration from previous settings-based storage:**
On the first call to `getAllDirs()`, if Memento is empty AND a legacy `vscode-android-debugger.sourceDirs` exists in `.vscode/settings.json` (single-folder) or in `.code-workspace` (multi-folder), the manager resolves each relative entry to an absolute path, deduplicates, and writes the result to Memento. The legacy settings entry is left untouched so the user can verify the migration before removing it manually. Migration is idempotent — once Memento has entries, settings are not consulted again.

**Alternatives considered:**
- *Per-folder `.vscode/settings.json`*: forces `ConfigurationTarget.WorkspaceFolder` vs `Workspace` selection based on single/multi-folder mode, requires path-format normalization, and leaves orphan data in the wrong file when switching modes. Rejected after first round of user feedback.
- *Workspace-level `.code-workspace` settings only*: forces users into workspace mode; rejects single-folder opens. Rejected.
- *Single global config*: doesn't scale to AOSP's multi-module structure and doesn't isolate per-workspace.
- *Memento `globalState` instead of `workspaceState`*: shared across all workspaces, which is the wrong scope. Rejected.
- *Adding migration UI (notification + button)*: complicates first-run UX for a debug-only config. Manual cleanup of legacy settings is acceptable.

### Decision 5: Explicit source root marking with auto-include for breakpoint files

**Choice:** User explicitly marks folders as source roots; any file with an active breakpoint is auto-included in the index regardless of whether it's under a marked root.

**Rationale:**
- AOSP workspace is too large to scan fully
- Marking gives users control over scope
- Auto-include for breakpoint files means users don't have to mark before setting a breakpoint — the common case (debug one file) just works

**Alternatives considered:**
- *Scan everything on session start*: too slow for full AOSP (50+ seconds), wasteful
- *Auto-detect via `Android.bp` / `Android.mk`*: too magical, hard to debug, and may not match user's debugging intent

### Decision 6: Dual-loaded extension with remote TS SourceIndexer (v4-alt)

**Choice:** Extension declares `extensionKind: ["ui", "workspace"]`. In Remote-SSH, the extension is loaded on **both** VSCode extension hosts, with strict role separation:

- **Local ext host (`kind=ui`)**: hosts all user UI (commands, QuickPick, status bar), runs `adb` as a local child process, spawns the Java Adapter locally, and coordinates indexing by calling the remote ext's `buildIndex()` API.
- **Remote ext host (`kind=workspace`)**: exposes **only one function** via `activate()` return value: `buildIndex(sourceDirs: string[]): Promise<FqnEntry[]>`. This function walks the workspace using local `vscode.workspace.findFiles` + `fs.readFile` (which is a native local FS read on the remote side, ~microseconds per file), parses `package` + class declarations in TS, and returns the `FQN → file path` entries.
- **Cross-host IPC**: local ext calls `vscode.extensions.getExtension('publisher.vscode-android-debugger').exports.buildIndex(sourceDirs)`. VSCode proxies this call across the SSH tunnel automatically.
- **Fallback**: if the remote ext fails to load (network issue, activation failure), local ext catches and falls back to its own cross-host `vscode.workspace.fs.readFile` (slow path, identical to v4 behavior).
- **Local-only adb + Java Adapter**: `adb forward tcp:LP jdwp:<pid>` and Java Adapter both run on local; Java Adapter connects to `127.0.0.1:<LP>` directly. Zero tunnels for JDWP.

```
+--- Local (laptop, any OS) ---+                    +--- Remote (Linux) ---+
| Local ext host (kind=ui)     |                    | Remote ext host      |
|  - commands / UI             |   .exports.        |  (kind=workspace)    |
|  - adb child_process         |   buildIndex()     |                      |
|  - Java Adapter child        | <----------------> |  buildIndex():       |
|  - calls remote.buildIndex() |  (VSCode built-in  |   - findFiles (fast) |
| Android Device (USB)         |   cross-host IPC)  |   - fs.readFile      |
| adb forward tcp:LP jdwp:<PID>|                    |     (native, fast)   |
+-----------------------------+                    |   - parse class FQN  |
                                                    |   - return entries   |
                                                    +----------------------+
```

**Rationale:**
- **10-100x faster indexing than v4.** Walking files on the remote host uses native FS reads (~10-100 microseconds per file), versus 5-50ms cross-host round-trips per file in v4. A 10k-file scope goes from 50-500 seconds to 5-10 seconds; a full 30k AOSP walk goes from "must be rejected" (5-25 minutes) to "usable" (15-30 seconds).
- **The remote ext is genuinely tiny.** ~50 lines of TS: one exported function, one findFiles loop, one regex parse, one return. No DAP, no Java, no ssh, no long-lived state. It can be removed or replaced without affecting anything else.
- **Cross-host IPC via `extensions.getExtension().exports` is the official VSCode pattern.** Microsoft uses it in `vscode-mssql`, `vscode-remote-containers`, `vscode-python`, etc. The IPC is type-safe at the boundary (TS types), doesn't need a custom protocol, and doesn't need any TCP port allocation.
- **Local ext can do everything except efficient indexing.** UI, adb, Java Adapter, DAP — all local. The remote ext adds exactly one capability (fast FS walk) and nothing else.
- **Fallback preserves v4 robustness.** If the remote ext fails to activate (e.g., the user opens a `ssh-remote` workspace but the extension hasn't been installed on the remote side, or activation throws), the local ext transparently switches to its own cross-host read path. The user sees a longer indexing time, not a broken session.
- **No ssh -R, no remote JDK, no cross-host DAP.** Java Adapter is local; JDWP socket is `127.0.0.1:<LP>` on local; only the buildIndex result crosses the SSH tunnel, and that's via VSCode's IPC.

**Alternatives considered (full history, all rejected):**
- *v1: Remote ext SSH-execs to local adb*: requires SSH server on local (off by default on Windows) + working private-key parse. Broken on user's setup.
- *v2: Local Agent + 9801 JSON-RPC*: user maintained `ssh -R 9801:...` manually; custom protocol with framing/errors/retries when VSCode already provides cross-host IPC for files and DAP.
- *v3: Dual-loaded ext + auto ssh -R*: removed manual step but still needed per-session ssh -R, remote JDK, cross-host DAP routing, `~/.ssh/config` parsing.
- *v4: All-local + TS SourceIndexer with cross-host read*: works, but indexing is slow (5-50ms per file across SSH tunnel). User-perceptible delay for any non-trivial scope.
- *v4-alt-rejected: Local Adapter + sshfs*: removes tunnel but adds sshfs mount dependency; Windows-local users can't reliably run sshfs (WinFsp fragility).
- *v4-alt-rejected: Remote Adapter with TS SourceIndexer*: would put JDK only on remote but reintroduces cross-host DAP routing AND needs remote JDK. Worse complexity trade-off.

**Performance comparison:**

| Scope | v4 (cross-host read) | v4-alt (remote ext local FS) |
|-------|----------------------|------------------------------|
| 1 module dir (~3k files) | 30-150s | 2-3s |
| 2-3 module dirs (~5-10k files) | 25-500s | 3-10s |
| Whole `frameworks/base` (~10k files) | 100-500s ⚠️ | 5-10s |
| Whole AOSP (~30k files) | 5-25 min, must reject | 15-30s, acceptable |

Mitigations retained from v4: progress notification with cancel, pre-flight file count check, `index.maxFiles` config (default 10000, can be raised since v4-alt is fast).

### Decision 7: Regex-based Java source parsing for source index

**Choice:** Parse `.java` files with simple regex patterns to extract `package` declaration and `class` declarations (including inner classes by matching indented `class`/`enum`/`interface`).

**Rationale:**
- AOSP source is syntactically regular; complex parser not needed
- We only need class FQN, not full AST
- ~200 lines of Go-style parsing in Java; no dependency on a full Java parser

**Alternatives considered:**
- *Embed a Java parser (Eclipse JDT, JavaParser)*: heavy, and we'd be using a small fraction of its capability
- *Read class file from bytecode*: doesn't help — we need source FQN to map back to file

### Decision 8: Expression evaluation limited to MVP-safe subset

**Choice:** MVP supports only `{name}`, `{this.field}`, `{this.field.field2}`, `{this.method()}`, `{this.field.method()}`, `{arr[i]}`, `{literal}`. No operators, no ternary, no casts, no method args.

**Rationale:**
- JDI's `evaluate()` is unreliable for arbitrary expressions on ART (ART's JDWP doesn't fully implement expression evaluation)
- Simple field/method access works reliably through JDI's `getValue()` and `invokeMethod()`
- Most log breakpoint needs are "log this value" — covered by the MVP subset

**Alternatives considered:**
- *Embed Janino to compile expressions*: requires JRE, adds 2MB jar, complexity. Defer to V2.
- *Embed JDI's full expression evaluator*: same JRE dependency, same complexity. Defer to V2.

### Decision 9: SourceIndexer logic lives in TypeScript, executed on remote ext in C1 scenario (v4-alt)

**Choice:** The class-name parser and `addDir` / `addFile` logic move from the Kotlin adapter (`SourceIndexer.kt`) into a TypeScript module (`extension/src/source/indexer.ts`). In scenario A (local), this module runs in the local ext host. In scenario C1 (Remote-SSH), the **same module runs in the remote ext host** — the local ext just calls it via `extensions.getExtension().exports.buildIndex()`.

**Rationale:**
- Java Adapter doesn't need file content for anything; it only needs the `FQN ↔ path` map. Pushing that map from TS (which has cross-host file access via VSCode API) is cleaner than having Kotlin walk a remote filesystem.
- The TS indexer module is the same code in both scenarios. Local scenario: local ext runs it directly with local FS access. Remote scenario: remote ext runs it with native FS access (10-100x faster than cross-host reads from local).
- TS-side indexer can show progress natively in the VSCode status bar / progress notification. The Kotlin side would have to send progress events back over DAP for the same UX.
- V3 architecture (Kotlin indexer on remote) required the Kotlin process to be on the remote machine to read remote files efficiently. V4-alt breaks that requirement by moving indexing to TS on the remote ext host.

**Alternatives considered:**
- *Kotlin indexer with VSCode fs API emulation via TCP RPC*: introduces a new protocol and process; worse than v4-alt.
- *Kotlin indexer reading local FS (works only if user has AOSP locally too)*: not a general solution; only works in scenario A.

### Decision 10: Dual-loaded extension via `extensionKind: ["ui", "workspace"]` (v4-alt)

**Choice:** Extension declares `extensionKind: ["ui", "workspace"]` in `package.json`. Both halves activate in Remote-SSH. The local half (`kind=ui`) handles all UI + adb + Java Adapter. The remote half (`kind=workspace`) only exposes `buildIndex()` via `activate()` return value.

**Rationale:**
- The local half needs to be on the user's machine for UI access (commands in palette, QuickPicks, status bar).
- The remote half has direct access to the workspace filesystem — no cross-host latency for file reads.
- Cross-host IPC via `vscode.extensions.getExtension('publisher.vscode-android-debugger').exports` is the official VSCode pattern for dual extensions and works through the SSH tunnel automatically.
- The remote half is genuinely small (~50 lines): one exported function, one findFiles call, one readFile loop, one regex parse, one return. No long-lived state, no DAP, no Java spawning, no ssh.
- If either half fails to activate (network issue, host version mismatch), the other half continues to work — local half falls back to its own cross-host read (v4 behavior).

**Alternatives considered (rejected):**
- *Single-side extension (v4, no extensionKind)*: works but indexing is slow for large remote workspaces.
- *Two separate extensions*: doubles packaging/release burden; user installs two things. Rejected.
- *Extension with only `kind=ui` (current v4 default)*: same as single-side, slow indexing.
- *Extension with only `kind=workspace`*: would put UI on remote side, breaking local QuickPick rendering. Rejected.

### Decision 11: Cross-host IPC via `vscode.extensions.getExtension().exports` (v4-alt)

**Choice:** The remote ext's `activate()` returns an object exposing `buildIndex(sourceDirs: string[])`. The local ext retrieves this via:
```typescript
const ext = vscode.extensions.getExtension('publisher.vscode-android-debugger');
if (!ext) throw new Error('Remote ext not loaded');
await ext.activate(); // ensures activation
const entries = await ext.exports.buildIndex(sourceDirs);
```

**Rationale:**
- This is the official VSCode API for cross-extension communication; when the two extensions live on different hosts (one local, one remote), VSCode transparently proxies the call through the Server↔Client tunnel.
- Type-safe at the boundary: both halves are TS, so a shared `types.ts` module defines `FqnEntry`, `BuildIndexRequest`, `BuildIndexResult` etc.
- No TCP port allocation, no JSON framing, no error protocol design — just a function call that happens to cross a host boundary.
- Built into VSCode; doesn't depend on internal APIs that could break.
- The `activate()` call is idempotent and safe to call multiple times; VSCode returns the same exports object.

**Alternatives considered:**
- *Custom JSON-RPC over a TCP socket on loopback*: introduces a port and a protocol. Rejected (this was v2).
- *VSCode's built-in command system (`executeCommand`)*: works across hosts but isn't type-safe; command IDs are strings.
- *VSCode webview messaging*: designed for UI webviews, not for ext-to-ext communication.
- *File-based IPC (write to a file, watch for response)*: fragile, slow, polling-based.

### Decision 12: Incremental source index updates during active debug session (v4-alt)

**Choice:** After the initial `sourceDirs/set` push during attach, the extension keeps the Java Adapter's `SourceIndex` in sync with the user's workspace state via two new DAP custom requests:
- `sourceDirs/set` (existing) — replace the entire index. Used on attach and on `sourceDirs` config change.
- `sourceDirs/add` (new) — merge entries into the existing index. Used for auto-include on breakpoint change.

Triggers:
1. **`sourceDirs` config change mid-session** → re-build full index from all marked dirs → `sourceDirs/set`. Simpler than computing diff; AOSP re-build is 15-30s (acceptable one-time cost) and smaller projects are sub-second.
2. **New breakpoint in unindexed `.java`/`.kt` file** → read just that file, parse class declarations in TS, send entries via `sourceDirs/add`. Implements Decision 5 ("auto-include for breakpoint files") at the TS layer (not the adapter, which no longer parses).
3. **Auto-excluded on user unmarking a source dir** — implicit in (1)'s full replace; no separate `remove` request needed.
4. **All breakpoints removed in auto-included file** — no auto-exclude; the entry stays in the adapter's index for the session. Memory cost is negligible (few KB per file) and removing on each breakpoint toggle would cause flapping.

**Why not full re-build on every breakpoint change?**
- For AOSP, full re-build = 15-30s on every new breakpoint in a new file. Unusable.
- Single-file parse = <100ms per file. Practical.
- Same rationale as the original v4-alt performance comparison.

**Rationale:**
- Without incremental updates, any session-spanning change (mark new dir mid-session, set breakpoint in new file) silently breaks debugging (all subsequent breakpoints get `verified: false`).
- `SourceIndex.add()` is already merge-safe (it just upserts `fqnToFile` and appends to `fileToFqns`).
- Single-file parsing reuses the existing `parseJavaFile` / `parseKotlinFile` regex extractors — no new logic.

**Alternatives considered:**
- *Compute diff (added dirs / removed dirs) on config change, send incremental add/remove*: more code, marginal benefit for AOSP-scale projects (15-30s full re-build is annoying but tolerable).
- *Auto-exclude when last breakpoint removed in a file*: causes flapping (user toggles breakpoint, entry re-adds on next add), complicates watcher logic, saves negligible memory.
- *Always re-build whole index on every change*: simplest, but unacceptable latency for large projects on every breakpoint set.

### Decision 13: Anonymous inner class breakpoint resolution

**Choice:** The adapter resolves line locations through a three-step lookup that handles both named inner classes and anonymous inner classes (including synthetic lambdas). All locations are source-name-verified before being accepted.

**Algorithm (`BreakpointManager.findLocationsForLine`):**

1. **Direct FQN lookup** — for each FQN the extension pushed via `sourceDirs/set` / `sourceDirs/add`, call `vm.classesByName(fqn)`. If any returned `ReferenceType` has a method whose line table covers the requested line AND the resulting `Location.sourceName()` matches the breakpoint file's basename, accept it.
2. **Hint class** — if a `ReferenceType` was passed in (e.g. from a `ClassPrepareEvent`), do an O(1) check against that single type. No `vm.allClasses()` walk.
3. **Broad walk** — only if steps 1 and 2 yield nothing AND the enclosing class is already loaded: iterate `vm.allClasses()` filtered by `${fqn}$*` prefix (catches `Outer$1`, `Outer$Inner`, `Outer$$Lambda$1`, etc.), and accept the first type whose line table covers the line. Skip entirely when the enclosing class is not yet loaded — no point searching for an anonymous inner if the outer isn't around.

Each accepted `Location` is checked for `sourceName()` matching the breakpoint file's basename (e.g. `MainActivity.java`). This filters out inner classes from OTHER source files that happen to live in the same package.

**Why the VM query is required:**
javac / kotlinc assign `$N` suffixes by **bytecode emission order**, not source order. Scanning a source file cannot reliably predict the runtime class name — e.g. a class with two anonymous `Runnable`s and one anonymous comparator produces `$1`, `$2`, `$3` depending on which closure's bytecode is emitted first, which depends on the compiler's optimization passes. The only canonical source of truth is the JVM's own class table via `vm.allClasses()` or `vm.classesByName(prefix)`.

**Why `vm.allClasses()` is rarely invoked:**
The broad walk is gated by three conditions, all of which must be true:
- Step 1 (direct FQN lookup) returned nothing.
- No `hintType` was supplied (i.e. this is not a class-prepare retry).
- The enclosing class is already loaded in the VM.

In practice, the common path (step 1) handles everything when the index is fresh. The broad walk only fires for genuinely anonymous inner classes of indexed outer classes — usually 0-3 per session.

**`ClassPrepareEvent` for late resolution:**
- `BreakpointManager.init` calls `erm.createClassPrepareRequest().enable()` once at attach time (no source name filter).
- `EventDispatcher` recognizes `ClassPrepareEvent` and emits a `"class-prepare"` tag.
- `DapServer.handleJdiEvent("class-prepare", ...)` calls `set.resume()` first (default policy is `SUSPEND_ALL`; the target VM must not stop on class prepare), then calls `tryDrainForClass(className, loadedType)` to retry placement of pending BPs whose FQNs match the new class or whose enclosing class matches.
- `tryDrainForClass` passes the loaded `ReferenceType` as a hint so the retry hits step 2 (O(1)) and skips the broad walk entirely.

**`SourceIndex.resolveByFqn` dollar fallback:**
The index only knows about top-level / named inner classes (the TS parser extracts `class`, `enum`, `interface`, Kotlin `object`/`class`). When a runtime stack frame points to `Outer$1`, the adapter strips at the first `$` and looks up `Outer` so VSCode can highlight the correct source line. This handles the most common case (anonymous inner class inside a non-anonymous outer) without requiring the parser to enumerate every anonymous class.

**Performance:**
- The `findLocationsForLine` broad walk is early-broken on first match.
- All `Logging.debug` calls early-return when debug logging is disabled (set via `DEBUGGER_ANDROID_DEBUG=1` env var or `Logging.setDebugEnabled(true)`); a `debug { ... }` lambda form avoids string concatenation entirely.
- The class-prepare drain only retries BPs whose FQN namespace matches the loaded class, not the entire pending list.

**Rationale:**
- Without step 3 (broad walk), breakpoints inside `new Runnable() { ... }` blocks fail with "no location found" — the enclosing class's `allMethods()` does not include the anonymous class.
- Without the dollar fallback in `SourceIndex.resolveByFqn`, the `stopped` event's `source.path` is null for anonymous inner classes, and VSCode cannot highlight the line.
- Without `ClassPrepareEvent`, breakpoints on classes that load after attach (e.g. `Application.onCreate`-instantiated classes) never become verified.
- Without the source-name verification, inner classes from one source file could match a breakpoint in a different source file with the same line number.

**Alternatives considered:**
- *Predict `$N` from source*: rejected — compiler ordering is non-deterministic across compiler versions, optimizations, and Kotlin vs javac.
- *Always walk `vm.allClasses()`*: rejected — would be O(thousands) on every `setBreakpoints`, with no early-break in older designs.
- *Disable breakpoints in anonymous inner classes*: rejected — common AOSP pattern (runnables, comparators, listeners) is exactly what users want to debug.
- *Pre-walk `vm.allClasses()` at attach and cache ReferenceTypes*: rejected — class set changes as the VM loads more classes; the cache would need refresh logic and would not handle classes loaded later.

### Decision 14: Atomic session teardown — JVM dies AND adb forward is removed on every terminate; restart rebuilds both via the factory

**Choice:** Every debug session ends with **both** the adapter JVM killed AND the `adb forward tcp:<jdwpPort> jdwp:<pid>` for the session's JDWP port removed. Forward removal happens in `vscode.debug.onDidTerminateDebugSession`:

```typescript
// extension.local.ts#onDidTerminateDebugSession
const port = (session.configuration as Record<string, unknown>).__jdwpPort;
const jdwp = getJdwpController();
if (typeof port === 'number' && jdwp) {
  jdwp.removeForward(port).catch((err) => { /* log */ });
}
factory.killActive();   // clears currentJdwpPort tracking as well
```

`factory.killActive()` calls `proc.kill()` on the tracked JVM `ChildProcess` and clears `currentJdwpPort` (so the factory no longer believes any forward is alive at that port).

**Toolbar Restart flow (the one that used to ECONNREFUSED):**
1. VSCode ends the current session → `onDidTerminate` fires → forward is removed, JVM dies, factory's `currentJdwpPort` is cleared.
2. VSCode starts a brand-new session with a COPY of the original configuration — including the OLD `__jdwpPort` (which no longer has a forward) and the OLD `__adapterPort` (whose JVM was just killed).
3. VSCode calls `factory.createDebugAdapterDescriptor(session)`. The tracked JVM is dead → `spawnFreshAdapter()` runs.
4. `spawnFreshAdapter`:
   - Allocates a **new** ephemeral JDWP port via `new AdbJdwpController(runner).forwardJdwp(pid)` (the runner + pid come from `setRestartContext`, captured at the previous attach).
   - Allocates a **new** ephemeral DAP port via `allocateFreePort()`.
   - Spawns a fresh JVM with `--port=<newDap> --jdwp-port=<newJdwp> --index-file=<file>`.
   - The JVM waits for READY (stderr line `READY port=...`) before the factory returns the descriptor.
5. Factory returns `DebugAdapterServer(newDap)` to VSCode. VSCode connects to the new DAP port. JVM accepts.
6. VSCode fires its automatic `setBreakpoints` (no JVM-side change — adapter returns `verified=false` for BPs because `bpManager` doesn't exist yet, exactly like a normal attach).
7. VSCode fires `attach`. The DAP request carries the OLD `__jdwpPort` in its `port` field. But `DapServer.handleAttach` reads `args["port"]` AND falls back to the `jdwpPortOverride` constructor parameter (the `--jdwp-port` value). Since the override is set, it connects to `localhost:<newJdwp>` instead of the stale OLD port. The forward at the new port is alive and waiting. SUCCESS.

**Why `--jdwp-port` is needed:** VSCode's session configuration is locked at session creation. The DAP `attach` request always carries the `port` that was stamped into the config at `startDebugging` time. Toolbar Restart reuses the OLD config, so the DAP request always carries the OLD port. Without the CLI override, the JVM would connect to a torn-down forward and ECONNREFUSED.

**Custom command `Android: Restart Debug Session` flow:**
1. `runRestartSessionCommand` reads `__pid` → `stopDebugging(session)` → `onDidTerminate` → forward removed, JVM dies.
2. `runAttachToPidCommand(pid)` → allocates a new JDWP port via `forwardJdwp(pid)` (a new `AdbJdwpController`), spawns a fresh JVM with `--jdwp-port=<newJdwp>` (the launcher-side CLI arg), calls `factory.setActiveAdapter(proc, dapPort, jdwpPort)`, calls `startDebugging` with the new ports stamped in.
3. The factory's tracked JVM is alive AND `cfg.__adapterPort` matches → reuse branch hits, returns `DebugAdapterServer(cfg.__adapterPort)`. VSCode connects. JVM accepts. DAP `attach` arrives with `port=__jdwpPort` which matches the CLI override → SUCCESS.

**Where forward tracking state lives:**
- The factory tracks `currentJdwpPort` (the port whose forward is currently believed to be alive). It's set by `setActiveAdapter` (on attach) and by the caller of `spawnFreshAdapter` (on toolbar Restart, after the call returns). It's cleared by `killActive`.
- The `AdbJdwpController` (in the shared registry) tracks its own `activeForwards` Set — but only for the forwards it created in this VSCode session. The factory doesn't depend on that Set being correct; it tracks the JDWP port separately as the source of truth.
- **Source of truth for `onDidTerminateDebugSession`'s port**: `factory.getJdwpPort()`, NOT `session.configuration.__jdwpPort`. VSCode's toolbar Restart clones the FIRST session's configuration for every subsequent restart, so on the 2nd+ restart `__jdwpPort` in the config is stale (points at the original attach's port, not the latest `spawnFreshAdapter` allocation). Reading from the session config would silently no-op the next `removeForward` (short-circuiting on `activeForwards.has(port)`) and LEAK the forward on every restart after the first. The factory's tracked port is the authoritative source. `killActive` MUST run AFTER `getJdwpPort()` because `killActive` clears the tracking.

**Final cleanup paths:**
- `onDidTerminateDebugSession` reads `factory.getJdwpPort()` (the live factory-tracked port), then `factory.killActive()`, then calls `jdwp.removeForward(factoryPort)` against the registered controller. If the factory is blind (extension reloaded between sessions), falls back to `session.configuration.__jdwpPort`.
- `deactivate()` runs `jdwp.cleanupAllForwards()` to catch anything left over (extension reload, VSCode shutdown).
- Force-quit (where `deactivate` may not run) leaks the forward on the device; documented in Open Question 7.

**Rationale:**
- Atomic teardown is correct: every restart is a clean slate, no stale forwards on the device, no leaked JVMs.
- Toolbar Restart "just works" because the factory has the runner + pid in `setRestartContext` — it can rebuild the entire (forward + JVM + index) chain from scratch in `createDebugAdapterDescriptor`.
- The `--jdwp-port` CLI override decouples the JVM's "where to find JDWP" knowledge from VSCode's session-config mechanism. Without it, the JVM would always try the OLD port (from the DAP `attach` request) and ECONNREFUSED; with it, the JVM uses the port that the extension actually set up the forward for.

**Alternatives considered:**
- *Keep forward alive across terminates, reuse on toolbar Restart* (revised fix attempt): simpler — `onDidTerminate` only kills the JVM, factory keeps `currentJdwpPort` and the forward is reused by the new session. But this violates the "atomic teardown" invariant the rest of the design hinges on (stale forwards accumulate; if the user does a few toolbar Restarts and then a custom Restart that allocates a different port, the OLD forward is leaked). Also: between sessions, the device-side forward keeps a JDWP socket open against the target process — if the target recycles (new attach), the JDWP listener moves and the forward points at nothing. The forward-must-go-with-the-JVM policy is cleaner.
- *Have the factory check `cfg.__jdwpPort` and skip the CLI override* (let the DAP attach request carry the new port): would require VSCode to ALSO let the factory mutate the session configuration before `startDebugging` completes. It doesn't (VSCode captures the configuration at the moment `startDebugging` resolves; subsequent calls to `createDebugAdapterDescriptor` only get to choose the transport, not the session config). The CLI override is the only way to push the new port into the JVM.
- *Override VSCode's `workbench.action.debug.restart` to call our custom command*: requires intercepting a global keybinding/command, fragile and breaks for users who bind their own restart shortcut.
- *Add a `noRestart` debug-type attribute to disable the toolbar Restart button*: Hides the feature entirely — users would have to use the command palette. We can do better by making the toolbar button work, so we don't need to disable it.

### Decision 15: AndroidJavaFactory owns the JVM `ChildProcess` AND can rebuild a session from `setRestartContext`

**Choice:** `AndroidJavaFactory` tracks the active JVM as a `ChildProcess` reference (`adapterProc: ChildProcess | undefined`) plus its DAP port (`currentPort: number | undefined`) AND the JDWP port whose adb forward is currently bound (`currentJdwpPort: number | undefined`). Liveness is decided by `isProcAlive(proc) = proc.exitCode === null && proc.signalCode === null` — no TCP probe.

The factory exposes:
- `setActiveAdapter(proc, dapPort, jdwpPort)` — register a fresh JVM paired with its adb forward, killing any prior tracked JVM first (idempotent; no leaked JVMs across rapid attach cycles). Sets `currentPort` and `currentJdwpPort`.
- `killActive()` — `proc.kill()` and clear `adapterProc` + `currentPort` + `currentJdwpPort`. Idempotent. Called from `onDidTerminateDebugSession` (after `removeForward`) and `deactivate`. Does NOT touch adb forwards — those are removed by their own handlers.
- `getPort()` — return the current DAP port (used by `UiExtApi.getAdapterPort`).
- `setRestartContext({ javaPath, jarPath, entries, runner, pid })` — capture everything needed to spawn a fresh JVM AND a fresh adb forward from `createDebugAdapterDescriptor` if the tracked JVM has died. The `runner` + `pid` are what let `spawnFreshAdapter` build a brand-new `AdbJdwpController` and call `forwardJdwp(pid)` even though the original `attach.ts` call site is long gone.
- `createDebugAdapterDescriptor(session)` — async; reads `session.configuration.__adapterPort`; if it matches the tracked port AND `isProcAlive` returns true, return `new vscode.DebugAdapterServer(cfgPort)` (the JVM is alive, the forward is already up); otherwise spawn a fresh JVM (and a fresh forward) via `spawnFreshAdapter()` and return its new DAP port.
- `spawnFreshAdapter()` — private; allocates a new JDWP port via `new AdbJdwpController(this.restartRunner).forwardJdwp(this.restartPid)`, allocates a new DAP port via `allocateFreePort()`, spawns the JVM with `--port=<newDap> --jdwp-port=<newJdwp>` (so the JVM connects through the fresh forward regardless of the stale `__jdwpPort` in the DAP `attach` request), waits for READY.
- `clearPort()` / `setPort(port)` — legacy no-ops kept for compatibility (callers in `attach.ts#cleanup` still invoke `clearPort`; the new model makes them irrelevant but kept to avoid touching every cleanup site).

**Why no TCP probe:** the adapter JVM's `ServerSocket.accept()` is a one-shot consumer — every connection drains it. If the factory probed the port with a short-lived socket to ask "is anyone listening?", the probe would consume the accept slot that VSCode's real DAP client is supposed to fill. The TCP probe would succeed and return, the factory would decide "JVM is alive, reuse this port" — and VSCode's subsequent DAP connect would either wait for the next accept (single-shot servers hit this) or silently lose the race.

A `ChildProcess` reference gives us perfect, free liveness: we know we launched the JVM, Node gives us its exit code the moment it exits, we know its signal code if we killed it. There's no scenario where `proc.exitCode === null && proc.signalCode === null` is true but the JVM isn't accepting connections.

**Why the restart context is captured separately:** the factory's `createDebugAdapterDescriptor` is called by VSCode AFTER the original `vscode.debug.startDebugging` chain has finished. By then `attach.ts`'s local variables (the JVM it just spawned) are gone — the only way to spawn a replacement is to have stashed the JDK path, jar path, and source-index entries. `setRestartContext` does this once per attach and the factory holds it until the next attach overwrites it.

**Rationale:**
- The TCP-probe bug is subtle and would only show up in production ("first attach works, restart ECONNREFUSEs"). Worth avoiding up-front.
- Holding a `ChildProcess` reference is the canonical Node way to own a spawned process. No novel infrastructure.
- Capturing restart context means the factory needs no extra plumbing when VSCode asks it for a descriptor for a re-launched session — it has everything it needs locally.

**Alternatives considered:**
- *TCP probe with `SO_REUSEADDR` to avoid consuming the accept slot*: doesn't work — `accept()` consumption is on the server side, not the client side. The probe's connect() wakes up `accept()` and the slot is consumed regardless of socket options.
- *Per-session factory (one AndroidJavaFactory per DebugSession)*: VSCode's debug adapter descriptor factory is a singleton per extension per type — we can't substitute per-session. The factory has to be long-lived.
- *Probe via `proc.kill(0)`*: on POSIX, `kill(0)` returns 0 if the process exists. Portable to Windows via `tasklist` but adds platform branching for something Node already tracks via `exitCode`/`signalCode`. Rejected.

### Decision 16: Adapter JVM loops on `accept()` with per-session executor

**Choice:** `adapter/src/main/kotlin/.../Main.kt#runTcp` wraps `serverSocket.accept()` in a `while (!Thread.currentThread().isInterrupted)` loop. Each accepted client runs on a new daemon thread (`dap-client-${client.port}`) which constructs a fresh `DapServer` AND a fresh `ExecutorService` (a `newCachedThreadPool` whose threads are named `dap-handler`). The outer `executor` parameter that `runTcp` receives is unused by the loop — it exists only to be shut down on JVM exit, and is kept around for compatibility with the stdin path (which uses it).

Each session's `finally` block runs `sessionExecutor.shutdown()` + `sessionExecutor.awaitTermination(2, TimeUnit.SECONDS)` + `client.close()` — independent of every other session.

**Why per-session executors:**
- The DapServer's `handleDisconnect` calls `jdi?.vm?.exit(0)` AND the JVM-side shutdown sequence (`server.shutdown()`, `eventDispatcher.stop()`, etc.). Sharing one executor across sessions would mean one session's `executor.shutdown()` blocks new tasks on other sessions — even though JDI events for those sessions would still be queued, no handler thread would run them.
- Per-session executors make `awaitTermination(2, TimeUnit.SECONDS)` meaningful per-session: the session has at most 2 seconds to drain in-flight handler tasks before its resources are reclaimed.

**Why loop on accept:**
- VSCode's native Restart sends `disconnect`, the DapServer's `handleDisconnect` runs `vm.exit(0)` (the TARGET app exits, not our adapter), the DapServer closes its `MessageTransport`, the per-session thread exits, the accept loop continues to block on `serverSocket.accept()`.
- When the new session connects, the loop accepts and starts a fresh DapServer.
- The only way out of the accept loop is `Thread.interrupt` (sent by `proc.kill()` from the extension side) or `serverSocket.close()`.

**Rationale:**
- Single-shot `accept()` (the previous design) forced the factory to spawn a fresh JVM on every restart, leaking JVMs across rapid restart cycles.
- The per-session executor is a small constant (a `CachedThreadPool` is just a queue + a thread factory); the memory cost is negligible.

**Alternatives considered:**
- *Single shared executor across all sessions*: rejected (see above).
- *Stdin JSON-RPC mode* (no accept loop, one process per session): rejected — adds a process spawn on every restart and complicates the launcher's stream wiring.
- *One JVM per session, no looping*: rejected — JVM cold-start is ~500ms on most machines, plus index-file write + read is another 50-200ms. On every restart. Not noticeable once, painful during a "step-through-then-restart" debugging session.

### Decision 17: adb discovery ladder — config / running / common / PATH / error

**Choice:** `findAdb(configuredPath)` in `extension/src/adb/path.ts` exposes a four-step ladder:

1. **Configured.** If `configuredPath !== 'auto'` and `fs.existsSync(configuredPath)` → use it. If it doesn't exist, throw `errAdbConfiguredMissing` (do NOT silently fall back — wrong config is a config bug, surface it).
2. **Running adb (auto only).** `findRunningAdb()` is platform-specific:
   - **Linux:** walk `/proc/<pid>/cmdline` for any process whose `argv0 basename === 'adb'` OR whose last argv token is `adb`. For each hit, resolve `/proc/<pid>/exe` symlink via `realpathSync` to follow symlink chains; verify the result is a regular file.
   - **Windows:** spawn `powershell -NoProfile -NonInteractive -Command "Get-Process adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Path"` via `execFileSync` (5-second timeout, `windowsHide: true`); parse output; return the first line whose path `fs.statSync(...).isFile()` succeeds.
   - **macOS:** returns `null` — there's no `/proc` and no clean cross-process binary path reader (would require `lsof` shell pipelines). Falls through to common paths / PATH.
3. **Common install paths (auto only).** Per-platform short list: Linux `/opt/android-sdk/platform-tools/adb`, `~/Android/Sdk/platform-tools/adb`, etc.; macOS `~/Library/Android/sdk/platform-tools/adb`, `/opt/homebrew/bin/adb`, `/usr/local/bin/adb`; Windows `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, `%ProgramFiles%\Android\platform-tools\adb.exe`. First one whose `isExecutableFile(p)` (`fs.statSync(p).isFile()`) returns true wins.
4. **`$PATH` (auto only).** `findAdbInPath()` splits `$PATH` (on `:` Unix / `;` Windows) and checks each directory for `adb` / `adb.exe`. First match wins.
5. **Error.** If 2-4 yield nothing, throw `errAdbNotFound`. The user sees "adb binary not found. Set `vscode-android-debugger.adbPath` to the absolute path of adb, or add it to $PATH" BEFORE any device interaction.

`AdbPathResult.source` is reduced to two coarse buckets: `'config'` and `'auto'`. The internal ladder step (running / common / PATH) is logged for debugging but not exposed in the result type — callers don't need to distinguish.

**Rationale:**
- "Prefer running adb" reflects how Android tooling actually behaves in practice — most users have `adb` running in the background (Android Studio, Gradle daemon, manual `adb start-server`) and want us to use the same binary.
- The platform-specific common-paths list is short on purpose: it covers the three distributions that 95% of users install (Android Studio SDK, Homebrew, system packages) without trying to enumerate every conceivable install path.
- Throwing rather than silently spawning a bare `"adb"` token ensures the user gets a clear error if no adb is found. The previous design's fallback (`return { adbPath: 'adb', source: 'fallback' }`) hid misconfigurations behind a confusing later "spawn adb ENOENT" failure.

**Alternatives considered:**
- *Spawn a probe (`adb version`) and parse the output*: would let us discover adb via PATH the way the OS does, but conflates "is the binary in PATH" with "does it run". Less control over error messages.
- *Longer common-paths list covering distro packages (`/snap/bin/adb`, `/var/lib/snapd/snap/bin/adb`, etc.)*: maintenance burden (distros change paths) for marginal coverage gain.
- *Use a single `'adb'` token and rely on `child_process.spawn` PATH resolution*: rejected — `spawn` does search PATH, but the resolved path is hidden from the user (only "ENOENT" surfaces) and the launcher loses the ability to log which binary it's using.

### Decision 18: JDK discovery — config / `JAVA_HOME` / `$PATH` only (no platform enumeration)

**Choice:** `detectJdk(configuredPath)` in `extension/src/util/jdk.ts` runs three steps:

1. **Configured.** If `configuredPath !== 'auto'` and `fs.existsSync(configuredPath)` → run `java -version`; if exit code is 0 and the version regex matches `version "X..."` with X ≥ 8, accept. If the configured path doesn't exist, throw `errJavaConfiguredMissing`.
2. **`$JAVA_HOME`.** If `process.env.JAVA_HOME` is set, check `$JAVA_HOME/bin/java` (or `java.exe`). If it exists, add to candidates.
3. **`$PATH`.** `findJavaInPath()` splits `$PATH` and checks each directory for `java` / `java.exe`.

The candidates are tried in order; the first that runs `java -version` successfully AND reports major version ≥ 8 is returned. If none match, `detectJdk` returns `null` and the caller surfaces the error.

**We deliberately do NOT enumerate platform common paths** — no `/Library/Java/JavaVirtualMachines`, no `C:\Program Files\Eclipse Adoptium\jdk-*`, no `/usr/lib/jvm/*`, no Homebrew openjdk symlinks. The previous design enumerated these and the list was brittle: JDK install dirs are version-stamped (e.g. `jdk-21.0.9.10-hotspot`), distro permutations vary wildly (Debian/Ubuntu's `/usr/lib/jvm/java-21-openjdk-amd64`, Arch's `/usr/lib/jvm/java-21-openjdk`, Fedora's `/usr/lib/jvm/java-21-openjdk-<arch>`), and Homebrew's `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` is fragile to renames. Almost every JDK install sets `JAVA_HOME` (Adoptium installer, SDKMAN, IntelliJ's bundled JDK settings, system packages) so we trust that convention rather than enumerating the alternatives.

**Rationale:**
- `JAVA_HOME` + `$PATH` is what every Java build tool (Maven, Gradle, sbt, kotlinc) uses. Mirroring that contract means our autodetection works wherever the user's other tools work.
- A short, deterministic ladder is easier to debug than a long enumeration. "Your JDK was found in $JAVA_HOME" is a clear, falsifiable log line; "Your JDK was found in the 7th directory of our hardcoded probe list" is not.
- Throwing on a missing configured path matches the adb policy: wrong config is a config bug, surface it.

**Alternatives considered:**
- *Enumerate `update-alternatives` / `sdkman` / `jEnv` configurations*: would cover more users but adds platform-specific shell-out for marginal gain.
- *Accept JDK 11+ only*: not needed — JDI works fine on JDK 8 for our needs.
- *Bundle a minimal JRE in the extension*: rejected — adds 50+ MB to the VSIX and breaks for users who want to control their JDK.

### Decision 19: Unified `${tmpdir}/d4a-index/` temp dir, no per-process subdirs

**Choice:** `extension/src/util/indexFile.ts` defines `INDEX_TMP_DIR = path.join(os.tmpdir(), 'd4a-index')`. `writeIndexFile(entries)` does `fs.mkdirSync(INDEX_TMP_DIR, { recursive: true })` and writes `index-${process.pid}-${Date.now()}-${random6}.json` directly into it. No `pid-${pid}/` subdirectories.

`process.pid` in the filename is informational (helps debugging when one userdebug app's adapter crashes and leaves files behind) but is not used to namespace the directory. The 6-character random suffix is the actual collision-avoidance mechanism.

`activate()` in `extension.local.ts` calls `sweepStaleIndexFiles(24h)` which deletes every file in `INDEX_TMP_DIR` whose `mtime` is older than 24 hours. The sweep is best-effort: missing dir → no-op; EACCES on individual files → skipped.

The previous design used `pid-${pid}/` subdirs and required knowing the adapter's exit to know when to clean up. The unified dir + filename suffix + 24h sweep replaces the lifecycle coupling with time-based garbage collection.

**Rationale:**
- Files are short-lived (write → adapter reads → adapter deletes in its `finally`). The 24h window is much longer than the typical write-to-delete interval, so legitimate files are never swept; crashed-adapter files are bounded.
- No subdirs means `sweepStaleIndexFiles` is a single `readdirSync` + filter, no recursion.
- `mkdirSync({ recursive: true })` is a no-op if the dir already exists, so the sweep on next activation is safe even if multiple activations race (they don't — Node is single-threaded, but the recursive option is documented as race-safe across processes).

**Alternatives considered:**
- *Per-pid subdirs + explicit cleanup*: requires the adapter to know its caller PID and the extension to track which subdirs are "live". More state to leak.
- *Use Node's `tmp` package*: external dependency for a 10-line utility. Rejected.
- *Use VSCode's `ExtensionContext.globalState` or `workspaceState` to track temp file paths*: state isn't designed for file handles and would have to be cleaned up explicitly on every attach anyway.

### Decision 20: adb stdout/stderr log truncation

**Choice:** `extension/src/adb/runner.ts` defines `STDOUT_LOG_MAX = 200` and `STDERR_LOG_MAX = 500` and a `truncateForLog(text, max)` helper. The `proc.on('exit')` log applies `truncateForLog` to both stdout and stderr before logging. Truncation is for LOGGING only — the runner still returns the full stdout/stderr to the caller via `AdbResult`.

The Output channel previously drowned on commands like `adb shell ps -A -o PID,NAME` (returns ~200 process rows × ~30 chars = ~6KB) and `adb logcat -d` (returns tens of KB of log lines per call). Truncation keeps the log readable while preserving the first 200 chars (often enough to identify the command's first lines — useful when debugging "why did this process list return empty?").

**Rationale:**
- The user reads the Output channel to debug the extension itself. Verbose command output makes the channel useless for finding actual errors.
- 200 chars is enough to see the first 5-7 process rows from `ps` and the first 2-3 logcat lines — enough to confirm the command ran and returned something.
- The `+<N> chars` suffix tells the user how much was truncated so they can tell at a glance whether the truncation hid something important.
- Truncation applies to stdout AND stderr because stderr from adb (rarely, but for things like perror dumps) can also be verbose.

**Alternatives considered:**
- *Pretty-print only the first N rows and `... [+M rows]`*: more useful but adds a parser. 200-char truncation is a single substring + concat — trivial cost.
- *Log only on non-zero exit*: hides useful "the command worked but the output is weird" diagnostics. Rejected.
- *Log full output to a per-command file under `.vscode/vscode-android-debugger/logs/`*: useful for deep debugging but adds a parallel file API the user has to learn. Defer to V2 if needed.

### Decision 21: Static checks before device interaction in attach flow

**Choice:** `commands/attach.ts` extracts `prepareAttachContext(statusBar)` as a separate async function that runs FIRST in both `runAttachCommand` (full attach) and `runAttachToPidCommand` (restart entry). It runs:
- `findAdb(config.adbPath)` (throws on missing adb)
- `detectJdk(config.javaPath)` (returns null on missing JDK; caller throws `getJdkErrorMessage()`)
- `new AdapterLauncher().resolveAdapterJar(extensionPath)` (throws if jar missing in extension resources)

Only after `prepareAttachContext` resolves does `runAttachCommand` proceed with `new AdbJdwpController(ctx.runner)`, `listDevices`, `listJdwpProcesses`, and `pickProcess`. The previous design ran these checks AFTER the user selected a process, meaning a misconfigured environment would surface as a confusing failure deep into the flow (the user had already picked a process, watched the QuickPick close, then seen an error like "java not found").

The restart entry `runAttachToPidCommand` also calls `prepareAttachContext` first — same rationale, and it lets restart succeed even if the user has changed their JDK between sessions.

**Rationale:**
- Bad config is independent of which device or process the user picks. Surfacing it up-front saves the user 30 seconds of UI interaction per failed attach.
- Restart skips `listDevices` / `listProcesses` / `pickProcess` (we already know the PID) but still needs the static checks (JDK could have been uninstalled, adb could have moved).
- Symmetry: both full-attach and restart call the same pre-flight helper. One source of truth for "is the user's environment OK?".

**Alternatives considered:**
- *Lazy checks at the moment they're needed (e.g. check adb right before `forwardJdwp`)*: leaves the user staring at an error mid-flow. Worse UX.
- *Check adb only after `listDevices` returns 0*: conflates "no adb" with "no devices". Bad signal.

## Risks / Trade-offs

**R1: JDI capabilities on ART may differ from HotSpot**
ART implements a JDWP subset. Some JDI calls (e.g., `canBeModified()`, certain `ReferenceType` capabilities) may return `false` or throw.
*Mitigation:* Adapter wraps all capability checks; degrades gracefully. Tested early in development against a real device.

**R2: Lambda debugging UX is awkward**
ART compiles lambdas to synthetic classes (`Foo$$Lambda$1`). Breakpoints on lambda code are set against these synthetic names.
*Mitigation:* MVP exposes the synthetic class name in breakpoint errors; user can manually navigate. V2 should add lambda unminify.

**R3: Source/runtime version drift causes wrong line hits**
If workspace source and running bytecode are from different builds, line numbers may be off. JDWP doesn't tell us this directly.
*Mitigation:* User guarantees correspondence (per scope). Adapter compares `Location.lineNumber()` with expected value; mismatch → warning, no automatic re-resolution.

**R4: Remote ext activation failure (v4-alt)**
The local ext depends on the remote ext being activated to call `buildIndex()`. If remote activation fails (network blip during activation, version mismatch, extension not installed on remote side), the user would see an empty index and broken breakpoints.
*Mitigation:* Local ext wraps the `extensions.getExtension().exports.buildIndex()` call in a try/catch. On failure, falls back to its own `vscode.workspace.fs.readFile` (v4 behavior, slower but functional). Status bar shows which path is active. Activation retry: if remote ext activates later (e.g., user installs extension mid-session), subsequent attach flows use the fast path. **MVP does not auto-retry** within a single attach; user clicks "Debugger with Android" again after fixing remote side.

**R5: SourceIndex build time for large AOSP**
AOSP `frameworks/base` has ~30000 `.java` files. Even scoped to marked roots, index build is non-trivial.
*Mitigation:* Build runs on background thread; UI shows "Indexing..." status; first-build estimate 5-10s for typical marked root scope. Subsequent session starts rebuild (no cache) — acceptable cost.

**R6: adb path detection across platforms**
adb lives in different default locations on Linux/macOS/Windows.
*Mitigation:* TS extension probes common paths and `PATH`. If detection fails, user is prompted to set `vscode-android-debugger.adbPath`.

**R7: Adapter JVM leaks if VSCode is force-quit mid-session**
If the user kills VSCode (process kill, OS shutdown) before `onDidTerminateDebugSession` fires, the JVM and adb forward leak. Decision 14's `deactivate()` covers the standard shutdown path; force-quit (where `deactivate` may not run) is a gap.
*Mitigation:* `deactivate()` runs `factory.killActive()` + `jdwp.cleanupAllForwards()` on graceful shutdown. For force-quit, the JVM exits when its parent process tree dies (JVMs aren't daemonized). adb forwards persist across adbd restarts but are tied to the adb server process — when the user runs `adb kill-server` or restarts adbd, the forwards clear. Document for users.

**R8: Toolbar Restart now rebuilds forward + JVM in the factory**
After D14's atomic-teardown policy, the toolbar Restart path triggers `factory.spawnFreshAdapter` which calls `adb forward` again and spawns a fresh JVM. This means every toolbar Restart round-trips adb (~30-100ms on a fast machine, ~300-500ms on first call) plus JVM cold-start (~500ms).
*Mitigation:* Acceptable — toolbar Restart is the user's explicit "I want a clean session" gesture, so the cost matches the intent. Document the cost so users understand why it's slower than a typical VSCode Restart. If profiling later shows the adb round-trip is the bottleneck, we could cache the prior `forwardJdwp`'s port and only re-set if it's been freed by the device (rare — adbd persists forwards until `adb kill-server`); but that requires deciding when to invalidate the cache, which adds complexity. Defer.

**R9: `findRunningAdb` on Windows requires PowerShell**
The Windows path-resolution step spawns `powershell -NoProfile -NonInteractive -Command ...` once per attach. PowerShell startup is ~300-500ms on most machines.
*Mitigation:* The user only pays this cost on Windows attach (most attach flows run >1s anyway for `adb devices` round-trip). The PowerShell script is short and `-NoProfile` skips profile loading. If performance becomes a concern we can cache the result by adb-server PID and invalidate on `findRunningAdb` errors. Defer.

**R10: Index temp files written outside `globalState`**
The `INDEX_TMP_DIR` lives in `os.tmpdir()` (typically `%TEMP%` on Windows, `/tmp` on Linux/macOS). It's not under VSCode's control and is not cleaned up when the user uninstalls the extension.
*Mitigation:* `sweepStaleIndexFiles(24h)` runs on every activation; the 24h window means files don't accumulate. Files are tiny (a few KB for a 10k-entry index). User can manually delete `%TEMP%\d4a-index\` if they care.

**R11: Per-pid file suffix doesn't uniquely identify the producing adapter**
Two VSCode windows (e.g. local + Remote-SSH) attaching to different PIDs simultaneously could in theory share `INDEX_TMP_DIR`. The `process.pid` in the filename is the EXTENSION host's pid (the same in both windows if they're the same VSCode instance); the 6-char random suffix is the only real collision-avoidance. With 6 base-36 chars, collision probability for two parallel writes is ~1/2 billion — acceptable, but worth noting.

**R12: VSCode's toolbar Restart clones the FIRST session's configuration (NOT the most recent)**
VSCode's `onDidStartDebugSession` for a toolbar-Restarted session receives a configuration that is a copy of the FIRST session's configuration from the user's "I clicked Attach" moment — NOT a copy of the immediately-prior session. So `session.configuration.__jdwpPort` (which `attach.ts` stamped at the original `startDebugging` call) is STALE for every restart after the first. Any handler that trusts this value (notably `onDidTerminateDebugSession`) would attempt to remove a non-existent forward and LEAK the actual one. *Mitigation:* `factory.getJdwpPort()` exposes the factory-tracked LATEST port (advanced by `spawnFreshAdapter`); `onDidTerminateDebugSession` reads from the factory first and only falls back to `session.configuration.__jdwpPort` if the factory is blind (extension reload between sessions). Document this in `extension.local.ts` so future maintainers don't "simplify" the lookup back to the session config.

## Open Questions

1. **JDK 8 specific JDI quirks** — JDK 8 JDI works for our needs in theory; whether there are ART-specific edge cases requiring JDK 11+ APIs (like improved `Module` handling) is to be verified in early implementation. Decision can be revisited at V2 if blockers emerge; doesn't affect MVP structure.

2. **When to expand expression evaluator** — the MVP supports field/method/literal only. Whether to add operator support (via custom mini-evaluator) or full Java (via Janino embed) is to be decided based on actual user need after MVP lands. Adding operators later is non-breaking; doesn't affect current spec.

3. **Whether to ship bundled adb binary** — currently we require user to have adb installed and reachable. Bundling would add ~5-10MB to extension and require per-platform build matrix. Defer; revisit if user feedback indicates installation is a friction point.

4. **TS-side indexer parser parity** — The Kotlin regex parser in v3 (~80 lines for Java + Kotlin class declarations) is being ported to TS in v4-alt. The TS parser runs in V8 in both local and remote scenarios. Need to verify the same edge cases (nested classes, `enum`, `interface`, Kotlin `object`/`sealed class`/`data class`) parse correctly in both AOSP `.java` and `.kt` files. If the TS port has gaps, breakpoints on those files won't resolve; fall back to importing a small TS parser library (e.g., `java-parser` is JS-native).

5. **Cross-host file watcher for incremental updates (V2)** — v4-alt rebuilds the index on each session start. Adding a `vscode.workspace.createFileSystemWatcher` over marked source dirs + `globalState` cache would amortize this for repeat sessions. Not blocking MVP. Note: in v4-alt, the file watcher would need to be set up on the remote ext (where the files live natively).

6. **Multi-device in scenario C1** — Local adb runner currently handles one device; multiple devices would need multiplexing via `-s <serial>`. Out of MVP scope (the proposal already excludes multi-device); the JSON `deviceSerial` is reserved in the launch config for V2.

7. **Dual ext activation ordering** — `extensionKind: ["ui", "workspace"]` doesn't guarantee local activates before remote. The local ext's `vscode.extensions.getExtension(...).exports` call returns `undefined` until the remote ext has activated. We use `await ext.activate()` to force activation, but in worst case the user sees a brief "loading remote extension..." message. Acceptable; documented in UX.

8. **WSL local + Windows remote (or vice versa)** — In v4-alt, the extension runs in WSL if WSL is the local side (where VSCode UI lives). The remote ext half runs on the remote machine (Windows or Linux). adb runs from local perspective; if local is WSL, adb is WSL's adb. Source files accessed via remote ext (native FS on remote). Edge cases (WSL using Windows-side adb via `interop`) are user's responsibility. Document.

9. **Restart cost amortization** — Decision 14 makes restart = full re-attach (new forward, new JVM, new DAP port). For an attached process that's behaving normally, this adds 1-2 seconds per restart cycle. Acceptable for "I need to restart because the session broke", but annoying for "I'm stepping through code and restarting every few minutes". Could optimize by caching the JVM across restarts in cases where the JVM is still alive and the forward can be reused (no adbd hiccup, no process recycle). Defer; revisit if user feedback indicates restart latency is a friction point.

10. **macOS adb-discovery step 2** — Decision 17 deliberately skips the "running adb" step on macOS because there's no clean cross-process binary path reader without `lsof` shell pipelines. macOS users on Homebrew will get their adb via the common-paths step; users with custom adb installs (e.g. `nix` profile) will need `vscode-android-debugger.adbPath`. Acceptable; document.

11. **JDK enumeration policy future-proofing** — Decision 18 refuses to enumerate platform common paths. This is right for today, but if the JDK ecosystem ever shifts (e.g. Ubuntu dropping `JAVA_HOME` defaults), users on those platforms will need `javaPath` config. Document the policy clearly in the README so users know what to set if autodetection fails.
