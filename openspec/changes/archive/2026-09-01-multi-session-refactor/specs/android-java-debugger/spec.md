## MODIFIED Requirements

### Requirement: Atomic Session Teardown

Every debug session SHALL be fully torn down on termination: the session's Java Adapter JVM SHALL be killed AND the session's `adb forward` for the session's JDWP port SHALL be removed. Both teardown steps happen in `onDidTerminateDebugSession`. Each session SHALL own its own JVM process and its own per-session `AdbDevice` reference — there is no shared global state between sessions. Per-session teardown MUST be self-contained: terminating session A MUST NOT touch any resource owned by session B.

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

### Requirement: TS-Side SourceIndexer (Shared Logic)

The class-name parser and `addDir` / `addFile` logic SHALL be implemented in TypeScript in `extension/src/core/indexer/`. The single `LocalIndexer` implementation SHALL serve both local and Remote-SSH windows — because the extension always runs on the UI host, all filesystem access SHALL go through `vscode.workspace.fs` / `vscode.workspace.findFiles`, which transparently proxy to the remote filesystem (Node `fs` SHALL NOT be used; it can only see the local disk). The Kotlin/Java adapter SHALL NOT walk the filesystem or parse source files.

#### Scenario: TS indexer parses Java files

- **WHEN** the indexer reads a `.java` file via `vscode.workspace.fs.readFile(uri)`
- **THEN** it extracts the `package` declaration and all top-level class/interface/enum/record declarations
- **AND** for each declaration, builds a fully-qualified name and adds `fqn → canonicalPath(uri)` to the index map

#### Scenario: TS indexer parses Kotlin files

- **WHEN** the indexer reads a `.kt` file
- **THEN** it extracts the `package` declaration and all top-level `class`/`object`/`interface`/`sealed class`/`data class` declarations
- **AND** for each declaration, builds a fully-qualified name and adds it to the map

#### Scenario: Source enumeration reaches remote workspaces

- **WHEN** a marked source dir belongs to a workspace folder (local or remote)
- **THEN** the indexer enumerates sources via `vscode.workspace.findFiles(new RelativePattern(dirUri, '**/*.{java,kt}'))` — the glob runs on the filesystem provider host, so in a Remote-SSH window the bulk listing executes on the remote
- **WHEN** a marked source dir is outside any workspace folder
- **THEN** the indexer walks it via `vscode.workspace.fs.stat` + `vscode.workspace.fs.readDirectory` recursion (skipping `build`/`.git`/dot-dirs), which still reaches the remote FS from the UI host
- **AND** neither path uses Node `fs`

#### Scenario: Kotlin adapter receives pre-built index

- **WHEN** the TypeScript extension completes indexing (single `LocalIndexer` path, local or remote window)
- **AND** a debug session starts
- **THEN** the session initializer (wired in `extension.ts`) reads `IndexManager.getAllEntries()` and sends the `custom/syncSourceIndex` custom request with the entries
- **AND** the Kotlin adapter's `SourceIndex` is populated from that request
- **AND** no file content is sent (only paths); no temp file is used; no `--index-file` CLI argument

#### Scenario: Kotlin adapter uses index for breakpoint resolution

- **WHEN** VSCode sends `setBreakpoints` whose `source.path` is a full URI in a remote window (e.g. `vscode-remote://ssh-remote%2Bhost/home/liux/.../AMS.java`)
- **THEN** the adapter normalizes it via `normalizeSourcePath` (strips `vscode-remote://<authority>` / `file://<authority>` prefixes, yielding the bare posix path) before any registry or index lookup
- **AND** the normalized key matches the `file` field of `custom/syncSourceIndex` entries
- **AND** the adapter resolves FQNs via `fileToFqns[key]`, calls JDI `VirtualMachine.classesByName(fqn)` for each FQN to obtain `ReferenceType`, resolves line numbers to JDI bytecode offsets via `Method.locationsOfLine()`
- **AND** creates JDI `BreakpointRequest`s and sends a DAP response with `verified: true/false`

#### Scenario: Canonical path model is uniform across environments

- **WHEN** any file key crosses a boundary (index entries, breakpoint payloads, adapter registry keys)
- **THEN** it uses the canonical form defined in `extension/src/util/uri.ts`: `uri.fsPath` for `file:` URIs (preserves historical local behavior, e.g. `C:\x\Y.java`), `uri.path` for every other scheme (e.g. `/home/liux/Y.java`)
- **AND** marked source dirs in Memento are stored as URI strings (`uri.toString()`), never `uri.fsPath` (which mangles remote URIs on a Windows UI host)

### Requirement: Incremental Source Index Updates

The extension SHALL keep the Java Adapter's `SourceIndex` in sync with the user's workspace state during an active debug session, without requiring the user to re-attach. Mid-session updates SHALL use the `custom/syncSourceIndex` DAP custom request (full replace; the former `sourceDirs/add` incremental-merge request was removed — the client never used it, and full replace keeps the adapter's logic simple).

#### Scenario: sourceDirs config changes mid-session (full replace)

- **WHEN** user adds or removes a folder from the marked source dirs while one or more debug sessions are active
- **THEN** `SourceDirManager` emits its change event
- **AND** `extension.ts` bridges to `IndexManager.rebuildFromDirs(getAllDirs(), maxFiles)` which rebuilds the in-memory index
- **AND** emits `onIndexReady(allEntries)` which `extension.ts` bridges to `SessionManager.broadcastIndex(allEntries)`
- **AND** `broadcastIndex` iterates every live `SessionContextImpl` and calls `ctx.dap.syncSourceIndex(entries)`, which sends the DAP `custom/syncSourceIndex` custom request with the new entries
- **AND** subsequent `setBreakpoints` calls in every live session resolve against the new index

#### Scenario: Breakpoint set in unindexed file (auto-include via add)

- **WHEN** user sets a breakpoint in a `.java` or `.kt` file that is not under any marked source dir AND a debug session is active
- **THEN** `BreakpointManager` emits `onNewFileNeedsIndex([uris])` (only for files outside marked dirs, judged via the injected `isPathMarked` callback that receives `vscode.Uri`s)
- **AND** `extension.ts` bridges to `IndexManager.autoIncludeFiles(uris)` which parses and merges the entries in-memory (reads via `vscode.workspace.fs.readFile`, so remote files work too)
- **AND** `IndexManager` then emits `onIndexReady(allEntries)` → `extension.ts` → `SessionManager.broadcastIndex` → every live session calls `ctx.dap.syncSourceIndex`
- **AND** each session's adapter receives `custom/syncSourceIndex` with the merged entries (full replace, not partial merge — keeps the adapter's logic simple)
- **AND** the breakpoint resolves correctly on the next `setBreakpoints` round-trip

#### Scenario: Auto-include is silent on the wire for non-Java/Kotlin breakpoints

- **WHEN** a breakpoint is added in a non-`.java`/`.kt` file (e.g., `.txt`, `.gradle`, `.xml`)
- **THEN** `BreakpointManager` does NOT emit `onNewFileNeedsIndex`
- **AND** no `custom/syncSourceIndex` is sent
- **AND** each adapter's index is unchanged

#### Scenario: Session ends mid-watch

- **WHEN** a session's adapter process exits (normal `disconnect`, crash, or user cancels)
- **THEN** the corresponding `SessionContextImpl` is disposed
- **AND** `SessionManager.sessions.delete(sessionId)` runs
- **AND** any subsequent `sourceDirs` config change or breakpoint change still emits broadcasts but the disposed session is no longer in the map (no DAP request sent to it)
- **AND** a new attach creates a fresh `SessionContextImpl`

#### Scenario: Adapter no longer accepts file-walking requests

- **WHEN** the Java Adapter receives any DAP request related to source indexing (the post-attach initial `custom/syncSourceIndex` push from the session initializer, or mid-session `custom/syncSourceIndex`)
- **THEN** it accepts only the entry-list shape: `Array<{fqn: string, file: string, sourceUri?: string}>` — no directory paths, no file contents, no walk instructions
- **AND** the adapter has NO filesystem access for source indexing (no `Files.walk`, no parser regexes)
- **AND** all FS walking and parsing happens in TS

#### Scenario: DAP echoes use the client-resolvable path form

- **WHEN** the adapter echoes a source path back to VSCode (breakpoint events, `stopped` events, `stackTrace`, `loadedSources`)
- **THEN** it uses the display form, never the bare posix internal key: the entry's `sourceUri` (full `vscode-remote://...` URI) when present, the canonical key otherwise (a native local path)
- **AND** breakpoint payloads pushed by the extension use the same rule (`clientPath`: fsPath for `file:` URIs, full URI otherwise)
- **WHEN** a Windows UI host runs a Remote-SSH window and a breakpoint hits
- **THEN** the `stopped`/`stackTrace` source path is a `vscode-remote://` URI that VSCode resolves through the remote filesystem provider — a bare posix path would render as `\home\...` and fail to open the file and to light up the verified breakpoint

## REMOVED Requirements

### Requirement: Shared AdbJdwpController

**Reason**: Replaced by per-session ownership. Each `SessionContextImpl` builds its own per-session `AdbDevice` (from `adb/AdbDevice.ts`) holding its own `activeForwards: Set<number>`. There is no longer a module-level singleton sharing controller references across attach / terminate / factory — each session's forward tracking is self-contained.

**Migration**: Code that previously called `getJdwpController().removeForward(port)` from `onDidTerminateDebugSession` should instead look up the session's own `SessionContextImpl` in `SessionManager.sessions` and call `ctx.dispose()` which internally calls the session's own `AdbDevice`'s `removeForward`. The `jdwpRegistry` module is deleted. (Device enumeration for the attach picker is served by `AdbDeviceFinder.findAll()` returning ready-to-use `AdbDevice` instances.)

### Requirement: Adapter JVM Lifecycle Owned by Factory

**Reason**: Replaced by per-session `SessionContextImpl`. The factory is now a stateless lambda that returns `DebugAdapterServer(dapPort)` based on the `dapPort` allocated by `SessionContextImpl` (read from session configuration). The factory no longer owns any `ChildProcess`, no JDWP port, no restart context.

**Migration**: Code that previously called `factory.setActiveAdapter(proc, dapPort, jdwpPort)` / `factory.killActive()` / `factory.setRestartContext(...)` / `factory.spawnFreshAdapter()` is replaced by `SessionContextImpl.create(session, device, process, options)` which does all those things internally. Code that called `factory.getJdwpPort()` to find the port to tear down on terminate no longer needs to — the `SessionContextImpl` itself knows its own `jdwpPort`.

### Requirement: Index Temp File Hygiene

**Reason**: Replaced by in-memory index pushed via the `custom/syncSourceIndex` custom request from the session initializer right after session start. There is no longer any temp index file written to `<os.tmpdir>/` by the extension. The Java adapter's `handleAttach` `fqnInfo` parsing remains only as a dormant compatibility path (no config field is written anymore).

**Migration**: Code that previously called `writeIndexFile(entries)` and passed `--index-file=<path>` to the JVM is replaced by pushing the in-memory snapshot through `SessionManager.setSessionInitializer` → `ctx.dap.syncSourceIndex(entries)` right after the session starts. Code that previously called `sweepStaleIndexFiles()` on activation is deleted (no files to sweep). The Kotlin `Main.kt#loadInitialIndex` function is deleted. The Kotlin `DapServer` constructor's `initialIndexEntries` parameter is removed.

### Requirement: Remote-Side TS SourceIndexer API

**Reason**: Unimplementable as specified, so the workspace-side implementation is deleted (`remote/DebugRemoteApi.ts` + `remote/DebugRemoteApiImpl.ts`). First, `extensionKind: ["ui", "workspace"]` is a *preference order*, not a contract — VSCode loads the extension on exactly ONE host; in a Remote-SSH window the effective kind is always `ui`, so the workspace half never activates and its `buildIndex` API never exists at runtime (confirmed by user logs: only `[DISPATCHER UI]` ever appeared). Second, the VSCode remote-extensions documentation explicitly states that cross-host `exports` are not reachable: "the API from the provider is not available to the consumer" — `vscode.extensions.getExtension().exports.buildIndex(...)` can never succeed against a workspace-host instance. Superseded by the single-path `LocalIndexer` (see ADDED "Single-Path TS SourceIndexer (Remote-SSH)").

**Migration**: `IndexerFactory` and `RemoteIndexer` are deliberately RETAINED as the injection seam for a future dual-extension architecture: a dedicated `extensionKind: ["workspace"]` helper extension will run the indexer natively on the remote host, and the main extension will call it through a cross-host channel. At that point `IndexerFactoryOptions.extensionId` points at the helper extension and the inlined `RemoteIndexApi` structural contract (now in `RemoteIndexer.ts`) is replaced by the helper's API type. Until then `createIndexer` always returns `LocalIndexer` (probing finds no provider) and reports fallback via `onFallback`.

### Requirement: Remote-SSH Scenario C1 (Dual-Loaded Extension with Remote TS Indexer)

**Reason**: Same unimplementable architecture as "Remote-Side TS SourceIndexer API" — the dual-loaded same-ID extension with cross-host `exports.buildIndex` calls never activates its workspace half and can never exchange APIs across hosts (single-host selection + unreachable cross-host exports; confirmed by runtime logs). The scenario was also invalidated end-to-end by the Remote-SSH fix: source dirs are now stored as URI strings, the index is built by the single `LocalIndexer` through `vscode.workspace.fs`/`vscode.workspace.findFiles` on the UI host, the initial index is pushed via `custom/syncSourceIndex` from the session initializer (not `sourceDirs/set`), and the fast path is deferred to a future dedicated helper extension. Superseded by "Single-Path TS SourceIndexer (Remote-SSH)".

**Migration**: Local windows behave as the old "Single-host (scenario A)" scenario already did — `vscode.env.remoteName` is empty, `LocalIndexer` runs directly. Remote windows now work via the remote FS proxy with the degraded-mode status bar hint instead of the remote-ext fast/fallback split. Nothing to migrate: the fast-path/fallback branches described here were never reachable.

## ADDED Requirements

### Requirement: Single-Path TS SourceIndexer (Remote-SSH)

Indexing SHALL use `LocalIndexer` in every window (local and Remote-SSH): no workspace-side indexer implementation exists and no code path SHALL depend on the `exports` of a same-ID workspace-host extension instance (unreachable by VSCode design). `IndexerFactory` and `RemoteIndexer` SHALL be retained as the injection seam for the future dual-extension architecture (a dedicated `extensionKind: ["workspace"]` helper extension), but until that helper exists the factory SHALL always resolve to `LocalIndexer`. In a remote window the extension SHALL surface the degraded indexing mode on the status bar (`remoteName` non-empty → `setIndexerMode('fallback')`) while indexing continues to work via the remote FS proxy.

#### Scenario: Single indexer path replaces the remote/fallback split

- **WHEN** indexing runs in any window (local or Remote-SSH)
- **THEN** `LocalIndexer` performs enumeration via `vscode.workspace.findFiles` (in-workspace dirs) and `vscode.workspace.fs` recursion (out-of-workspace dirs)
- **AND** no code path depends on a workspace-side `DebugRemoteApi` implementation (`remote/` stays empty) or on same-ID cross-host `exports`
- **AND** in a remote window the status bar shows the degraded mode and indexing still succeeds

#### Scenario: IndexerFactory seam is kept for the dual-extension future

- **WHEN** `createIndexer` runs (today, in any window)
- **THEN** it returns `LocalIndexer` — the probe for a remote indexer provider finds none because the workspace-side implementation is intentionally absent
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

The extension SHALL deliver the initial source index to a newly started debug session when `onDidStartDebugSession` fires: a session initializer (wired in `extension.ts` via `SessionManager.setSessionInitializer`) SHALL read the `IndexManager` snapshot and send it to that session via the `custom/syncSourceIndex` DAP custom request, followed by a fallback `custom/syncBreakpoints` push of the current breakpoint snapshot. The session configuration SHALL NOT carry an `fqnInfo` field; the Java Adapter's `handleAttach` `fqnInfo` parsing remains as a dormant compatibility path only.

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
