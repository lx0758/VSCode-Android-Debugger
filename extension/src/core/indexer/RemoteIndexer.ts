// extension/src/core/indexer/RemoteIndexer.ts
import { FqnEntry, IndexOptions, Indexer } from './Indexer';

/**
 * Minimal structural contract for the remote indexer channel.
 *
 * The workspace-side implementation (`remote/DebugRemoteApi(Impl).ts`) is
 * intentionally absent: the same-ID dual-host exports channel it relied on
 * is unreachable (VSCode loads this extension on the UI host only in
 * Remote-SSH windows, and cross-host `exports` are not available to
 * consumers). It will return as a dedicated `extensionKind: ["workspace"]`
 * helper extension (dual-extension architecture) — at that point replace
 * this interface with that extension's API type (likely a
 * `vscode.commands` based channel) and switch IndexerFactory to the
 * helper extension ID.
 */
export interface RemoteIndexApi {
  buildIndex(dirs: string[], options?: IndexOptions): Promise<FqnEntry[]>;
}

/**
 * Indexer strategy for Remote-SSH workspaces: forwards buildIndex to the
 * indexer running in the workspace host (real filesystem there) once the
 * helper extension exists. Unused today — LocalIndexer covers every
 * window via vscode.workspace.fs — but kept as the injection seam so the
 * future switch is a one-line change in IndexerFactory.
 */
export class RemoteIndexer implements Indexer {
  constructor(private readonly api: RemoteIndexApi) {}

  async buildIndex(sourceDirs: string[], options: IndexOptions = {}): Promise<FqnEntry[]> {
    return await this.api.buildIndex(sourceDirs, options);
  }

  dispose(): void {
    // The remote API object is owned by VSCode's extension registry;
    // nothing to release here.
  }
}
