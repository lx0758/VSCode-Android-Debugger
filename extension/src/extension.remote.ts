// extension/src/extension.remote.ts
import * as vscode from 'vscode';
import { Logger } from './util/logger';
import { buildIndex, FqnEntry, IndexTooLargeError } from './source/indexer';
import { UiExtApi } from './extension.local';

export interface RemoteExtApi {
  buildIndex(sourceDirs: string[], options?: { maxFiles?: number }): Promise<FqnEntry[]>;
}

class WorkspaceFactory implements vscode.DebugAdapterDescriptorFactory {
  async createDebugAdapterDescriptor(
    session: vscode.DebugSession
  ): Promise<vscode.DebugAdapterDescriptor | undefined> {
    if (session.configuration.type !== 'android-java') return undefined;
    const cfg = session.configuration as Record<string, unknown>;
    const port = cfg.__adapterPort;
    if (typeof port !== 'number') {
      Logger.error(`[workspace-factory] no __adapterPort in session config (have keys: ${Object.keys(cfg).join(',')})`);
      throw new Error('Missing __adapterPort');
    }
    Logger.info(`[workspace-factory] returning DebugAdapterServer(${port}) for session ${session.id}`);
    return new vscode.DebugAdapterServer(port);
  }
}

export function activate(_context: vscode.ExtensionContext): RemoteExtApi {
  Logger.info(`[Workspace] activating`);
  Logger.info(`[Workspace] extensionId   = ${_context.extension.id}`);
  Logger.info(`[Workspace] extensionPath = ${_context.extensionPath}`);
  Logger.info(`[Workspace] sessionId     = ${vscode.env.sessionId}`);

  _context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory('android-java', new WorkspaceFactory())
  );
  Logger.info(`[Workspace] registered DebugAdapterDescriptorFactory for android-java`);

  const onSessionStart = vscode.debug.onDidStartDebugSession(async (session) => {
    if (session.configuration.type !== 'android-java') return;
    Logger.info(`[Workspace] onDidStartDebugSession id=${session.id} name=${session.name}`);

    (async () => {
      try {
        // Use _context.extension.id rather than a hard-coded publisher id.
        // The publisher in package.json may be customised by downstream
        // packagers (e.g. publisher "6xyun" instead of the upstream
        // "anomaly"), and a hard-coded id would silently fail to find the
        // UI host's extension on the other side of the cross-host call.
        const uiExt = vscode.extensions.getExtension<UiExtApi>(_context.extension.id);
        if (!uiExt?.exports?.getSourceDirs) {
          Logger.warn('[Workspace] UI ext API unavailable, skipping sourceDirs/set');
          return;
        }
        const dirs = await uiExt.exports.getSourceDirs();
        if (dirs.length === 0) {
          Logger.info('[Workspace] no marked source dirs, skipping sourceDirs/set');
          return;
        }
        const cfg = session.configuration as Record<string, unknown>;
        const maxFiles = typeof cfg.__maxFiles === 'number' ? cfg.__maxFiles : 10000;
        Logger.info(`[Workspace] buildIndex for ${dirs.length} dirs (maxFiles=${maxFiles})`);
        const entries = await buildIndex(dirs, { maxFiles });
        Logger.info(`[Workspace] sending sourceDirs/set: ${entries.length} entries`);
        await session.customRequest('sourceDirs/set', { entries });
      } catch (e) {
        if (e instanceof IndexTooLargeError) {
          Logger.warn(`[Workspace] initial index too large, skipped`);
        } else {
          Logger.error('[Workspace] sourceDirs/set failed', e as Error);
        }
      }
    })();
  });
  _context.subscriptions.push(onSessionStart);

  const onSessionTerminate = vscode.debug.onDidTerminateDebugSession((session) => {
    if (session.configuration.type !== 'android-java') return;
    Logger.info(`[Workspace] onDidTerminateDebugSession id=${session.id}`);
  });
  _context.subscriptions.push(onSessionTerminate);

  Logger.info(`[Workspace] activated — buildIndex API ready`);

  return {
    async buildIndex(sourceDirs, options) {
      const maxFiles = options?.maxFiles ?? Number.POSITIVE_INFINITY;
      const started = Date.now();
      Logger.info(
        `[Workspace] buildIndex requested: ${sourceDirs.length} dir(s), maxFiles=${options?.maxFiles ?? 'unlimited'}`
      );
      Logger.info(`[Workspace]   dirs = ${JSON.stringify(sourceDirs)}`);
      try {
        const entries = await buildIndex(sourceDirs, { maxFiles });
        const elapsed = Date.now() - started;
        Logger.info(
          `[Workspace] buildIndex completed: ${entries.length} FQNs in ${elapsed}ms`
        );
        return entries;
      } catch (e) {
        if (e instanceof IndexTooLargeError) {
          Logger.warn(
            `[Workspace] buildIndex too large: ${e.totalFiles} files > ${e.maxFiles}`
          );
        } else {
          Logger.error(`[Workspace] buildIndex failed`, e as Error);
        }
        throw e;
      }
    },
  };
}

export function deactivate(): void {
}
