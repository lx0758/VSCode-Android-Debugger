// extension/src/core/indexer/IndexerFactory.ts
import * as vscode from 'vscode';
import { LocalIndexer } from './LocalIndexer';
import { RemoteIndexer, RemoteIndexApi } from './RemoteIndexer';
import { Indexer } from './Indexer';

export interface IndexerFactoryOptions {
  extensionId: string;
  /** Invoked when the remote half is unavailable and we degrade to LocalIndexer. */
  onFallback?: () => void;
}

/**
 * Returns the Indexer appropriate for the current extension host:
 *  - Everything today: LocalIndexer — `vscode.workspace.fs`/findFiles reach
 *    the remote filesystem transparently, so one implementation covers
 *    local and Remote-SSH windows.
 *  - Future dual-extension architecture (kept as the injection seam): a
 *    dedicated `extensionKind: ["workspace"]` helper extension will run the
 *    indexer on the remote host natively. When it lands, point
 *    `extensionId` at the helper extension, obtain its `RemoteIndexApi`
 *    (cross-host channel — exports of a same-ID workspace-host instance
 *    are NOT reachable), and return `new RemoteIndexer(api)` here.
 *
 * The lookup below probes for such an API provider and degrades to
 * LocalIndexer when absent (today: always, because the workspace-side
 * implementation is intentionally absent — see remote/ removal notes).
 */
export function createIndexer(opts: IndexerFactoryOptions): Indexer {
  if (vscode.env.remoteName) {
    const ext = vscode.extensions.getExtension<RemoteIndexApi>(opts.extensionId);
    if (ext?.isActive && typeof ext.exports?.buildIndex === 'function') {
      return new RemoteIndexer(ext.exports);
    }
    // No remote indexer provider available. Degrade to LocalIndexer —
    // vscode.workspace.fs still reaches the remote filesystem from the UI
    // host, just slower — and tell the caller so the status bar can
    // surface the degraded mode.
    opts.onFallback?.();
  }
  return new LocalIndexer();
}
