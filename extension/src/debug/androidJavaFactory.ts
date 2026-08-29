// extension/src/debug/androidJavaFactory.ts
import * as vscode from 'vscode';
import { ChildProcess } from 'child_process';
import { Logger } from '../util/logger';
import { FqnEntry } from '../source/indexer';
import { AdapterLauncher } from './adapterLauncher';
import { allocateFreePort } from './portAllocator';
import { t } from '../i18n/strings';
import { writeIndexFile } from '../util/indexFile';
import { AdbRunner } from '../adb/runner';
import { AdbJdwpController } from '../adb/jdwp';
import { setJdwpController } from './jdwpRegistry';

/**
 * Local-ext half of the debug adapter descriptor factory. Owns the
 * lifecycle of the Java adapter JVM(s) AND pairs every JVM with the
 * adb JDWP forward it connects through. Tells VSCode how to reach
 * the adapter.
 *
 * Lifecycle (atomic teardown):
 *
 * - On session start the extension's `attach.ts` allocates a JDWP
 *   port, runs `adb forward tcp:<port> jdwp:<pid>`, spawns the JVM
 *   on a fresh DAP port, and calls `factory.setActiveAdapter(proc,
 *   dapPort, jdwpPort)`. The factory remembers all three. It then
 *   calls `vscode.debug.startDebugging` with both ports stamped into
 *   the configuration as `__adapterPort` and `__jdwpPort`.
 *
 * - On session terminate `extension.local.ts` reads the JDWP port
 *   from the factory's `getJdwpPort()` (NOT from the session config —
 *   see below), calls `removeForward(port)` on the shared
 *   `AdbJdwpController`, and then `factory.killActive()`. The JVM
 *   dies and the forward is gone — full atomic teardown, both ends.
 *
 *   The session config's `__jdwpPort` is NOT used as the source of
 *   truth here because VSCode's toolbar Restart clones the FIRST
 *   session's configuration for every subsequent restart, so on the
 *   2nd+ restart `__jdwpPort` is stale (it still points at the
 *   original attach's port). Reading the factory's tracked port
 *   always gives us the LATEST port whose forward is alive in the
 *   registry's `activeForwards`.
 *
 * Restart (toolbar button OR custom command):
 *
 * VSCode restarts the session using a copy of the original
 * configuration, so the new session's `__adapterPort` and
 * `__jdwpPort` are the OLD values (no longer valid). The factory
 * detects this in `createDebugAdapterDescriptor`:
 *
 *   - If the tracked JVM is alive AND `__adapterPort` matches the
 *     tracked DAP port → reuse. (This is the common case for an
 *     in-progress session; restart doesn't hit this branch because
 *     `onDidTerminate` already killed the JVM before us.)
 *
 *   - If the tracked JVM is dead → call `spawnFreshAdapter()`, which
 *     allocates a NEW JDWP port + sets up a NEW adb forward + spawns
 *     a fresh JVM with `--jdwp-port=<new_port>` (so the JVM ignores
 *     the stale `__jdwpPort` from the DAP attach request) + returns
 *     a fresh DAP port. The old forward is gone (atomic teardown
 *     fired), the new forward is at the new port, the new JVM uses
 *     the new port.
 *
 * Why the JVM needs `--jdwp-port`: VSCode's `attach` request carries
 * the OLD `__jdwpPort` in its `port` field. If the JVM blindly
 * called `SocketAttach` against that, it would ECONNREFUSED (the
 * forward at that port was just torn down). The CLI override lets the
 * JVM pick the right port regardless of what VSCode sends.
 *
 * Liveness check:
 *
 * We deliberately do NOT TCP-probe the DAP port to decide whether
 * the JVM is alive. Probing would call `accept()` on the JVM and
 * steal that one-shot connection slot from VSCode's DAP client.
 * Instead we track the `ChildProcess` directly via `setActiveAdapter`
 * and check its lifecycle state (exit code / signal).
 */
export class AndroidJavaFactory implements vscode.DebugAdapterDescriptorFactory {
  private adapterProc: ChildProcess | undefined;
  private currentPort: number | undefined;
  /**
   * The JDWP port the last `setActiveAdapter` recorded. After
   * `killActive()` this is cleared (atomic teardown) — the factory
   * no longer believes any forward is alive at this port. The next
   * session that starts must allocate its own JDWP port + forward.
   */
  private currentJdwpPort: number | undefined;
  private javaPath: string | undefined;
  private jarPath: string | undefined;
  private sourceIndexEntries: FqnEntry[] = [];
  /**
   * Captured by `setRestartContext` so the factory can rebuild a
   * session from scratch in `spawnFreshAdapter` (used for toolbar
   * Restart when no fresh `runAttachCommand` / `runAttachToPidCommand`
   * has been called).
   */
  private restartRunner: AdbRunner | undefined;
  private restartPid: number | undefined;

  /**
   * Register a freshly-launched JVM as the active adapter, paired with
   * the adb forward at `jdwpPort`. Replaces any prior registration,
   * killing the prior JVM first to avoid leaked processes if the user
   * runs attach twice without restarting.
   *
   * IMPORTANT: this does NOT remove any prior adb forward — that is
   * `extension.local.ts#onDidTerminateDebugSession`'s job (atomic
   * teardown). By the time `setActiveAdapter` is called from the
   * attach flow, the previous session has already been torn down.
   */
  setActiveAdapter(proc: ChildProcess, port: number, jdwpPort: number): void {
    Logger.info(
      `[ui-factory] setActiveAdapter(dapPort=${port}, jdwpPort=${jdwpPort}, pid=${proc.pid ?? '?'})`
    );
    this.killActive();
    this.adapterProc = proc;
    this.currentPort = port;
    this.currentJdwpPort = jdwpPort;
  }

  /** Returns the DAP port of the currently-tracked JVM, if any. */
  getPort(): number | undefined {
    return this.currentPort;
  }

  /**
   * Returns the JDWP port of the live adb forward the factory currently
   * believes is alive, if any.
   *
   * This is the AUTHORITATIVE source for "which adb forward needs to
   * be torn down on session end". Callers (notably
   * `extension.local.ts#onDidTerminateDebugSession`) MUST read it
   * BEFORE `killActive()` — `killActive` clears it.
   *
   * Why factory-tracked and not `session.configuration.__jdwpPort`:
   * VSCode's toolbar Restart clones the FIRST session's configuration
   * for every subsequent restart. So on the 2nd+ restart the
   * `__jdwpPort` in the session config is stale — it still points at
   * the original `attach` command's port, not the latest
   * `spawnFreshAdapter` allocation. Reading from the factory sidesteps
   * that; the factory always tracks the actual current port.
   */
  getJdwpPort(): number | undefined {
    return this.currentJdwpPort;
  }

  /**
   * Kill the tracked JVM AND clear the JDWP-port tracking. Idempotent.
   *
   * Why both: this is the JVM-side of the atomic teardown. The
   * forward-side teardown (running `adb forward --remove`) is wired
   * into `extension.local.ts#onDidTerminateDebugSession` — it's done
   * there because that handler has the live `DebugSession` and can
   * read `__jdwpPort` from it. By clearing `currentJdwpPort` here we
   * also make sure the factory doesn't accidentally tell the next
   * `spawnFreshAdapter` that the old forward is still alive.
   */
  killActive(): void {
    if (this.adapterProc) {
      const proc = this.adapterProc;
      Logger.info(`[ui-factory] killActive pid=${proc.pid ?? '?'}`);
      try {
        proc.kill();
      } catch (e) {
        Logger.warn(`[ui-factory] killActive threw: ${(e as Error).message}`);
      }
      this.adapterProc = undefined;
    }
    this.currentPort = undefined;
    this.currentJdwpPort = undefined;
  }

  /** Legacy no-op kept for compatibility. */
  clearPort(): void {
    Logger.info(`[ui-factory] clearPort (no-op)`);
  }

  /** Legacy no-op kept for compatibility. */
  setPort(port: number): void {
    Logger.info(`[ui-factory] setPort(${port}) (no-op — use setActiveAdapter instead)`);
  }

  /**
   * Capture everything we need to spawn a fresh JVM AND a fresh adb
   * forward for the restart path. Called by `attach.ts` after the
   * forward has been set up.
   *
   * `runner` and `pid` are stored so `spawnFreshAdapter` (called on
   * toolbar Restart) can build a new `AdbJdwpController` against the
   * same adb runner and run `adb forward` again, even though the
   * attach.ts call site is long gone by the time the new session
   * arrives at `createDebugAdapterDescriptor`.
   */
  setRestartContext(ctx: {
    javaPath: string;
    jarPath: string;
    entries: FqnEntry[];
    runner: AdbRunner;
    pid: number;
  }): void {
    Logger.info(
      `[ui-factory] setRestartContext(javaPath=${ctx.javaPath}, jarPath=${ctx.jarPath}, entries=${ctx.entries.length}, pid=${ctx.pid})`
    );
    this.javaPath = ctx.javaPath;
    this.jarPath = ctx.jarPath;
    this.sourceIndexEntries = ctx.entries;
    this.restartRunner = ctx.runner;
    this.restartPid = ctx.pid;
  }

  async createDebugAdapterDescriptor(
    session: vscode.DebugSession
  ): Promise<vscode.DebugAdapterDescriptor> {
    Logger.info(
      `[ui-factory] createDebugAdapterDescriptor called (extensionKind=${vscode.ExtensionKind.UI})`
    );
    const cfg = session.configuration as Record<string, unknown>;
    const cfgPort = cfg.__adapterPort;
    if (typeof cfgPort !== 'number') {
      Logger.error(
        `[ui-factory] no __adapterPort in session config (have keys: ${Object.keys(cfg).join(',')})`
      );
      throw new Error('No __adapterPort in session configuration');
    }

    // Reuse the tracked JVM if it's still alive. Do NOT TCP-probe the
    // port — that would steal an accept() slot from VSCode's DAP
    // client. We trust our own ChildProcess reference instead.
    if (
      this.adapterProc &&
      this.currentPort === cfgPort &&
      isProcAlive(this.adapterProc)
    ) {
      Logger.info(
        `[ui-factory] reusing live adapter at port ${cfgPort} (pid=${this.adapterProc.pid ?? '?'})`
      );
      return new vscode.DebugAdapterServer(cfgPort);
    }

    // Tracked JVM is dead (or never tracked — e.g. extension restarted
    // between sessions). VSCode is reusing the configuration from the
    // prior session, so the `__adapterPort` it asks for is whatever
    // the prior session used; we ignore that and hand back a fresh
    // port after spawning a fresh JVM (with a fresh adb forward — the
    // previous one was torn down by `onDidTerminate`). The fresh JVM
    // is told `--jdwp-port=<new>` so it ignores the stale `__jdwpPort`
    // VSCode will send in its `attach` request.
    if (!this.javaPath || !this.jarPath || !this.restartRunner || typeof this.restartPid !== 'number') {
      throw new Error(t.restartNoContext());
    }
    Logger.info(
      `[ui-factory] tracked adapter gone — spawning fresh adapter (was dapPort=${cfgPort}, jdwpPort=${cfg.__jdwpPort})`
    );
    const result = await this.spawnFreshAdapter();
    this.currentPort = result.dapPort;
    this.currentJdwpPort = result.jdwpPort;
    Logger.info(
      `[ui-factory] new adapter READY at dap=${result.dapPort} jdwp=${result.jdwpPort} (pid=${this.adapterProc?.pid ?? '?'})`
    );
    return new vscode.DebugAdapterServer(result.dapPort);
  }

  /**
   * Spawn a brand-new JVM AND set up a brand-new adb forward. Used by
   * `createDebugAdapterDescriptor` on the restart path when no JVM is
   * available to reuse.
   *
   * Returns both ports because the caller (the factory itself) needs
   * to track the JDWP port and the descriptor only needs the DAP port.
   *
   * IMPORTANT: the adb forward is set up via a freshly-constructed
   * `AdbJdwpController` — and we register that controller with the
   * shared `jdwpRegistry` so `extension.local.ts#onDidTerminateDebugSession`
   * can find it on the next terminate and actually `removeForward` the
   * port. Without the registration, the registry keeps pointing at
   * the controller from the original `runAttachCommand` (whose
   * `activeForwards` doesn't include this port), and the next
   * terminate would silently no-op in `removeForward`'s
   * `activeForwards.has(port)` guard, leaking the forward on the
   * device forever.
   */
  private async spawnFreshAdapter(): Promise<{ dapPort: number; jdwpPort: number }> {
    if (!this.javaPath || !this.jarPath || !this.restartRunner || typeof this.restartPid !== 'number') {
      throw new Error(t.restartNoContext());
    }
    // Kill any stale tracked JVM first; it is about to be replaced.
    this.killActive();

    // Allocate a NEW JDWP port and set up a NEW adb forward. The
    // previous forward (if any) was removed by `onDidTerminate` —
    // we're not racing that handler because `createDebugAdapterDescriptor`
    // is called AFTER terminate completes (the session has already
    // been torn down by VSCode before the new one is started).
    const tmpController = new AdbJdwpController(this.restartRunner);
    const jdwpPort = await tmpController.forwardJdwp(this.restartPid);
    Logger.info(`[ui-factory] restart allocated new JDWP port ${jdwpPort}`);

    // Register the new controller so the next `onDidTerminateDebugSession`
    // can find it via `getJdwpController()` and actually run
    // `adb forward --remove tcp:<jdwpPort>`. Without this the forward
    // would leak — see the comment above.
    setJdwpController(tmpController);

    const dapPort = await allocateFreePort();
    const launcher = new AdapterLauncher();
    const args: string[] = [`--port=${dapPort}`, `--jdwp-port=${jdwpPort}`];
    if (this.sourceIndexEntries.length > 0) {
      args.push(`--index-file=${writeIndexFile(this.sourceIndexEntries)}`);
    }
    const proc = launcher.launch({
      javaPath: this.javaPath,
      jarPath: this.jarPath,
      args,
    });
    await waitForAdapterReady(proc, 10_000);
    this.adapterProc = proc;
    return { dapPort, jdwpPort };
  }
}

function isProcAlive(proc: ChildProcess): boolean {
  return proc.exitCode === null && proc.signalCode === null;
}

function waitForAdapterReady(
  proc: ChildProcess,
  timeoutMs: number
): Promise<void> {
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

    const timer = setTimeout(
      () => finish(new Error(t.errAdapterNotReadyTimeout(timeoutMs))),
      timeoutMs
    );

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
