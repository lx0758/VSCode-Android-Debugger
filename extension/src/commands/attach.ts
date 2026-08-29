// extension/src/commands/attach.ts
import * as vscode from 'vscode';
import * as fs from 'fs';
import { Logger } from '../util/logger';
import { getConfig } from '../util/config';
import { findAdb } from '../adb/path';
import { DefaultAdbRunner, AdbRunner } from '../adb/runner';
import { AdbJdwpController } from '../adb/jdwp';
import { pickProcess } from '../ui/processPicker';
import { AdapterLauncher } from '../debug/adapterLauncher';
import { detectJdk, getJdkErrorMessage } from '../util/jdk';
import { StatusBarManager } from '../ui/statusBar';
import { t } from '../i18n/strings';
import { AndroidJavaFactory } from '../debug/androidJavaFactory';
import { allocateFreePort } from '../debug/portAllocator';
import { ChildProcess } from 'child_process';
import { SourceDirsManager } from '../source/dirs';
import { FqnEntry } from '../source/indexer';
import { writeIndexFile } from '../util/indexFile';
import { setJdwpController } from '../debug/jdwpRegistry';

export interface AttachCommandDeps {
  factory: AndroidJavaFactory;
  dirsManager: SourceDirsManager;
  /**
   * Absolute path to this extension's installation directory (i.e.
   * `context.extensionPath` from the UI-host activation). Used by
   * `prepareAttachContext` to resolve `resources/adapter.jar` without
   * having to look up the extension via `vscode.extensions.getExtension` —
   * that lookup is fragile because the publisher id is hard-coded in
   * `package.json` and may be customised by downstream packagers (or
   * diverge from the running build's id), which would make the lookup
   * silently fall back to `__dirname` (the compiled `out/commands/`
   * folder, which has no `resources/adapter.jar`).
   */
  extensionPath: string;
  /**
   * Pre-builds the source index from the marked source dirs. Must be invoked
   * BEFORE `vscode.debug.startDebugging`, so the result can be passed to the
   * adapter via a temp file and be available for VSCode's automatic
   * `setBreakpoints` request (which fires during session init, before our
   * `onDidStartDebugSession` extension hook gets a chance to run).
   */
  buildIndex: (dirs: string[]) => Promise<FqnEntry[]>;
}

export interface JdkAndJar {
  jdk: { javaPath: string; version: number };
  jarPath: string;
  adbResult: { adbPath: string };
  runner: AdbRunner;
}

/**
 * Full attach flow: prompts the user, runs every step.
 *
 * The bulk of the work lives in `attachToProcess` below. This wrapper
 * just adds the user-facing parts: pre-flight checks (adb/java/jar),
 * listing devices and processes, and showing the QuickPick picker.
 */
export async function runAttachCommand(
  statusBar: StatusBarManager,
  deps: AttachCommandDeps
): Promise<void> {
  statusBar.setState('attaching', t.attachPreparing());

  let jdwpController: AdbJdwpController | undefined;
  let jdwpPort: number | undefined;
  let adapterProc: ChildProcess | undefined;
  let indexFile: string | undefined;

  try {
    const ctx = await prepareAttachContext(statusBar, deps.extensionPath);

    // === Device-side checks (require a running adb) ===
    jdwpController = new AdbJdwpController(ctx.runner);
    setJdwpController(jdwpController);

    statusBar.setState('attaching', t.attachCheckingDevices());
    const devices = await jdwpController.listDevices();
    if (devices.length === 0) {
      throw new Error(t.noDevices());
    }
    const readyDevices = devices.filter((d) => d.state === 'device');
    if (readyDevices.length === 0) {
      throw new Error(t.devicesNotReady(devices.map((d) => `${d.serial}=${d.state}`).join(', ')));
    }

    statusBar.setState('attaching', t.attachEnumerating());
    const processes = await jdwpController.listJdwpProcesses();
    if (processes.length === 0) {
      throw new Error(t.noJdwpProcesses());
    }

    const pick = await pickProcess(processes);
    if (!pick) {
      statusBar.setState('idle');
      return;
    }

    await attachToProcess({
      statusBar,
      deps,
      ctx,
      jdwpController,
      pick,
      onCleanup: (p) => { jdwpPort = p; },
      onAdapterProc: (p) => { adapterProc = p; },
      onIndexFile: (f) => { indexFile = f; },
    });
  } catch (e) {
    handleAttachError(e, statusBar);
    cleanup(jdwpPort, adapterProc, deps.factory, indexFile, jdwpController);
  }
}

/**
 * Restart an existing session against the same target PID, doing a
 * FULL re-attach from `forwardJdwp` onward. This is what the user
 * expects from "Restart" — a clean re-handshake, not a
 * connect-the-same-socket reconnect. Skips:
 *
 *   - JDK/adb/jar pre-flight (already validated on first attach)
 *   - `listDevices` / `listJdwpProcesses` (we already know the PID)
 *   - `pickProcess` (target is fixed)
 *
 * The previous session's JVM and adb forward have already been torn
 * down by `onDidTerminateDebugSession` (atomic teardown policy), so
 * this function builds a brand-new forward and a brand-new JVM from
 * scratch. The new session's `__jdwpPort` and `__adapterPort` are
 * freshly allocated and stamped into its configuration.
 */
export async function runAttachToPidCommand(
  statusBar: StatusBarManager,
  deps: AttachCommandDeps,
  pid: number,
): Promise<void> {
  statusBar.setState('attaching', t.attachPreparing());

  let jdwpController: AdbJdwpController | undefined;
  let jdwpPort: number | undefined;
  let adapterProc: ChildProcess | undefined;
  let indexFile: string | undefined;

  try {
    const ctx = await prepareAttachContext(statusBar, deps.extensionPath);

    jdwpController = new AdbJdwpController(ctx.runner);
    setJdwpController(jdwpController);

    await attachToProcess({
      statusBar,
      deps,
      ctx,
      jdwpController,
      pick: { pid, name: `pid ${pid}` },
      onCleanup: (p) => { jdwpPort = p; },
      onAdapterProc: (p) => { adapterProc = p; },
      onIndexFile: (f) => { indexFile = f; },
    });
  } catch (e) {
    handleAttachError(e, statusBar);
    cleanup(jdwpPort, adapterProc, deps.factory, indexFile, jdwpController);
  }
}

/**
 * Resolve adb + JDK + adapter jar up front, before we talk to the
 * device or show any UI. These are all things that can fail on the
 * user's machine and have nothing to do with which Android process
 * they pick — surfacing them early avoids walking the user through
 * the device/process picker only to fail at the end.
 */
async function prepareAttachContext(statusBar: StatusBarManager, extensionPath: string): Promise<JdkAndJar> {
  const config = getConfig();

  Logger.info(`[attach] running adb locally`);
  const adbResult = findAdb(config.adbPath);
  Logger.info(`[attach] local adb path: ${adbResult.adbPath}`);
  const runner: AdbRunner = new DefaultAdbRunner(adbResult.adbPath);

  statusBar.setState('attaching', t.attachLaunchingAdapter());
  const jdk = await detectJdk(config.javaPath);
  if (!jdk) {
    throw new Error(getJdkErrorMessage());
  }

  // Resolve the bundled adapter jar against the extension installation
  // directory passed in by the caller (extension.local.ts#activate's
  // `context.extensionPath`). We deliberately do NOT use
  // `vscode.extensions.getExtension('<publisher>.<name>').extensionPath`
  // here — that lookup is fragile because the publisher id is configured
  // in `package.json` and may be customised by downstream packagers
  // (or may not match the running build's id), which would silently fall
  // back to `__dirname` (= the compiled `out/commands/` folder, which
  // has no `resources/adapter.jar`) and produce the misleading error
  // "找不到 Java adapter jar" with paths under `extension/out/...`.
  const adapterLauncher = new AdapterLauncher();
  const jarPath = adapterLauncher.resolveAdapterJar(extensionPath);

  return { adbResult, runner, jdk, jarPath };
}

async function handleAttachError(e: unknown, statusBar: StatusBarManager): Promise<void> {
  const msg = (e as Error).message;
  Logger.error(t.adapterAttachFailed(), e as Error);
  statusBar.setIndexerMode('unknown');
  statusBar.setState('error', msg.substring(0, 50));
  vscode.window.showErrorMessage(t.attachFail(msg));
}

interface AttachToProcessArgs {
  statusBar: StatusBarManager;
  deps: AttachCommandDeps;
  ctx: JdkAndJar;
  jdwpController: AdbJdwpController;
  pick: { pid: number; name: string };
  onCleanup: (jdwpPort: number) => void;
  onAdapterProc: (proc: ChildProcess) => void;
  onIndexFile: (file: string) => void;
}

/**
 * The shared post-PID part of the attach flow: forward JDWP, build the
 * source index, register restart context, spawn the JVM, fire
 * startDebugging. Called by both the full `runAttachCommand` and the
 * restart entry `runAttachToPidCommand`.
 */
async function attachToProcess(args: AttachToProcessArgs): Promise<void> {
  const { statusBar, deps, ctx, jdwpController, pick } = args;

  statusBar.setState('attaching', t.attachForwarding(pick.pid));
  const jdwpPort = await jdwpController.forwardJdwp(pick.pid);
  args.onCleanup(jdwpPort);

  // === Pre-build the source index BEFORE launching the adapter ===
  // VSCode sends `setBreakpoints` automatically during session init, which
  // arrives at the adapter BEFORE our `onDidStartDebugSession` extension
  // hook fires. If the adapter's sourceIndex is empty at that point, every
  // BP comes back as id=0, verified=false, and stays "unverified" forever
  // (VSCode never learns the adapter-side ids). So we scan the source dirs
  // up front and hand the entries to the adapter on a temp file.
  const sourceDirs = await deps.dirsManager.getAllDirs();
  const extraArgs: string[] = [];
  let indexEntries: FqnEntry[] = [];
  if (sourceDirs.length > 0) {
    Logger.info(`[attach] pre-indexing ${sourceDirs.length} source dir(s)`);
    statusBar.setState('attaching', t.attachIndexerFallback());
    indexEntries = await deps.buildIndex(sourceDirs);
    const file = writeIndexFile(indexEntries);
    args.onIndexFile(file);
    extraArgs.push(`--index-file=${file}`);
    Logger.info(`[attach] pre-index wrote ${indexEntries.length} entries to ${file}`);
  } else {
    Logger.info(`[attach] no marked source dirs, adapter will start with empty index`);
  }

  // Stash the JDK / jar / source-index / runner / pid context on the
  // factory so the factory can re-spawn a fresh JVM AND a fresh adb
  // forward if VSCode restarts the debug session via the toolbar
  // Restart button (which does NOT go through attach.ts). Without
  // the runner + pid, the factory has no way to set up a new
  // `adb forward tcp:<X> jdwp:<pid>` for the new session.
  deps.factory.setRestartContext({
    javaPath: ctx.jdk.javaPath,
    jarPath: ctx.jarPath,
    entries: indexEntries,
    runner: ctx.runner,
    pid: pick.pid,
  });

  const dapPort = await allocateFreePort();
  Logger.info(`[attach] adapter TCP port = ${dapPort}`);

  const adapterLauncher = new AdapterLauncher();
  // --jdwp-port tells the JVM which adb forward to connect through.
  // For a normal attach this matches what VSCode sends in the DAP
  // `attach` request anyway, but for a restart the DAP request
  // carries the OLD __jdwpPort and the JVM would otherwise
  // ECONNREFUSED on the torn-down forward.
  const adapterProc = adapterLauncher.launch({
    javaPath: ctx.jdk.javaPath,
    jarPath: ctx.jarPath,
    args: [`--port=${dapPort}`, `--jdwp-port=${jdwpPort}`, ...extraArgs],
  });
  args.onAdapterProc(adapterProc);

  await waitForAdapterReady(adapterProc, 10_000);

  // Register BOTH the JVM (DAP) port and the JDWP port on the factory.
  deps.factory.setActiveAdapter(adapterProc, dapPort, jdwpPort);

  const folder = vscode.workspace.workspaceFolders?.[0];
  const configObj: vscode.DebugConfiguration = {
    type: 'android-java',
    request: 'attach',
    name: `Android Debugger (PID ${pick.pid})`,
    hostName: 'localhost',
    port: jdwpPort,
  };
  (configObj as Record<string, unknown>).__adapterPort = dapPort;
  (configObj as Record<string, unknown>).__jdwpPort = jdwpPort;
  (configObj as Record<string, unknown>).__pid = pick.pid;
  (configObj as Record<string, unknown>).__maxFiles = getConfig().indexMaxFiles;
  (configObj as Record<string, unknown>).__preIndexedDirs = sourceDirs.length;

  Logger.info(`[attach] startDebugging config=${JSON.stringify(configObj)}`);
  Logger.info(`[attach] folder = ${folder?.uri.toString() ?? '<undefined>'}`);
  Logger.info(`[attach] vscode.env.remoteName = "${vscode.env.remoteName}"`);
  Logger.info(`[attach] await startDebugging...`);
  const started = await vscode.debug.startDebugging(folder, configObj);
  Logger.info(`[attach] startDebugging returned ${started}`);
  if (!started) {
    throw new Error(t.attachFail('startDebugging returned false'));
  }

  statusBar.setState('attached', `PID ${pick.pid} @ :${jdwpPort}`);
  Logger.info(t.attachSuccessLog(pick.pid, jdwpPort));
  vscode.window.showInformationMessage(t.attachSuccess(pick.pid));
}

async function waitForAdapterReady(proc: ChildProcess, timeoutMs: number): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    let done = false;
    const finish = (err?: Error) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      proc.stderr?.off('data', onChunk);
      proc.stdout?.off('data', onChunk);
      proc.off('exit', onExit);
      if (err) reject(err);
      else resolve();
    };

    const timer = setTimeout(() => finish(new Error(t.errAdapterNotReadyTimeout(timeoutMs))), timeoutMs);

    const onChunk = (chunk: Buffer | string) => {
      const text = typeof chunk === 'string' ? chunk : chunk.toString('utf-8');
      if (text.includes('READY')) finish();
    };
    proc.stderr?.on('data', onChunk);
    proc.stdout?.on('data', onChunk);

    const onExit = (code: number | null, signal: NodeJS.Signals | null) => {
      finish(new Error(t.errAdapterExitBeforeReady(code, signal)));
    };
    proc.on('exit', onExit);
  });
}

function cleanup(
  jdwpPort: number | undefined,
  adapterProc: ChildProcess | undefined,
  factory: AndroidJavaFactory,
  indexFile: string | undefined,
  jdwp: AdbJdwpController | undefined,
): void {
  factory.clearPort();
  try {
    adapterProc?.kill();
  } catch {
  }
  // Best-effort: if we already set up an adb forward before the
  // failure, tear it down. removeForward itself no-ops if the port
  // isn't in its tracking set, so passing it unconditionally here is
  // safe even though forwardJdwp may not have run yet.
  if (jdwp && jdwpPort !== undefined) {
    jdwp.removeForward(jdwpPort).catch(() => {});
  }
  // Best-effort: the adapter is also expected to delete the file after
  // reading it, but if attach failed before launching we still want to
  // avoid leaking temp files in $TMPDIR.
  if (indexFile) {
    fs.promises.unlink(indexFile).catch(() => {});
  }
}