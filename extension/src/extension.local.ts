// extension/src/extension.local.ts
import * as vscode from 'vscode';
import { Logger } from './util/logger';
import { detectJdk } from './util/jdk';
import { StatusBarManager } from './ui/statusBar';
import { SourceDirsManager } from './source/dirs';
import { SourceDirDecorationProvider } from './source/decoration';
import { SourceDirsTreeProvider, SourceDirItem } from './source/treeView';
import { runAttachCommand } from './commands/attach';
import { runRestartSessionCommand } from './commands/restartSession';
import { runMarkSourceDir, runUnmarkSourceDir } from './commands/markSourceDir';
import { t } from './i18n/strings';
import { refreshSourceDirsContext } from './source/context';
import { FqnEntry, IndexTooLargeError, buildIndex as buildIndexLocal } from './source/indexer';
import { getConfig } from './util/config';
import { RemoteExtApi } from './extension.remote';
import { DebugIndexSync } from './debug/indexSync';
import { getActiveSync, setActiveSync, clearActiveSync } from './debug/indexSyncRegistry';
import { AndroidJavaFactory } from './debug/androidJavaFactory';
import { sweepStaleIndexFiles } from './util/indexFile';
import { getJdwpController } from './debug/jdwpRegistry';

export interface UiExtApi {
  getSourceDirs(): Promise<string[]>;
  getAdapterPort(): Promise<number | undefined>;
}

let statusBar: StatusBarManager | undefined;
let dirsManager: SourceDirsManager | undefined;
let decorationProvider: SourceDirDecorationProvider | undefined;
let treeProvider: SourceDirsTreeProvider | undefined;
const factory = new AndroidJavaFactory();

async function syncSourceDirsContext(): Promise<void> {
  if (!dirsManager) return;
  const dirs = await dirsManager.getAllDirs();
  await refreshSourceDirsContext(dirs);
}

async function buildIndexWithProgress(
  sourceDirs: string[],
  onFallback: () => void,
  maxFiles: number,
  // The fully-qualified extension id (`<publisher>.<name>`) of THIS
  // extension. Used to look up the remote-host half of the same
  // extension when running in a Remote-SSH workspace. Passed in from
  // the activate() context (`context.extension.id`) so we don't have
  // to hard-code the publisher — downstream packagers may customise
  // it (e.g. `6xyun.debugger-for-android`), and a hard-coded id would
  // silently fail to find the remote half on the other side of the
  // cross-host call.
  extensionId: string
): Promise<FqnEntry[]> {
  return await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: t.attachProgressTitle(sourceDirs.length),
      cancellable: true,
    },
    async (progress, token) => {
      try {
        if (vscode.env.remoteName) {
          try {
            const ext = vscode.extensions.getExtension<RemoteExtApi>(extensionId);
            if (ext?.isActive && ext.exports?.buildIndex) {
              Logger.info(t.logIndexerRemote());
              progress.report({ message: t.attachIndexerRemote() });
              const entries = await ext.exports.buildIndex(sourceDirs, { maxFiles });
              return entries;
            }
            Logger.warn(`[local] remote ext not active, falling back`);
          } catch (e) {
            Logger.warn(
              `[local] remote buildIndex failed, falling back: ${(e as Error).message}`
            );
          }
        }
        onFallback();
        Logger.info(t.logIndexerFallback());
        progress.report({ message: t.attachIndexerFallback() });
        return await buildIndexLocalWithGuard(sourceDirs, progress, token, maxFiles);
      } catch (e) {
        if (e instanceof IndexTooLargeError) {
          const choice = await vscode.window.showWarningMessage(
            t.attachLargeIndexMessage(e.totalFiles, e.estimatedSeconds),
            t.attachLargeIndexContinue(),
            t.attachLargeIndexCancel()
          );
          if (choice !== t.attachLargeIndexContinue()) {
            throw new Error(t.attachIndexCancelled());
          }
          return await buildIndexLocalWithGuard(sourceDirs, progress, token, e.totalFiles + 1);
        }
        throw e;
      }
    }
  );
}

async function buildIndexLocalWithGuard(
  sourceDirs: string[],
  progress: vscode.Progress<{ message?: string }>,
  token: vscode.CancellationToken,
  maxFiles: number
): Promise<FqnEntry[]> {
  return await buildIndexLocal(sourceDirs, { progress, token, maxFiles });
}

interface SyncBreakpointEntry {
  line: number;
  condition?: string;
  logMessage?: string;
  /**
   * Mirrors VSCode's `Breakpoint.enabled`. Defaults to true on the adapter
   * side when absent (back-compat with older senders). The extension
   * ALWAYS sends the actual value so the adapter can run the state machine:
   *   enabled=true  → ACTIVE  (install/reuse JDI request)
   *   enabled=false → DISABLED (remove JDI request, KEEP the id)
   * A BP not present in the payload is treated as REMOVED (clear id too).
   */
  enabled: boolean;
}

interface SyncBreakpointFile {
  source: { path: string };
  breakpoints: SyncBreakpointEntry[];
}

function collectBreakpoints(): SyncBreakpointFile[] {
  const byFile = new Map<string, SyncBreakpointEntry[]>();
  for (const bp of vscode.debug.breakpoints) {
    if (!(bp instanceof vscode.SourceBreakpoint)) continue;
    // INCLUDE disabled BPs in the payload — the adapter runs a state
    // machine based on the `enabled` flag (ACTIVE / DISABLED / REMOVED),
    // so the adapter MUST see the disabled state to clear the JDI request.
    // Filtering them here would leave a stale JDI request firing.
    const loc = bp.location;
    const path = loc?.uri?.fsPath;
    if (!path) continue;
    if (!/\.(java|kt)$/.test(path)) continue;
    let list = byFile.get(path);
    if (!list) {
      list = [];
      byFile.set(path, list);
    }
    list.push({
      line: loc.range.start.line + 1,
      condition: bp.condition,
      logMessage: bp.logMessage,
      enabled: bp.enabled,
    });
  }
  const files: SyncBreakpointFile[] = [];
  for (const [path, breakpoints] of byFile) {
    files.push({ source: { path }, breakpoints });
  }
  return files;
}

async function pushAllBreakpoints(session: vscode.DebugSession): Promise<void> {
  try {
    const files = collectBreakpoints();
    const total = files.reduce((n, f) => n + f.breakpoints.length, 0);
    Logger.info(`[local] syncBreakpoints pushing ${total} breakpoints across ${files.length} files`);
    if (files.length === 0) {
      // Still send an empty request so the adapter can clear any stale state.
      await session.customRequest('syncBreakpoints', { files: [] });
      return;
    }
    const resp = await session.customRequest('syncBreakpoints', { files });
    Logger.info(`[local] syncBreakpoints result: ${JSON.stringify(resp.body ?? {})}`);
  } catch (e) {
    Logger.error('[local] syncBreakpoints failed', e as Error);
  }
}

async function pushBreakpointsForFiles(
  session: vscode.DebugSession,
  paths: string[]
): Promise<void> {
  if (paths.length === 0) return;
  const byFile = new Map<string, SyncBreakpointEntry[]>();
  for (const bp of vscode.debug.breakpoints) {
    if (!(bp instanceof vscode.SourceBreakpoint)) continue;
    // INCLUDE disabled BPs — see collectBreakpoints for rationale. We send
    // them with enabled=false so the adapter's state machine can clear the
    // matching JDI request while preserving the id (DISABLED state).
    const path = bp.location?.uri?.fsPath;
    if (!path || !paths.includes(path)) continue;
    let list = byFile.get(path);
    if (!list) {
      list = [];
      byFile.set(path, list);
    }
    list.push({
      line: bp.location!.range.start.line + 1,
      condition: bp.condition,
      logMessage: bp.logMessage,
      enabled: bp.enabled,
    });
  }
  const files: SyncBreakpointFile[] = [];
  for (const [path, breakpoints] of byFile) {
    files.push({ source: { path }, breakpoints });
  }
  // Always send a payload for every affected path, even when no BPs remain
  // for it (the user just removed the last one). The empty `breakpoints: []`
  // triggers the adapter's REMOVED diff — without it, bpIdRegistry entries
  // for BPs the user truly deleted would linger and re-adds would reuse the
  // old id, mismatching VSCode's fresh vsId for the new BP.
  for (const path of paths) {
    if (!byFile.has(path)) {
      files.push({ source: { path }, breakpoints: [] });
    }
  }
  if (files.length === 0) return;
  Logger.info(`[local] syncBreakpoints incremental push: ${files.length} files`);
  await session.customRequest('syncBreakpoints', { files });
}

export async function activate(context: vscode.ExtensionContext): Promise<UiExtApi> {
  Logger.info(`[UI] activating`);
  Logger.info(`[UI] extensionId        = ${context.extension.id}`);
  Logger.info(`[UI] extensionPath      = ${context.extensionPath}`);
  Logger.info(`[UI] sessionId          = ${vscode.env.sessionId}`);
  Logger.info(`[UI] creating status bar, dirs manager, tree view`);

  // Best-effort sweep of stale index files left behind by previous
  // sessions (e.g. an adapter that crashed before its finally{} could
  // delete the file). Non-blocking; safe to fail.
  sweepStaleIndexFiles();

  statusBar = new StatusBarManager();
  statusBar.show();
  dirsManager = new SourceDirsManager(context.workspaceState);
  statusBar.setDirsManager(dirsManager);
  decorationProvider = new SourceDirDecorationProvider(dirsManager);
  treeProvider = new SourceDirsTreeProvider(dirsManager);
  Logger.info(`[UI] UI components created`);

  const decoDisposable = vscode.window.registerFileDecorationProvider(decorationProvider);
  const treeView = vscode.window.createTreeView('debugger-for-android.sourceDirs', {
    treeDataProvider: treeProvider,
    showCollapseAll: false,
  });

  const factoryRegistration = vscode.debug.registerDebugAdapterDescriptorFactory(
    'android-java',
    factory
  );

  const onDebugSessionStart = vscode.debug.onDidStartDebugSession((session) => {
    if (session.configuration.type !== 'android-java') return;
    if (!dirsManager) return;
    Logger.info(`[local] onDidStartDebugSession id=${session.id} name=${session.name}`);
    // NOTE: do NOT clear the factory's tracked JVM here. The JVM
    // outlives the session (the adapter loops on accept()) and
    // should be reused for restart. Killing it here would force
    // every restart to spawn a brand-new JVM and leak the prior one.

    const sync = new DebugIndexSync(session, dirsManager, {
      maxFiles: getConfig().indexMaxFiles,
      extensionId: context.extension.id,
      buildIndexWithProgress,
    });
    setActiveSync(sync);

    (async () => {
      try {
        const dirs = await dirsManager.getAllDirs();
        if (dirs.length === 0) {
          Logger.info('[local] no marked source dirs, skipping initial sourceDirs/set');
        } else {
          const entries = await buildIndexWithProgress(
            dirs,
            () => statusBar?.setIndexerMode('fallback'),
            getConfig().indexMaxFiles,
            context.extension.id
          );
          if (sync.isDisposed()) return;
          Logger.info(`[local] sending initial sourceDirs/set: ${entries.length} entries`);
          await session.customRequest('sourceDirs/set', { entries });
          statusBar?.setIndexerMode('fast');
        }
        // After (or independent of) sourceDirs/set, push the full set of
        // workspace breakpoints down to the adapter. VSCode's built-in
        // setBreakpoints only fires for files open in the editor at session
        // start, so we replay everything via a custom request to make sure
        // no existing breakpoint is silently dropped.
        if (!sync.isDisposed()) {
          await pushAllBreakpoints(session);
        }
      } catch (e) {
        if (e instanceof IndexTooLargeError) {
          Logger.warn(`[local] initial index too large, skipped`);
        } else {
          Logger.error('[local] initial sourceDirs/set failed', e as Error);
        }
      }
    })();
  });
  context.subscriptions.push(onDebugSessionStart);

  const onDebugSessionTerminate = vscode.debug.onDidTerminateDebugSession((session) => {
    if (session.configuration.type !== 'android-java') return;
    Logger.info(`[local] onDidTerminateDebugSession id=${session.id}`);
    setActiveSync(undefined);
    // Atomic teardown: kill the JVM AND remove the adb forward for
    // the session's JDWP port. This makes "Restart" a clean slate
    // every time — no stale forwards on the device, no wedged JVMs
    // holding stale DAP ports.
    //
    // The forward removal MUST happen here (not deferred to the
    // factory) because the factory's `spawnFreshAdapter` (called
    // from `createDebugAdapterDescriptor` on the restart path) needs
    // to allocate a NEW JDWP port for the new forward; if the OLD
    // forward were still around, `adb forward` would refuse the new
    // request with "address already in use" (or, in practice,
    // silently replace it, which would still leak the old local
    // port).
    //
    // After terminate, the toolbar Restart flow looks like:
    //   1. VSCode ends the current session.
    //   2. onDidTerminate fires → removeForward + killActive.
    //   3. VSCode starts a new session with the OLD __jdwpPort in
    //      its config (the OLD forward is gone).
    //   4. factory.createDebugAdapterDescriptor sees no live JVM,
    //      calls spawnFreshAdapter which allocates a NEW JDWP port,
    //      sets up a NEW adb forward, and spawns a NEW JVM with
    //      `--jdwp-port=<new>`. The JVM uses the CLI override to
    //      ignore the stale __jdwpPort from the DAP attach request.
    //
    // JDWP port source: we read `factory.getJdwpPort()` as the
    // authoritative source (the factory tracks the LATEST JDWP
    // port), and only fall back to `session.configuration.__jdwpPort`
    // if the factory is blind (e.g. the extension was reloaded
    // between sessions). Reading from the session config alone is
    // WRONG because VSCode's toolbar Restart clones the FIRST
    // session's configuration for every subsequent restart, so
    // `__jdwpPort` in the config stays at the original attach's
    // port across all restarts — removing that stale port would
    // no-op in `removeForward`'s `activeForwards.has(port)` guard
    // and LEAK the actual forward on every restart after the first.
    // Read factory BEFORE killActive (killActive clears the tracking).
    const factoryPort = factory.getJdwpPort();
    factory.killActive();
    const port = factoryPort ?? (session.configuration as Record<string, unknown>).__jdwpPort;
    if (typeof port !== 'number') {
      Logger.info(`[local] no JDWP port available; skip removeForward`);
    } else {
      const jdwp = getJdwpController();
      if (!jdwp) {
        Logger.warn(`[local] no jdwp controller registered; cannot remove forward tcp:${port}`);
      } else {
        jdwp.removeForward(port).catch((err) => {
          Logger.warn(
            `[local] failed to remove adb forward tcp:${port}: ${(err as Error).message}`
          );
        });
      }
    }
  });
  context.subscriptions.push(onDebugSessionTerminate);

  const onDirsChange = dirsManager.onDidChange(() => {
    decorationProvider?.refresh();
    treeProvider?.refresh();
    statusBar?.refreshCount();
    syncSourceDirsContext();
    const sync: DebugIndexSync | undefined = getActiveSync();
    if (sync && !sync.isDisposed()) {
      Logger.info('[local] sourceDirs changed mid-session, re-indexing');
      sync.pushFullIndex().catch((err) => {
        Logger.error('[local] re-index after sourceDirs change failed', err as Error);
      });
    }
  });
  context.subscriptions.push(onDirsChange);

  // Throttle the one-shot notification when the status-bar sidebar button
  // can't open the activity-bar view container (because the builtin
  // `workbench.view.<id>` command is missing on some activation orders).
  // Held in a closure-scoped object so it can be flipped from inside the
  // `context.subscriptions.push(...)` argument list, which itself can't
  // contain declarations.
  const openExplorerNotifiedRef = { value: false };

  context.subscriptions.push(
    statusBar,
    decoDisposable,
    treeView,
    factoryRegistration,
    // The `view/title` icon for this command lives in package.json
    // (`$(debug-alt)`). It takes a built-in VSCode codicon — see the
    // catalogue at https://microsoft.github.io/vscode-codicons/dist/codicon.html
    // (any name from there works: $(debug), $(debug-alt), $(bug),
    // $(server-process), etc.). vsce rejects comments in package.json so
    // we can't drop a pointer there.
    vscode.commands.registerCommand('debugger-for-android.attach', () => {
      if (!statusBar || !dirsManager) return;
      runAttachCommand(statusBar, {
        factory,
        dirsManager,
        extensionPath: context.extensionPath,
        buildIndex: (dirs: string[]) =>
          buildIndexWithProgress(
            dirs,
            () => statusBar?.setIndexerMode('fallback'),
            getConfig().indexMaxFiles,
            context.extension.id
          ),
      });
    }),
    vscode.commands.registerCommand('debugger-for-android.restartSession', () => {
      if (!statusBar || !dirsManager) return;
      runRestartSessionCommand(statusBar, {
        factory,
        dirsManager,
        extensionPath: context.extensionPath,
        buildIndex: (dirs: string[]) =>
          buildIndexWithProgress(
            dirs,
            () => statusBar?.setIndexerMode('fallback'),
            getConfig().indexMaxFiles,
            context.extension.id
          ),
      });
    }),
    vscode.commands.registerCommand('debugger-for-android.markSourceDir', (uri?: vscode.Uri) => {
      if (!dirsManager) return;
      runMarkSourceDir(dirsManager, uri);
    }),
    vscode.commands.registerCommand('debugger-for-android.unmarkSourceDir', (uri?: vscode.Uri) => {
      if (!dirsManager) return;
      runUnmarkSourceDir(dirsManager, uri);
    }),
    vscode.commands.registerCommand('debugger-for-android.unmarkFromDir', (item: SourceDirItem) => {
      if (!dirsManager) return;
      const uri = item?.resourceUri;
      if (!uri) return;
      runUnmarkSourceDir(dirsManager, uri);
    }),
    vscode.commands.registerCommand('debugger-for-android.revealInExplorer', async (item: SourceDirItem) => {
      if (!item?.resourceUri) return;
      await vscode.commands.executeCommand('workbench.view.explorer');
      await vscode.commands.executeCommand('revealInExplorer', item.resourceUri);
    }),
    // Opens the activity-bar view container `debugger-for-android-explorer`.
    // We wrap the VSCode-builtin `workbench.view.<id>` command rather than
    // Opens the activity-bar view container `debugger-for-android-explorer`.
    //
    // VSCode auto-registers two flavours of `workbench.view.*` commands:
    //   - `workbench.view.<id>` for built-in view containers
    //     (e.g. `workbench.view.explorer`, `workbench.view.search`).
    //   - `workbench.view.extension.<id>` for activity-bar containers
    //     contributed by extensions via
    //     `package.json#contributes.viewsContainers`. The `extension.`
    //     prefix is what marks it as extension-contributed.
    //
    // Ours is extension-contributed, so the registered command is
    // `workbench.view.extension.debugger-for-android-explorer` — not
    // `workbench.view.debugger-for-android-explorer` (which we used
    // originally and which VSCode responds to with "command not
    // found"). The diagnostic log in step (2) below is what surfaced
    // the correct name.
    //
    // We own this wrapper command rather than pointing the status-bar
    // item at the VSCode builtin directly so that (a) the status-bar
    // config stays stable if VSCode renames the builtin in the future,
    // and (b) we can degrade gracefully (focus the activity bar +
    // show a one-shot hint) if the builtin ever goes missing for any
    // reason.
    vscode.commands.registerCommand('debugger-for-android.openExplorer', async () => {
      // 1) Primary: the VSCode-builtin command for our extension-contributed
      //    activity-bar view container.
      try {
        await vscode.commands.executeCommand(
          'workbench.view.extension.debugger-for-android-explorer'
        );
        return;
      } catch (e) {
        Logger.warn(
          `[local] workbench.view.extension.debugger-for-android-explorer failed: ${(e as Error).message}`
        );
      }

      // 2) Best-effort diagnostic: list the actually-registered
      //    `workbench.view.*` commands so future bug reports include
      //    what VSCode actually knows about.
      try {
        const all = await vscode.commands.getCommands(true);
        const ours = all.filter((c) => c.includes('debugger-for-android') || c.startsWith('workbench.view.'));
        Logger.info(`[local] workbench.view.* commands registered: ${ours.join(', ') || '(none)'}`);
      } catch (e) {
        Logger.debug(`[local] getCommands diagnostic failed: ${(e as Error).message}`);
      }

      // 3) Fallback: at least focus the activity bar so the user can
      //    click the explorer icon manually if the auto-registered
      //    command is unavailable for any reason.
      try {
        await vscode.commands.executeCommand('workbench.action.focusActivityBar');
      } catch (e) {
        Logger.warn(`[local] focusActivityBar fallback failed: ${(e as Error).message}`);
      }

      // 4) One-shot notification so the user knows why nothing opened
      //    automatically and what to do. Throttled to once per session —
      //    the user has seen it; no need to spam on every click.
      if (!openExplorerNotifiedRef.value) {
        openExplorerNotifiedRef.value = true;
        const choice = await vscode.window.showInformationMessage(
          t.openExplorerFallbackHint(),
          t.openExplorerFallbackAction()
        );
        if (choice === t.openExplorerFallbackAction()) {
          // Best-effort: jump the user straight to the source-dirs
          // command so they can act immediately.
          await vscode.commands.executeCommand('workbench.view.explorer');
          await vscode.commands.executeCommand('debugger-for-android.markSourceDir');
        }
      }
    })
  );

  vscode.debug.onDidChangeBreakpoints((e) => {
    if (!dirsManager) return;
    const sync: DebugIndexSync | undefined = getActiveSync();
    if (!sync || sync.isDisposed()) return;
    const session = vscode.debug.activeDebugSession;
    if (!session || session.configuration.type !== 'android-java') return;

    // Auto-include new BP files into the source index
    const filesToAdd: string[] = [];
    for (const added of e.added) {
      const loc = (added as vscode.SourceBreakpoint).location;
      const path = loc?.uri?.fsPath;
      if (!path || !/\.(java|kt)$/.test(path)) continue;
      if (dirsManager.contains(path)) continue;
      filesToAdd.push(path);
    }
    if (filesToAdd.length > 0) {
      const unique = Array.from(new Set(filesToAdd));
      sync.addFiles(unique).catch((err) => {
        Logger.error('[local] auto-include failed', err as Error);
      });
    }

    // Re-sync BPs for any changed/removed/added file. We always push the
    // full set so the adapter reflects the current state.
    const affected = new Set<string>();
    for (const bp of [...e.added, ...e.removed, ...e.changed]) {
      const loc = (bp as vscode.SourceBreakpoint).location;
      const path = loc?.uri?.fsPath;
      if (path) affected.add(path);
    }
    if (affected.size > 0) {
      pushBreakpointsForFiles(session, Array.from(affected)).catch((err) => {
        Logger.error('[local] syncBreakpoints incremental failed', err as Error);
      });
    }
  });

  const jdk = await detectJdk(getConfig().javaPath);
  if (!jdk) {
    Logger.warn(t.jdkMissingLong());
    statusBar.setState('error', t.jdkStatusMissing());
    vscode.window.showWarningMessage(
      t.jdkWarning(),
      t.installJdk(),
      t.viewOutput()
    ).then((choice) => {
      if (choice === t.installJdk()) {
        vscode.env.openExternal(vscode.Uri.parse('https://adoptium.net/'));
      } else if (choice === t.viewOutput()) {
        Logger.info(`[UI] user chose '${t.viewOutput()}'`);
      }
    });
  } else {
    Logger.info(t.jdkDetected(jdk.javaPath, jdk.version));
  }

  Logger.info(t.activated());
  treeProvider.refresh();
  await syncSourceDirsContext();

  Logger.info(`[UI] returning UiExtApi`);
  return {
    async getSourceDirs() {
      if (!dirsManager) return [];
      return await dirsManager.getAllDirs();
    },
    async getAdapterPort() {
      return factory.getPort();
    },
  };
}

export function deactivate(): void {
  clearActiveSync();
  // Kill any tracked JVM so we don't leak the adapter process when
  // VSCode shuts down or reloads the extension.
  factory.killActive();
  // Sweep any adb forwards the user might still have open. Normal
  // sessions are released on terminate, but if VSCode is force-quit
  // or the extension is reloaded mid-session, this catches the ones
  // that fell through. Fire-and-forget — deactivate can't await.
  const jdwp = getJdwpController();
  if (jdwp) {
    jdwp.cleanupAllForwards().catch((err) => {
      Logger.warn(`[local] cleanupAllForwards failed: ${(err as Error).message}`);
    });
  }
  if (statusBar) {
    statusBar.dispose();
    statusBar = undefined;
  }
  Logger.info(t.deactivating());
}
