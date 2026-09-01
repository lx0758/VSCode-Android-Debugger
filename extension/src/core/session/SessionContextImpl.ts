// extension/src/core/session/SessionContextImpl.ts
//
// Full per-session context. Owns:
//   - the Java Adapter JVM ChildProcess
//   - the session's AdbDevice (and its activeForwards set)
//   - the DAP port
//   - the JDWP port
//   - the DapServerProxy (wraps session.customRequest)
//
// `dispose()` releases everything exactly once. Idempotent.

import * as vscode from 'vscode';
import * as net from 'net';
import { spawn, ChildProcess } from 'child_process';
import { Logger } from '../../util/logger';
import { t } from '../../i18n/strings';
import { AdbRunner } from '../../adb/AdbRunner';
import { AdbDevice } from '../../adb/AdbDevice';
import { FqnEntry } from '../indexer/Indexer';
import { SessionContext, DeviceInfo, ProcessInfo } from './SessionContext';
import { DapServerProxy } from './DapServerProxy';

export interface SessionContextOptions {
  javaPath: string;
  jarPath: string;
  fqnInfo: FqnEntry[];
  adbRunner: AdbRunner;
}

/**
 * Re-export of `allocateFreePort` as a namespace member, so legacy
 * call sites (e.g. `SessionContextImpl.PortAllocator.allocateFreePort()`)
 * keep compiling. Slice 6 removes the legacy call sites in
 * `commands/attach.ts` and routes everything through `SessionManager`.
 */

export class SessionContextImpl implements SessionContext {
  readonly session: vscode.DebugSession;
  readonly device: DeviceInfo;
  readonly process: ProcessInfo;
  readonly dapPort: number;
  readonly jdwpPort: number;
  readonly dap: DapServerProxy;

  private readonly adbDevice: AdbDevice;
  private readonly adapterProc: ChildProcess;
  private disposed = false;

  private constructor(
    session: vscode.DebugSession,
    device: DeviceInfo,
    process: ProcessInfo,
    jdwpPort: number,
    adbDevice: AdbDevice,
    dapPort: number,
    adapterProc: ChildProcess
  ) {
    this.session = session;
    this.device = device;
    this.process = process;
    this.jdwpPort = jdwpPort;
    this.adbDevice = adbDevice;
    this.dapPort = dapPort;
    this.adapterProc = adapterProc;
    this.dap = new DapServerProxy(session);
  }

  /**
   * Build a fresh SessionContextImpl:
   *   1. Allocate a free DAP port.
   *   2. Construct a per-session AdbDevice and install an
   *      adb forward for the target pid.
   *   3. Spawn the Java Adapter JVM with --port=<dapPort>
   *      --jdwp-port=<jdwpPort> and wait until it prints READY.
   *
   * Throws on any step failure; the caller is responsible for the
   * cleanup of any partial state (e.g. an installed forward without a
   * JVM). `dispose()` is always safe to call, so wrapping callers can
   * simply `try { ... } catch (e) { ctx?.dispose(); throw e }`.
   */
  static async create(
    session: vscode.DebugSession,
    device: DeviceInfo,
    process: ProcessInfo,
    options: SessionContextOptions
  ): Promise<SessionContextImpl> {
    const dapPort = await allocateFreePort();
    const adbDevice = new AdbDevice(options.adbRunner, device.serial);
    const jdwpPort = await adbDevice.forwardJdwp(process.pid);

    const launcher = new AdapterLauncher();
    const adapterProc = launcher.launch({
      javaPath: options.javaPath,
      jarPath: options.jarPath,
      args: [`--port=${dapPort}`, `--jdwp-port=${jdwpPort}`],
    });

    try {
      await waitForAdapterReady(adapterProc, 10_000);
    } catch (e) {
      try {
        adapterProc.kill();
      } catch {
        // already dead
      }
      await adbDevice.removeForward(jdwpPort).catch(() => {});
      throw e;
    }

    return new SessionContextImpl(
      session,
      device,
      process,
      jdwpPort,
      adbDevice,
      dapPort,
      adapterProc
    );
  }

  /**
   * Resolve the bundled adapter jar against the extension installation
   * directory. The caller (SessionManager.attachToProcess) hands us the
   * extensionPath; we never reach into VSCode's extension registry.
   */
  static resolveAdapterJar(extensionPath: string): string {
    return new AdapterLauncher().resolveAdapterJar(extensionPath);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    try {
      this.adapterProc.kill();
    } catch (e) {
      Logger.warn(`[session] adapter kill threw: ${(e as Error).message}`);
    }
    this.adbDevice.removeForward(this.jdwpPort).catch((err) => {
      Logger.warn(
        `[session] removeForward tcp:${this.jdwpPort} failed: ${(err as Error).message}`
      );
    });
  }

  isDisposed(): boolean {
    return this.disposed;
  }
}

class AdapterLauncher {
  launch(options: { javaPath: string; jarPath: string; args?: string[]; cwd?: string }): ChildProcess {
    const jvmArgs = [
      '-Dfile.encoding=UTF-8',
      '-Dsun.stdout.encoding=UTF-8',
      '-Dsun.stderr.encoding=UTF-8',
      '-jar', options.jarPath,
      ...(options.args || []),
    ];
    Logger.info(t.logAdapterSpawn(options.javaPath, jvmArgs.join(' ')));
    const proc = spawn(options.javaPath, jvmArgs, {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: options.cwd,
    });
    proc.on('exit', (code, signal) => {
      Logger.info(t.logAdapterExited(code, signal));
    });
    proc.on('error', (err) => {
      Logger.error(t.logAdapterSpawnFail(), err);
    });
    // In TCP mode (the only mode the extension launches) the adapter
    // routes its own Logging output to stdout — DAP traffic goes over the
    // socket — so stdout lines are adapter logs: tag them plainly. stderr
    // is then reserved for JVM-level errors and stdio-mode protocol
    // safety, which deserve the louder "[adapter stderr]" marker.
    proc.stdout?.on('data', (chunk: Buffer) => {
      for (const line of chunk.toString('utf-8').split(/\r?\n/)) {
        if (line) Logger.info(`[adapter] ${line}`);
      }
    });
    proc.stderr?.on('data', (chunk: Buffer) => {
      for (const line of chunk.toString('utf-8').split(/\r?\n/)) {
        if (line) Logger.info(`[adapter stderr] ${line}`);
      }
    });
    return proc;
  }

  resolveAdapterJar(extensionPath: string): string {
    const path = require('path') as typeof import('path');
    const fs = require('fs') as typeof import('fs');
    const candidates = [
      path.join(extensionPath, 'resources', 'adapter.jar'),
      path.join(extensionPath, '..', 'adapter', 'build', 'libs', 'adapter.jar'),
    ];
    for (const p of candidates) {
      if (fs.existsSync(p)) {
        Logger.info(t.logAdapterJarFound(p));
        return p;
      }
    }
    throw new Error(t.errAdapterJarMissing(candidates.join('\n')));
  }
}

async function allocateFreePort(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error(t.errBindPortFailed()));
        return;
      }
      const port = addr.port;
      server.close(() => {
        Logger.info(`[port] allocated ${port}`);
        resolve(port);
      });
    });
  });
}

function waitForAdapterReady(proc: ChildProcess, timeoutMs: number): Promise<void> {
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

/**
 * Re-export of the inner helpers as namespace members, so legacy
 * call sites (e.g. `SessionContextImpl.PortAllocator.allocateFreePort()`)
 * keep compiling. Slice 6 removes the legacy call sites in
 * `commands/attach.ts` and routes everything through `SessionManager`.
 */
export namespace SessionContextImpl {
  export const PortAllocator = { allocateFreePort };
}
