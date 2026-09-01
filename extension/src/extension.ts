// extension/src/extension.ts
// VSCode extension entry point.
//
// `extensionKind: ["ui", "workspace"]` declares a preference, but VSCode
// loads the extension on exactly ONE host: the UI host (in both local and
// Remote-SSH windows — the effective kind is always `ui` here). All
// filesystem work therefore goes through `vscode.workspace.fs` /
// `vscode.workspace.findFiles`, which transparently reach the remote FS.
//
// activateUI is deliberately thin: it constructs the managers, subscribes
// the cross-manager pipelines that aren't owned by a manager itself, and
// hands everything else to UIManager / SessionManager. All sides share a
// single "Android Debugger" OutputChannel.
import * as vscode from 'vscode';
import { Logger } from './util/logger';
import { getConfig } from './util/config';
import { ensureJdkOrWarn } from './util/jdk';
import { DefaultAdbRunner } from './adb/AdbRunner';
import { SourceDirManager } from './core/SourceDirManager';
import { IndexManager } from './core/IndexManager';
import { createIndexer } from './core/indexer/IndexerFactory';
import { BreakpointManager } from './core/BreakpointManager';
import { SessionManager } from './core/SessionManager';
import { UIManager } from './ui/UIManager';
import { SourceDirsBridge, SessionBridge } from './ui/UiBridges';
import { t } from './i18n/strings';

export async function activate(context: vscode.ExtensionContext): Promise<unknown> {
  const tag = 'DISPATCHER';

  const outputChannel = vscode.window.createOutputChannel(t.outputChannel());
  Logger.initialize(outputChannel);
  outputChannel.show(true);

  Logger.info(`=========================================`);
  Logger.info(`[${tag}] activate() entered`);
  Logger.info(`[${tag}]   declared in pkg.json = ${JSON.stringify(
    (context.extension.packageJSON as { extensionKind?: unknown }).extensionKind
  )}`);
  Logger.info(`[${tag}]   extensionId        = ${context.extension.id}`);
  Logger.info(`[${tag}]   extensionPath      = ${context.extension.extensionPath}`);
  Logger.info(`[${tag}]   sessionId          = ${vscode.env.sessionId}`);
  Logger.info(
    `[${tag}]   remoteName         = "${vscode.env.remoteName || '<none>'}" ` +
      `(isRemoteWindow=${!!vscode.env.remoteName})`
  );
  Logger.info(`=========================================`);

  console.log(
    `[ADB Dispatcher] sessionId=${vscode.env.sessionId} ` +
      `remoteName="${vscode.env.remoteName || '<none>'}"`
  );

  try {
    await activateUI(context);
    Logger.info(`[${tag}] UI side activated OK`);
    return undefined;
  } catch (e) {
    Logger.error(`[${tag}] ERROR activating UI side`, e as Error);
    throw e;
  }
}

/**
 * UI-side activation: construct the managers, wire the pipelines, hand
 * the workbench surfaces to UIManager. JDK pre-flight warns but does not
 * fail — SessionManager re-detects lazily at attach time.
 */
async function activateUI(context: vscode.ExtensionContext): Promise<void> {
  const config = getConfig();
  const jdk = await ensureJdkOrWarn(config.javaPath);

  const dirsManager = new SourceDirManager(context.workspaceState);
  const breakpointManager = new BreakpointManager(
    context,
    // Bridge callback: BreakpointManager never holds a SourceDirManager.
    // Auto-include gate: skip files already covered by a marked dir.
    (file) => dirsManager.isPathMarked(file)
  );

  // Indexer strategy is chosen once here and injected into IndexManager.
  // Today: always LocalIndexer (workspace.fs/findFiles reach the remote FS
  // transparently). In remote windows the factory probes for a remote
  // indexer provider and reports fallback — the seam for the future
  // workspace-host helper extension (see core/indexer/IndexerFactory.ts).
  let ui: UIManager | undefined;
  const indexer = createIndexer({
    extensionId: context.extension.id,
    onFallback: () => ui?.statusBar.setIndexerMode('fallback'),
  });
  const indexManager = new IndexManager(indexer);

  const sessionManager = new SessionManager({
    javaPath: jdk?.javaPath,
    adbRunner: new DefaultAdbRunner(config.adbPath),
    // Bridges — managers never depend on each other; all cross-manager
    // traffic flows through these callbacks wired here.
    getFqnInfo: () => indexManager.getAllEntries(),
  });

  // Bridge contracts handed to the UI layer — UIManager never sees a
  // manager instance, only these callback objects.
  const dirsBridge: SourceDirsBridge = {
    getDirs: () => dirsManager.getAllDirs(),
    contains: (d) => dirsManager.contains(d),
    findCoveringAncestor: (d) => dirsManager.findCoveringAncestor(d),
    addDir: (d) => dirsManager.addDir(d),
    removeDir: (d) => dirsManager.removeDir(d),
    isPathMarked: (f) => dirsManager.isPathMarked(f),
    onDidChange: (cb) => dirsManager.onDidChange(cb),
  };
  const sessionBridge: SessionBridge = {
    attachToProcess: (d, p) => sessionManager.attachToProcess(d, p),
    restartActiveSession: () => sessionManager.restartActiveSession(),
  };

  // === External bridge wiring (the ONLY place managers meet) ===
  context.subscriptions.push(
    // marked dirs changed → full index rebuild
    dirsManager.onDidChange(async () => {
      await indexManager.rebuildFromDirs(
        await dirsManager.getAllDirs(),
        config.indexMaxFiles
      );
    }),
    // breakpoint landed outside marked dirs → auto-include into index
    breakpointManager.onNewFileNeedsIndex((uris) => void indexManager.autoIncludeFiles(uris)),
    // index rebuilt → broadcast to live sessions
    indexManager.onIndexReady((entries) => sessionManager.broadcastIndex(entries)),
    // breakpoints changed → broadcast to live sessions
    breakpointManager.onBreakpointsChanged((files) => sessionManager.broadcastBreakpoints(files))
  );

  // Fresh session start → refresh the index from the currently marked dirs,
  // then push the initial index + breakpoint snapshots. The rebuild here is
  // what keeps entries warm: nothing else fires between activation and the
  // first attach (dirsManager.onDidChange only fires on mark/unmark), and
  // the pre-refactor flow built the index on every attach as well. Data
  // access happens in this closure, not inside SessionManager.
  sessionManager.setSessionInitializer(async (ctx) => {
    await indexManager.rebuildFromDirs(
      await dirsManager.getAllDirs(),
      config.indexMaxFiles
    );

    const entries = indexManager.getAllEntries();
    Logger.info(`[session] initial index push: ${entries.length} entries`);
    if (entries.length > 0) {
      await ctx.dap.syncSourceIndex(entries).catch((err: Error) => {
        Logger.warn(`[session] initial custom/syncSourceIndex failed: ${err.message}`);
      });
    }
    await ctx.dap
      .syncBreakpoints(breakpointManager.getCurrentBreakpoints())
      .catch((err: Error) => {
        Logger.warn(`[session] initial custom/syncBreakpoints failed: ${err.message}`);
      });
  });

  const uiManager = new UIManager({ dirs: dirsBridge, session: sessionBridge });
  ui = uiManager;
  context.subscriptions.push(uiManager, indexManager);

  // In a Remote-SSH window the whole extension runs on the UI host; source
  // indexing still works (workspace.fs proxies the remote FS) but is not
  // native-speed, so surface the degraded mode on the status bar.
  if (vscode.env.remoteName) {
    uiManager.statusBar.setIndexerMode('fallback');
    Logger.warn(
      `[DISPATCHER] remote window (${vscode.env.remoteName}): extension runs on the UI host; ` +
        `source indexing goes through the remote FS proxy (works, slower)`
    );
  }

  sessionManager.register(context, context.extensionPath);
  uiManager.registerCommands();
}

export function deactivate(): void {
  // Sub-activate functions are responsible for their own teardown.
}
