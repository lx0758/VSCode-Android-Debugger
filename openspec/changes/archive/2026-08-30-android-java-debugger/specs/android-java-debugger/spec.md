# Android Java Debugger

## Purpose

Provides VSCode-based debugging of AOSP framework and system app Java layer code via JDWP attach to running Android processes. Designed for the common AOSP developer workflow of attaching to a userdebug/eng device with full root access, in either a fully local environment or a Remote-SSH setup where source lives on a remote Linux server while the device and adb live on the local machine.

## ADDED Requirements

### Requirement: Process Discovery

The extension SHALL provide a command that enumerates all debuggable Android processes on the connected device by reading JDWP capability. The command SHALL display the result as a list from which the user can pick a target.

#### Scenario: Local device available
- **WHEN** user invokes "Debugger with Android" with a device connected via adb
- **THEN** the extension lists at least one debuggable process and shows them in a selection UI

#### Scenario: No device connected
- **WHEN** user invokes "Debugger with Android" with no device connected
- **THEN** the extension shows a notification that no device is available, with guidance to connect a device

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

### Requirement: Log Breakpoints

The extension SHALL support log breakpoints (breakpoints that emit a message and continue without suspending). The log message MAY contain placeholders of the form `{expr}` where `expr` is a simple expression referring to local variables, the `this` reference, fields of these, or no-arg method calls on these.

#### Scenario: Log breakpoint with field access
- **WHEN** user sets a log breakpoint with message `"user={this.user.name}"`
- **AND** the breakpoint is hit
- **THEN** the extension emits the formatted message to the Debug Console and continues execution without suspending

#### Scenario: Log breakpoint expression eval fails
- **WHEN** a log breakpoint expression references a non-existent field
- **THEN** the extension emits a warning to the Debug Console indicating the failed expression, replaces the placeholder with an error marker, and continues execution

### Requirement: Simple Conditional Breakpoints

The extension SHALL support conditional breakpoints where the condition is a single field access compared to a literal (e.g., `this.count == 5`, `this.user != null`). Conditions SHALL be evaluated by the adapter when the breakpoint is hit; if false, execution continues.

#### Scenario: Conditional hit with true condition
- **WHEN** user sets a conditional breakpoint `this.count == 5`
- **AND** the breakpoint is hit when `this.count == 5`
- **THEN** execution suspends

#### Scenario: Conditional hit with false condition
- **WHEN** user sets a conditional breakpoint `this.count == 5`
- **AND** the breakpoint is hit when `this.count == 3`
- **THEN** execution continues without suspending

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
- **WHEN** the extension activates in a workspace that has `debugger-for-android.sourceDirs` in `.vscode/settings.json` (single-folder) or in `.code-workspace` (multi-folder) but has an empty Memento
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

### Requirement: Remote-SSH Scenario C1 (Dual-Loaded Extension with Remote TS Indexer)

When running in a Remote-SSH context where the source workspace is on a remote Linux server but the Android device and adb live on the local machine, the extension SHALL be loaded on **both** VSCode extension hosts (`extensionKind: ["ui", "workspace"]`). The local host SHALL run all adb commands, host all user UI, and spawn the Java Adapter as a local child process. The remote host SHALL expose a `buildIndex(sourceDirs)` API that walks the workspace using native filesystem reads and returns `FQN → file path` entries. The local host SHALL call this API via `vscode.extensions.getExtension(...).exports.buildIndex(...)` and send the returned entries to the Java Adapter via the DAP `sourceDirs/set` custom request. There SHALL be no SSH tunnels, no remote Java process, and no cross-host DAP routing.

#### Scenario: Local VSCode + Remote SSH (full scenario C1)
- **GIVEN** user has a `ssh-remote` workspace open in VSCode (workspace is on a remote Linux server)
- **AND** user has an Android device USB-connected to their local laptop
- **AND** user has marked source dirs in the remote workspace (paths stored as absolute strings in the remote workspace's `workspaceState` Memento)
- **WHEN** user invokes "Debugger with Android" and selects a process
- **THEN** the local extension host picks a free OS-assigned port `LP` via `net.createServer().listen(0)`
- **AND** runs `adb devices` locally to verify the device is connected
- **AND** runs `adb jdwp -l` locally to enumerate debuggable PIDs
- **AND** shows the QuickPick UI in the user's local VSCode window
- **AND** after process selection, runs `adb forward tcp:LP jdwp:<pid>` on the local machine
- **AND** retrieves the remote extension via `vscode.extensions.getExtension('publisher.debugger-for-android')`
- **AND** calls `await ext.exports.buildIndex(sourceDirs)` — VSCode proxies this call to the remote ext host
- **AND** the remote ext host uses `vscode.workspace.findFiles` + `vscode.workspace.fs.readFile` (native FS read on remote) to walk each source dir, parses `package` + class declarations in TS, and returns `[{fqn, file}, ...]`
- **AND** the local ext sends the entries to the Java Adapter via DAP `sourceDirs/set`
- **AND** spawns the Java Adapter as a local child process; the Adapter connects to `127.0.0.1:LP` directly (no SSH tunnel)
- **AND** the JDWP socket data flows: Java Adapter → local `adb forward` → device JDWP socket

#### Scenario: Remote ext activation succeeds (fast path)
- **WHEN** `vscode.extensions.getExtension('publisher.debugger-for-android').exports.buildIndex(sourceDirs)` is called
- **AND** the remote ext host has activated successfully
- **THEN** the call returns within seconds (typically <10s for 10k files via native FS)
- **AND** the local ext proceeds with sending entries to Java Adapter and starting the debug session

#### Scenario: Remote ext activation fails (fallback path)
- **WHEN** the call to `ext.exports.buildIndex(sourceDirs)` throws (remote ext not activated, network blip, version mismatch)
- **THEN** the local ext catches the error and falls back to its own cross-host indexing: it iterates `vscode.workspace.findFiles` + `vscode.workspace.fs.readFile` directly (slow path)
- **AND** shows a status bar message: "Remote indexer unavailable, using slower local indexing (this may take several minutes)"
- **AND** the debug session still proceeds once indexing completes
- **AND** a subsequent attach (after the user reopens VSCode or retries) will retry the fast path

#### Scenario: Single-host (scenario A) skips the remote ext entirely
- **WHEN** user opens a local folder (no `remoteName`)
- **THEN** the extension checks `vscode.env.remoteName`; if undefined, runs the indexer in the local ext directly (no IPC to a remote ext)
- **AND** adb commands run as a local child process, no ssh involved
- **AND** no SSH process is spawned, no tunnel is opened
- **AND** the local indexer uses `vscode.workspace.fs.readFile` against local URIs (native local FS, fast — equivalent to scenario C1 fast path)

#### Scenario: Indexing progress and cancellation
- **WHEN** the indexer (on either local or remote ext) is running
- **THEN** the user sees a VSCode progress notification ("Indexing N/M files in <sourceDir>…") with a cancel button
- **AND** if the user cancels, the indexer stops and the user is told the session cannot start without a complete index
- **AND** progress is reported every 50 files or every 1 second, whichever comes first

#### Scenario: Large source dir warning
- **WHEN** a marked source dir contains more files than `debugger-for-android.index.maxFiles` (default 10000)
- **THEN** before indexing starts, the extension shows a confirmation dialog: "Source dir `<path>` contains ~<N> files. Indexing may take ~<estimated_seconds>. Continue or pick a narrower dir?"
- **AND** if the user declines, prompts them to unmark or pick a narrower dir

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

### Requirement: Remote-Side TS SourceIndexer API

The remote ext host (`kind=workspace`) SHALL expose a `buildIndex(sourceDirs: string[]): Promise<FqnEntry[]>` API via its `activate()` return value. The local ext SHALL retrieve and call this API via `vscode.extensions.getExtension().exports.buildIndex(...)`.

#### Scenario: API surface
- **WHEN** the remote ext's `activate()` runs
- **THEN** it returns an object with at least one method: `buildIndex(sourceDirs: string[]): Promise<Array<{fqn: string, file: string}>>`
- **AND** calling this method with an empty array returns `[]` immediately
- **AND** calling this method with multiple source dirs walks all of them and aggregates the entries
- **AND** the returned entries have absolute file paths (matching what VSCode sends in DAP breakpoint paths)

#### Scenario: Native FS performance
- **WHEN** `buildIndex(sourceDirs)` is called with a 10k-file scope
- **THEN** the function completes in <30 seconds (native FS reads, no cross-host latency)
- **AND** reads each file via `vscode.workspace.fs.readFile(uri)` which is a native FS call on the remote side
- **AND** parses package + class declarations in TS using regex (no external parser library)
- **AND** returns a flat array of `{fqn, file}` entries — no file content

#### Scenario: Source dir outside the workspace
- **WHEN** a `sourceDir` path is not inside any open workspace folder
- **THEN** `vscode.workspace.findFiles` returns an empty result for that dir
- **AND** the indexer silently skips that dir (no error, no warning)
- **AND** the user sees the empty entry list reflected in the Java Adapter

#### Scenario: File read error
- **WHEN** `fs.readFile` fails for a particular file (permissions, file deleted between findFiles and readFile)
- **THEN** the indexer skips that file and continues with the next
- **AND** logs a warning to the extension output channel
- **AND** does NOT abort the entire buildIndex call

### Requirement: TS-Side SourceIndexer (Shared Logic)

The class-name parser and `addDir` / `addFile` logic SHALL be implemented in TypeScript in `extension/src/source/indexer.ts`. The same module SHALL be reused by both the local ext host (scenario A and the C1 fallback path) and the remote ext host (scenario C1 fast path). The Kotlin/Java adapter SHALL NOT walk the filesystem or parse source files.

#### Scenario: TS indexer parses Java files
- **WHEN** the indexer reads a `.java` file via `vscode.workspace.fs.readFile(uri)`
- **THEN** it extracts the `package` declaration and all top-level class/interface/enum/record declarations (including nested ones by indentation match)
- **AND** for each declaration, builds a fully-qualified name and adds `fqn → filePath` to the index map

#### Scenario: TS indexer parses Kotlin files
- **WHEN** the indexer reads a `.kt` file
- **THEN** it extracts the `package` declaration and all top-level `class`/`object`/`interface`/`sealed class`/`data class` declarations
- **AND** for each declaration, builds a fully-qualified name (Kotlin objects are referenced by their declared name, not instance) and adds it to the map

#### Scenario: Kotlin adapter receives pre-built index
- **WHEN** the TypeScript extension completes indexing (either via remote ext fast path or local fallback path)
- **THEN** it sends a DAP custom request `sourceDirs/set` with payload `{entries: [{fqn: "com.android.server.am.AMS", file: "/remote/path/.../AMS.java"}, ...]}`
- **AND** the Kotlin adapter populates internal `fqnToFile` and `fileToFqns` maps from the entries
- **AND** no file content is sent (only paths)

#### Scenario: Kotlin adapter uses index for breakpoint resolution
- **WHEN** VSCode sends `setBreakpoints` with `source.path = "/remote/path/.../AMS.java"` and several line numbers
- **THEN** the Kotlin adapter looks up `fileToFqns[path]` to get the FQNs declared in that file
- **AND** calls JDI `VirtualMachine.classesByName(fqn)` for each FQN to obtain `ReferenceType`
- **AND** resolves line numbers to JDI bytecode offsets via `Method.locationsOfLine()`
- **AND** creates JDI `BreakpointRequest`s and sends a DAP response with `verified: true/false`

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
- **THEN** `debugger-for-android.setupTunnel` is NOT registered
- **AND** no replacement command exists (the extension is zero-configuration for Remote-SSH)

### Requirement: Incremental Source Index Updates

The extension SHALL keep the Java Adapter's `SourceIndex` in sync with the user's workspace state during an active debug session, without requiring the user to re-attach. Updates SHALL use two DAP custom requests: `sourceDirs/set` (full replace, existing) and `sourceDirs/add` (incremental merge, new).

#### Scenario: sourceDirs config changes mid-session (full replace)
- **WHEN** user adds or removes a folder from the marked source dirs while a debug session is active
- **THEN** the local ext re-builds the entire index from the current marked dirs (using the same fast-path / fallback-path mechanism as the initial attach)
- **AND** sends the new entries to the Java Adapter via DAP `sourceDirs/set` (replacing the old index)
- **AND** subsequent `setBreakpoints` calls resolve against the new index
- **AND** the user sees a status-bar / progress indication during the re-build (or, for AOSP-scale projects, a brief "Re-indexing..." message)

#### Scenario: Breakpoint set in unindexed file (auto-include via add)
- **WHEN** user sets a breakpoint in a `.java` or `.kt` file that is not under any marked source dir AND a debug session is active
- **THEN** the extension reads just that file (`vscode.workspace.fs.readFile`), parses class declarations in TS (reusing `parseJavaFile` / `parseKotlinFile`)
- **AND** sends the entries to the Java Adapter via DAP `sourceDirs/add` (merging into the existing index)
- **AND** the breakpoint resolves correctly (`verified: true`) on the next `setBreakpoints` round-trip without requiring the user to mark the file's parent dir

#### Scenario: Auto-include is silent on the wire for non-Java/Kotlin breakpoints
- **WHEN** a breakpoint is added in a non-`.java`/`.kt` file (e.g., `.txt`, `.gradle`, `.xml`)
- **THEN** the extension does NOT send `sourceDirs/add`
- **AND** the adapter's index is unchanged

#### Scenario: Adapter no longer accepts file-walking requests
- **WHEN** the Java Adapter receives a request of type `sourceDirs/set` or `sourceDirs/add`
- **THEN** it accepts only the new payload shape: `{entries: [{fqn, file}, ...]}`
- **AND** the adapter has NO filesystem access for source indexing (no `Files.walk`, no parser regexes)
- **AND** all FS walking and parsing happens in TS

#### Scenario: Session ends mid-watch
- **WHEN** the Java Adapter process exits (normal `disconnect`, crash, or user cancels)
- **THEN** the extension's index-sync coordinator is disposed (listeners removed, references cleared)
- **AND** any subsequent `sourceDirs` config change or breakpoint change is a no-op (no DAP requests sent)
- **AND** a new attach creates a fresh coordinator

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

Every debug session SHALL be fully torn down on termination: the Java Adapter JVM SHALL be killed AND the `adb forward` for the session's JDWP port SHALL be removed. Both teardown steps happen in `onDidTerminateDebugSession` BEFORE VSCode starts any new session. The extension SHALL also support VSCode's built-in Restart toolbar button by having the factory rebuild both the adb forward AND the JVM in `createDebugAdapterDescriptor` when it sees the tracked JVM has died; the JVM is told `--jdwp-port=<new>` on its command line so it ignores the stale `__jdwpPort` from the DAP `attach` request and connects to the freshly-installed forward instead.

#### Scenario: Normal session end
- **WHEN** user clicks VSCode's Stop button or the session ends for any reason
- **THEN** `onDidTerminateDebugSession` reads `__jdwpPort` from the session's configuration
- **AND** calls `removeForward(__jdwpPort)` on the shared `AdbJdwpController`
- **AND** calls `factory.killActive()` which `kill()`s the tracked JVM `ChildProcess` and clears `currentJdwpPort` (so the factory no longer believes any forward is alive)
- **AND** the next attach starts from a clean slate: brand-new DAP port, brand-new JDWP port, brand-new JVM process, brand-new adb forward

#### Scenario: VSCode-native Restart works via factory rebuild
- **WHEN** user clicks VSCode's debug toolbar Restart button while an Android debug session is active
- **THEN** VSCode sends `disconnect` (the JVM-side DapServer shuts down its client socket and the tracked JVM is killed by `onDidTerminate`), then starts a new session with a copy of the original configuration (including `__jdwpPort` and `__adapterPort`)
- **AND** the new session asks the factory for a descriptor; the factory reads `__adapterPort`, checks `isProcAlive` on the tracked JVM
- **AND** since the JVM was already killed on terminate, `isProcAlive` returns false
- **AND** the factory calls `spawnFreshAdapter()` which: (a) allocates a NEW JDWP port via `new AdbJdwpController(runner).forwardJdwp(pid)`, (b) allocates a NEW DAP port via `allocateFreePort()`, (c) spawns a fresh JVM with `--port=<newDap> --jdwp-port=<newJdwp> --index-file=...`, (d) waits for the JVM to print `READY`
- **AND** the factory returns `DebugAdapterServer(newDap)` to VSCode
- **AND** VSCode connects to the new DAP port; the JVM accepts; VSCode fires `attach` (carrying the OLD `__jdwpPort` in its `port` field)
- **AND** the JVM-side `DapServer.handleAttach` reads `args["port"]` and falls back to the `jdwpPortOverride` constructor parameter (set from `--jdwp-port`) — since the override differs, it logs the override and connects to the FRESH JDWP port instead
- **AND** the new session reaches the device's JDWP socket and debugging continues normally

#### Scenario: VSCode is force-quit mid-session
- **WHEN** the user closes VSCode without properly terminating the debug session (process kill, OS shutdown)
- **THEN** the `onDidTerminateDebugSession` hook never fires
- **AND** the extension's `deactivate()` calls `factory.killActive()` (kills any tracked JVM) and `jdwp.cleanupAllForwards()` (removes every tracked adb forward)
- **AND** any adb forward left over on the device (force-quit where `deactivate` may also not run) persists until the user runs `adb kill-server`; documented as Open Question 7

### Requirement: Custom Restart Command

The extension SHALL register a command `debugger-for-android.restartSession` (label: "Android: Restart Debug Session") that re-attaches to the same target PID with a brand-new forward, JVM, and DAP port. The command SHALL be visible in the command palette only when the active debug session is of type `android-java` (gated by `when: "debugType == 'android-java'"` in `package.json`).

#### Scenario: Restart command with active Android session
- **WHEN** user invokes "Android: Restart Debug Session" while an Android debug session is active
- **THEN** the command reads `__pid` from the active session's configuration
- **AND** calls `vscode.debug.stopDebugging(session)` — the atomic-teardown hook fires and tears down JVM + forward
- **AND** then calls `runAttachToPidCommand(statusBar, deps, pid)` which walks the full attach flow: pre-flight checks (adb/JDK/jar), build a new `AdbJdwpController`, fresh `forwardJdwp`, fresh JVM spawn, fresh `startDebugging`
- **AND** the new session's `__adapterPort`, `__jdwpPort`, and `__pid` are all newly allocated

#### Scenario: Restart command with no active session
- **WHEN** user invokes "Android: Restart Debug Session" with no active debug session, or with an active session of a different type
- **THEN** the command shows a warning: "No active Android debug session to restart"
- **AND** does not throw or attempt any teardown

#### Scenario: Restart command loses `__pid` from an old session
- **WHEN** the active session was started by an older extension version that did not stamp `__pid` into the configuration
- **THEN** the command shows a warning: "Session is missing target PID; was started by an older extension version"
- **AND** does not invoke `runAttachToPidCommand`

### Requirement: Shared AdbJdwpController (forward tracking)

The `AdbJdwpController` instance SHALL be shared across `attach.ts` (which calls `forwardJdwp` / `removeForward`), the factory's `spawnFreshAdapter` (which constructs a fresh `tmpController` on every toolbar Restart and registers it via `setJdwpController`), and `extension.local.ts` (which calls `removeForward` from `onDidTerminateDebugSession` and `cleanupAllForwards` from `deactivate`). Sharing SHALL be implemented as a module-level singleton (`extension/src/debug/jdwpRegistry.ts`) holding the last-constructed instance, because each instance's `activeForwards` Set is the source of truth for which adb forwards are currently open — if every attach creates a new controller and discards it, the in-memory set becomes empty even though adb still has the forwards bound.

The factory SHALL additionally track the LATEST JDWP port (`factory.getJdwpPort()`, exposed as `currentJdwpPort`) as the authoritative source for "which port's forward needs to be torn down on session end". `onDidTerminateDebugSession` MUST read `factory.getJdwpPort()` (NOT `session.configuration.__jdwpPort`) because VSCode's toolbar Restart clones the FIRST session's configuration for every subsequent restart — `__jdwpPort` in the session config is therefore stale (points at the original attach's port) on the 2nd+ restart. Trusting the session config would silently no-op `removeForward` (short-circuiting on the `activeForwards.has(port)` guard) and LEAK the forward across every restart after the first. The session config's `__jdwpPort` SHALL only be used as a fallback for the rare case where the factory is blind (extension reload between sessions).

#### Scenario: Forward tracked across attach / terminate / restart
- **WHEN** `runAttachCommand` constructs a new `AdbJdwpController` and calls `forwardJdwp(pid)` (allocates port `LP` and runs `adb forward tcp:LP jdwp:<pid>`)
- **THEN** the controller is registered via `setJdwpController(controller)` in `jdwpRegistry`
- **AND** when `onDidTerminateDebugSession` fires, `extension.local.ts` calls `getJdwpController().removeForward(LP)` — the SAME instance the attach command used, so `activeForwards` still has `LP` in it
- **AND** the `adb forward --remove tcp:LP` invocation succeeds
- **AND** without the singleton, the controller instance from `attach.ts` would be GC'd before terminate runs, leaving an unrecoverable forward on the device

#### Scenario: Forward tracked across toolbar Restart (factory rebuild)
- **WHEN** the toolbar Restart button fires, VSCode ends the current session, then starts a new session using the OLD configuration
- **AND** `onDidTerminateDebugSession` reads the JDWP port from `factory.getJdwpPort()` (the factory's tracked port, which is the port that was JUST torn down) and calls `getJdwpController().removeForward(port)` to clean up the OLD forward
- **THEN** the factory's `createDebugAdapterDescriptor` is called for the new session. Because the tracked JVM is dead, it calls `spawnFreshAdapter` which:
  1. Constructs a fresh `tmpController = new AdbJdwpController(restartRunner)`
  2. Calls `tmpController.forwardJdwp(pid)` to allocate port `LP2` and run `adb forward tcp:LP2 jdwp:<pid>`
  3. Calls `setJdwpController(tmpController)` to register the NEW controller with the registry — **this step is required** so the next `onDidTerminateDebugSession` finds a controller whose `activeForwards` includes `LP2`. Without it, the registry would still point at the attach-time controller (whose `activeForwards` doesn't include `LP2`); `removeForward(LP2)` would short-circuit on the `activeForwards.has(port)` guard and silently no-op, leaving `LP2` forwarded on the device across every subsequent restart.
  4. Spawns a fresh JVM with `--jdwp-port=<LP2>` and returns the new DAP port
  5. Updates `factory.currentJdwpPort = LP2` (the factory tracks the LATEST JDWP port — never the one in the session config; see next scenario)
- **AND** the next `onDidTerminateDebugSession` (e.g. on the SECOND toolbar Restart) reads `factory.getJdwpPort() = LP2` and calls `getJdwpController().removeForward(LP2)` — `tmpController.activeForwards` includes `LP2`, so `adb forward --remove` runs and the forward is cleaned up correctly

#### Scenario: 2nd+ toolbar Restart uses factory-tracked JDWP port (NOT session config)
- **WHEN** the toolbar Restart button is clicked a SECOND (or later) time on the same debug session
- **AND** VSCode clones the FIRST session's configuration for the new session — so the new session's `session.configuration.__jdwpPort` is still the original attach-time port (e.g. `LP1`), NOT the latest restart-allocated port (`LP2`)
- **THEN** `onDidTerminateDebugSession` MUST read the JDWP port from `factory.getJdwpPort()` (which holds `LP2`, the LATEST spawnFreshAdapter allocation), not from `session.configuration.__jdwpPort` (which holds the stale `LP1`)
- **AND** the `factory.killActive()` call happens AFTER `factory.getJdwpPort()` because `killActive` clears the tracked port
- **AND** `getJdwpController().removeForward(LP2)` succeeds because the registered `tmpController.activeForwards` includes `LP2`
- **AND** if the extension had instead trusted `session.configuration.__jdwpPort = LP1`, then `removeForward(LP1)` would short-circuit on `tmpController.activeForwards.has(LP1) === false` and silently no-op — leaving `LP2` (and `LP3`, `LP4`, … on subsequent restarts) forwarded on the device forever
- **AND** `session.configuration.__jdwpPort` SHALL be treated as a fallback for the rare case where the factory is blind (e.g. extension reload between sessions) — not as the primary source

#### Scenario: Extension deactivated mid-session
- **WHEN** VSCode is reloading the extension or shutting down while a session is active
- **THEN** `deactivate()` reads the registered controller from `jdwpRegistry`
- **AND** calls `cleanupAllForwards()` which iterates `activeForwards` and runs `adb forward --remove tcp:<port>` for each, fire-and-forget
- **AND** even if `onDidTerminateDebugSession` never fired (force-quit), no forwards leak

### Requirement: Configurable Binary Paths

The extension SHALL expose two VSCode configuration keys for pointing at the adb and JDK binaries: `debugger-for-android.adbPath` (default `"auto"`) and `debugger-for-android.javaPath` (default `"auto"`). When the value is non-`"auto"` and the file exists, that path SHALL be used. When the file is missing, the extension SHALL throw a clear error rather than silently fall back. When the value is `"auto"`, the extension SHALL run a deterministic discovery ladder.

#### Scenario: User-configured adbPath
- **WHEN** user sets `debugger-for-android.adbPath` to `/opt/sdk/platform-tools/adb`
- **AND** the file exists
- **THEN** `findAdb` returns `{ adbPath: '/opt/sdk/platform-tools/adb', source: 'config' }`
- **AND** logs `adb path (configured): /opt/sdk/platform-tools/adb`

#### Scenario: User-configured adbPath points at a missing file
- **WHEN** user sets `debugger-for-android.adbPath` to a non-existent path
- **THEN** `findAdb` throws "Configured adb path '<path>' does not exist; update `debugger-for-android.adbPath` or set to 'auto' for auto-detection"
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
- **THEN** `findAdbAuto` throws "adb binary not found. Set `debugger-for-android.adbPath` to the absolute path of adb, or add it to $PATH"
- **AND** the attach command surfaces this error before any device interaction

#### Scenario: User-configured javaPath
- **WHEN** user sets `debugger-for-android.javaPath` to `/opt/jdk/bin/java`
- **AND** the file exists
- **THEN** `detectJdk` runs `java -version` against that path
- **AND** returns `{ javaPath, version }` when version >= 8
- **AND** logs `JDK detected: <path> (version <v>)`

#### Scenario: User-configured javaPath missing
- **WHEN** user sets `debugger-for-android.javaPath` to a non-existent path
- **THEN** `resolveJavaCandidates` throws "Configured java path '<path>' does not exist; update `debugger-for-android.javaPath` or set to 'auto'"

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
- **AND** instead returns null and the caller surfaces a clear error: "JDK 8+ is required on the local machine. Set `debugger-for-android.javaPath` or set $JAVA_HOME / add java to $PATH"
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

### Requirement: Adapter JVM Lifecycle Owned by Factory

The `AndroidJavaFactory` SHALL own the `ChildProcess` reference for the active adapter JVM AND the JDWP port whose adb forward is bound to the current session. It SHALL NOT TCP-probe the adapter port to decide liveness — the adapter JVM listens on a single `accept()` slot per session, so a probe would steal that slot from VSCode's real DAP client. Instead, the factory SHALL check `proc.exitCode === null && proc.signalCode === null` (no `proc.kill` happened, no natural exit) via its own tracked `ChildProcess`. The factory SHALL also be able to rebuild a fresh JVM AND a fresh adb forward from `setRestartContext` so the toolbar Restart path works without going through `attach.ts` again.

#### Scenario: Same-port session reuse
- **WHEN** VSCode asks the factory for a descriptor for a new session whose `__adapterPort` equals `factory.currentPort`
- **AND** `isProcAlive(adapterProc)` returns true
- **THEN** the factory returns `new vscode.DebugAdapterServer(cfgPort)` — VSCode connects to the existing JVM
- **AND** the JVM's accept loop (Decision 16) accepts the new DAP client

#### Scenario: Tracked JVM is dead, restart context present (toolbar Restart path)
- **WHEN** `isProcAlive` returns false (JVM was killed by `onDidTerminate` on a prior session)
- **AND** `javaPath`, `jarPath`, `entries`, `runner`, and `pid` were captured via `setRestartContext` on the prior attach
- **THEN** the factory calls `spawnFreshAdapter` which:
  1. Allocates a NEW JDWP port via `new AdbJdwpController(runner).forwardJdwp(pid)` (the runner + pid came from `setRestartContext`)
  2. Allocates a NEW DAP port via `allocateFreePort()`
  3. Spawns a new JVM via `AdapterLauncher.launch({javaPath, jarPath, args: ['--port=<newDap>', '--jdwp-port=<newJdwp>', '--index-file=<file>']})` — the `--jdwp-port` CLI override tells the JVM to ignore the stale `__jdwpPort` from the DAP `attach` request and connect to the freshly-installed forward instead
  4. Waits for "READY" on stderr within 10s
- **AND** calls `setActiveAdapter(newProc, newDap, newJdwp)` to register the new JVM + new JDWP port
- **AND** returns `new vscode.DebugAdapterServer(newDap)` to VSCode
- **AND** VSCode connects; the JVM accepts; VSCode sends `attach` (carrying the OLD `__jdwpPort` in its `port` field); `DapServer.handleAttach` reads `args["port"]` and falls back to the `jdwpPortOverride` from `--jdwp-port` — since the override differs, the JVM logs the override and connects to `localhost:<newJdwp>` → forward → JDWP socket. Success.

#### Scenario: No restart context
- **WHEN** the factory has no `javaPath`/`jarPath`/`runner`/`pid` (e.g. extension was reloaded mid-session, `setRestartContext` was never called)
- **THEN** `createDebugAdapterDescriptor` throws "Cannot restart: missing JVM spawn context. Run `Android Java Debugger: Attach to Process` once before restarting"
- **AND** VSCode shows the error in the debug toolbar

### Requirement: Adapter Accepts Multiple Sessions

The adapter JVM SHALL loop on `ServerSocket.accept()` and serve a fresh DapServer on every accepted client, rather than exiting after the first disconnect. Each accepted client SHALL run on its own daemon thread with its own request-handling executor.

#### Scenario: VSCode disconnects and reconnects to the same JVM
- **WHEN** VSCode's native Restart sends `disconnect`, the DapServer shuts down and releases the client socket
- **AND** the accept loop continues to block on `serverSocket.accept()`
- **AND** when VSCode's new DAP client connects, the JVM accepts it and starts a new DapServer on a new thread
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

### Requirement: Index Temp File Hygiene

The extension SHALL write its source-index temp files to a single dedicated directory `${os.tmpdir()}/d4a-index/` (no nested per-process subdirs). File names SHALL be unique via `index-<process.pid>-<Date.now()>-<random6>.json`. The adapter is expected to delete the file after reading it (see adapter-side `loadInitialIndex`), so the file's lifetime is the time between the extension's `writeIndexFile` and the adapter's read. The extension SHALL additionally sweep stale files (older than 24h) at every activation.

#### Scenario: Multiple parallel attachments don't collide
- **WHEN** two attach commands run in the same VSCode window (rare but possible)
- **AND** each calls `writeIndexFile(entries)`
- **THEN** both files land in `${tmpdir}/d4a-index/` with different random suffixes — no collision
- **AND** no nested `pid-${pid}/` subdirs (which were used in the previous design and made cleanup awkward)

#### Scenario: Previous session crashed and left files
- **WHEN** the extension activates and `${tmpdir}/d4a-index/` contains files older than 24h (e.g. a prior adapter crashed before its `finally` could delete)
- **THEN** `sweepStaleIndexFiles()` deletes them
- **AND** the activation proceeds normally
- **AND** the sweep is best-effort: any per-file error (EACCES, ENOENT mid-sweep) is silently ignored

#### Scenario: Attach fails between `writeIndexFile` and JVM start
- **WHEN** the JVM fails to start (e.g. invalid `javaPath`) after the index file was already written
- **THEN** the `cleanup()` function in `attach.ts` calls `fs.promises.unlink(indexFile).catch(...)` to remove the temp file
- **AND** no temp file leaks

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
