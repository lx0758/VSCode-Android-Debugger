// extension/src/core/SessionManager.ts
//
// Owns the per-session lifecycle:
//   - a Map<sessionId, SessionContextImpl> for the live sessions
//   - the DebugAdapterDescriptorFactory lambda (registered as
//     `vscode.debug.registerDebugAdapterDescriptorFactory('android-java', ...)`)
//   - onDidStartDebugSession / onDidTerminateDebugSession wiring
//   - broadcastIndex / broadcastBreakpoints for multi-session sync
//   - attachToProcess + restartActiveSession UI entry points
//
// Slice 5 keeps the legacy `commands/attach.ts` doing its own port
// allocation + JVM spawn, and only uses SessionManager for the
// factory + lifecycle hooks. Slice 6 collapses attach.ts into
// `sessionManager.attachToProcess`.

import * as vscode from 'vscode';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';
import { DefaultAdbRunner, AdbRunner } from '../adb/AdbRunner';
import { detectJdk } from '../util/jdk';
import { FqnEntry } from './indexer/Indexer';
import { BreakpointFile } from './BreakpointTypes';
import { SessionContext, DeviceInfo, ProcessInfo } from './session/SessionContext';
import { SessionContextImpl, SessionContextOptions } from './session/SessionContextImpl';

export interface SessionManagerDeps {
  javaPath?: string;
  adbRunner?: AdbRunner;
  /**
   * Bridge callback: supplies the FQN index snapshot for the DAP `attach`
   * request. Injected by the activation entry so SessionManager never
   * holds an IndexManager reference (managers never depend on managers —
   * they talk through callbacks wired externally).
   */
  getFqnInfo?: () => FqnEntry[];
}

export type SessionInitializerCallback = (ctx: SessionContext) => Promise<void> | void;

export class SessionManager {
  private readonly sessions = new Map<string, SessionContextImpl>();
  private sessionInitializer: SessionInitializerCallback | undefined;
  private readonly deps: SessionManagerDeps;

  constructor(deps: SessionManagerDeps) {
    this.deps = deps;
  }

  /**
   * Register the callback that pushes initial state (index snapshot +
   * breakpoint snapshot) into a freshly started session. The data comes
   * from the callback's closure — bridged externally, not from manager
   * references held here.
   */
  setSessionInitializer(cb: SessionInitializerCallback): void {
    this.sessionInitializer = cb;
  }

  /**
   * Register the per-session hooks (factory + start/terminate) with VSCode.
   * Caller is responsible for putting the returned disposables into
   * `context.subscriptions`.
   */
  register(context: vscode.ExtensionContext, extensionPath: string): vscode.Disposable[] {
    const disposables: vscode.Disposable[] = [];

    disposables.push(
      vscode.debug.registerDebugAdapterDescriptorFactory('android-java', {
        createDebugAdapterDescriptor: async (session) =>
          this.handleCreateDescriptor(session, extensionPath),
      })
    );

    disposables.push(
      vscode.debug.onDidStartDebugSession((session) => this.handleSessionStart(session))
    );
    disposables.push(
      vscode.debug.onDidTerminateDebugSession((session) => this.handleSessionTerminate(session))
    );

    context.subscriptions.push(...disposables);
    return disposables;
  }

  private async handleCreateDescriptor(
    session: vscode.DebugSession,
    extensionPath: string
  ): Promise<vscode.DebugAdapterDescriptor> {
    Logger.info(`[session-manager] createDebugAdapterDescriptor id=${session.id}`);
    const cfg = session.configuration as Record<string, unknown>;
    const existing = this.sessions.get(session.id);
    if (existing && !existing.isDisposed()) {
      // Same session being asked again (VSCode reconnect after a transient
      // socket drop). Hand back the already-running JVM's DAP port.
      return new vscode.DebugAdapterServer(existing.dapPort);
    }

    const device = this.readDevice(cfg);
    const proc = this.readProcess(cfg);
    const options = await this.readOptions(extensionPath);

    const ctx = await SessionContextImpl.create(session, device, proc, options);
    this.sessions.set(session.id, ctx);
    Logger.info(`[session-manager] new session ${session.id} dap=${ctx.dapPort} jdwp=${ctx.jdwpPort}`);
    return new vscode.DebugAdapterServer(ctx.dapPort);
  }

  private async handleSessionStart(session: vscode.DebugSession): Promise<void> {
    if (session.configuration.type !== 'android-java') return;
    const ctx = this.sessions.get(session.id);
    if (!ctx) {
      Logger.warn(`[session-manager] onDidStart without context for ${session.id}`);
      return;
    }
    if (this.sessionInitializer) {
      try {
        await this.sessionInitializer(ctx);
      } catch (e) {
        Logger.error('[session-manager] sessionInitializer failed', e as Error);
      }
    }
  }
  private handleSessionTerminate(session: vscode.DebugSession): void {
    if (session.configuration.type !== 'android-java') return;
    const ctx = this.sessions.get(session.id);
    if (ctx) {
      ctx.dispose();
      this.sessions.delete(session.id);
      Logger.info(`[session-manager] session ${session.id} disposed (active=${this.sessions.size})`);
    }
  }

  async attachToProcess(device: DeviceInfo, process: ProcessInfo): Promise<boolean> {
    const configObj: vscode.DebugConfiguration = {
      type: 'android-java',
      request: 'attach',
      name: `Android Debugger (PID ${process.pid})`,
      hostName: 'localhost',
      port: 0, // placeholder; session builder fills in once jdwpPort is known
    };
    (configObj as Record<string, unknown>).__device = device.serial;
    (configObj as Record<string, unknown>).__pid = process.pid;
    if (process.name) (configObj as Record<string, unknown>).__processName = process.name;
    return await vscode.debug.startDebugging(undefined, configObj);
  }

  async restartActiveSession(): Promise<void> {
    const session = vscode.debug.activeDebugSession;
    if (!session || session.configuration.type !== 'android-java') {
      vscode.window.showWarningMessage('No active Android debug session to restart');
      return;
    }
    const ctx = this.sessions.get(session.id);
    if (!ctx) {
      vscode.window.showWarningMessage(
        'Session is missing target context; was started by an older extension version'
      );
      return;
    }
    const device = ctx.device;
    const proc = ctx.process;
    await vscode.debug.stopDebugging(session);
    await this.attachToProcess(device, proc);
  }

  broadcastIndex(entries: FqnEntry[]): void {
    for (const ctx of this.sessions.values()) {
      if (ctx.isDisposed()) continue;
      ctx.dap.syncSourceIndex(entries).catch((err) => {
        Logger.warn(`[session-manager] custom/syncSourceIndex on ${ctx.session.id} failed: ${(err as Error).message}`);
      });
    }
  }

  broadcastBreakpoints(files: BreakpointFile[]): void {
    for (const ctx of this.sessions.values()) {
      if (ctx.isDisposed()) continue;
      ctx.dap.syncBreakpoints(files).catch((err) => {
        Logger.warn(`[session-manager] custom/syncBreakpoints on ${ctx.session.id} failed: ${(err as Error).message}`);
      });
    }
  }

  disposeAll(): void {
    for (const ctx of this.sessions.values()) {
      ctx.dispose();
    }
    this.sessions.clear();
  }

  private readDevice(cfg: Record<string, unknown>): DeviceInfo {
    const serial = typeof cfg.__device === 'string' ? cfg.__device : '';
    return { serial };
  }

  private readProcess(cfg: Record<string, unknown>): ProcessInfo {
    const pid = typeof cfg.__pid === 'number' ? cfg.__pid : 0;
    const name = typeof cfg.__processName === 'string' ? cfg.__processName : undefined;
    return { pid, name };
  }

  private async readOptions(extensionPath: string): Promise<SessionContextOptions> {
    const javaPath = this.deps.javaPath ?? (await this.detectJava());
    const jarPath = SessionContextImpl.resolveAdapterJar(extensionPath);
    const fqnInfo = this.deps.getFqnInfo?.() ?? [];
    const adbRunner = this.deps.adbRunner ?? this.detectAdbRunner();
    return { javaPath, jarPath, fqnInfo, adbRunner };
  }

  private async detectJava(): Promise<string> {
    const jdk = await detectJdk('auto');
    if (!jdk) throw new Error(t.jdkMissingLong());
    return jdk.javaPath;
  }

  private detectAdbRunner(): AdbRunner {
    return new DefaultAdbRunner();
  }
}
