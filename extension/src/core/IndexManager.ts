// extension/src/core/IndexManager.ts
//
// In-memory source index. Owns the canonical `FqnEntry[]` snapshot
// for the current marked source dirs:
//   - rebuildFromDirs(): full rebuild (bridged from SourceDirManager
//     changes by the activation entry)
//   - autoIncludeFiles(): parse + merge files that get their first
//     breakpoint while outside the marked dirs (bridged from
//     BreakpointManager.onNewFileNeedsIndex)
// Consumers (SessionManager for fqnInfo-on-attach and mid-session
// broadcasts) subscribe to `onIndexReady` and read `getAllEntries()`.
// Like every manager, IndexManager never references another manager —
// all cross-manager traffic is callback-wired externally.

import * as vscode from 'vscode';
import { canonicalPath, clientPath } from '../util/uri';
import { FqnEntry } from './indexer/Indexer';
import { Indexer } from './indexer/Indexer';
import { parseJavaFile, parseKotlinFile } from './indexer/LocalIndexer';
import { Logger } from '../util/logger';

export type IndexReadyCallback = (entries: FqnEntry[]) => void;

export class IndexManager {
  private entries: FqnEntry[] = [];
  private readonly emitter = new vscode.EventEmitter<FqnEntry[]>();

  /**
   * @param indexer Strategy that actually walks + parses source dirs.
   *   Injected (not constructed here) so the activation entry picks the
   *   implementation once, via IndexerFactory. Managers never reach into
   *   the environment themselves.
   */
  constructor(private readonly indexer: Indexer) {}

  getAllEntries(): FqnEntry[] {
    return this.entries;
  }

  onIndexReady(cb: IndexReadyCallback): vscode.Disposable {
    return this.emitter.event(cb);
  }

  /**
   * Rebuild the index from the given marked source dirs. The activation
   * entry bridges SourceDirManager.onDidChange into this call — managers
   * never reference each other directly. Data side only; the UI refresh
   * (tree / decorations / status bar / context key) is UIManager's job.
   */
  async rebuildFromDirs(dirs: string[], maxFiles: number): Promise<void> {
    if (dirs.length === 0) {
      this.setEntries([]);
      return;
    }
    try {
      const entries = await this.indexer.buildIndex(dirs, { maxFiles });
      this.setEntries(entries);
    } catch (e) {
      Logger.error(`[index] rebuild failed`, e as Error);
    }
  }

  /**
   * Parse the given java/kotlin files and merge their FQN entries into the
   * snapshot. Fired by BreakpointManager when a breakpoint lands in a file
   * that isn't under a marked dir ("auto-include"). Reads go through
   * `vscode.workspace.fs`, which transparently reaches remote workspaces.
   */
  async autoIncludeFiles(uris: vscode.Uri[]): Promise<void> {
    const out: FqnEntry[] = [];
    for (const uri of uris) {
      if (!/\.(java|kt)$/.test(uri.path)) continue;
      try {
        const bytes = await vscode.workspace.fs.readFile(uri);
        const text = new TextDecoder('utf-8').decode(bytes);
        const fqns = uri.path.endsWith('.kt')
          ? parseKotlinFile(text, uri)
          : parseJavaFile(text, uri);
        for (const fqn of fqns) {
          out.push({
            fqn,
            file: canonicalPath(uri),
            ...(uri.scheme === 'file' ? {} : { sourceUri: uri.toString() }),
          });
        }
      } catch (e) {
        Logger.warn(`[index] parse ${uri.toString()} failed: ${(e as Error).message}`);
      }
    }
    this.mergeEntries(out);
  }

  /**
   * Replace the full index snapshot. The `rebuild` flow runs after
   * source-dir changes; it computes a fresh `FqnEntry[]` from the
   * marked dirs and calls `setEntries(entries)`.
   */
  setEntries(entries: FqnEntry[]): void {
    this.entries = entries;
    this.emitter.fire(entries);
  }

  /**
   * Merge newly-parsed entries (typically from auto-include when a BP
   * is set in an unindexed file). Fires onIndexReady so downstream
   * consumers can broadcast the merged snapshot.
   */
  mergeEntries(more: FqnEntry[]): void {
    if (more.length === 0) return;
    const seen = new Set(this.entries.map((e) => `${e.fqn}|${e.file}`));
    const merged = this.entries.slice();
    for (const e of more) {
      const key = `${e.fqn}|${e.file}`;
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(e);
    }
    this.setEntries(merged);
  }

  resolveByFqn(fqn: string): { file: string } | undefined {
    return this.entries.find((e) => e.fqn === fqn);
  }

  resolveFqnsByFile(file: string): string[] {
    return this.entries.filter((e) => e.file === file).map((e) => e.fqn);
  }

  dispose(): void {
    this.emitter.dispose();
    this.indexer.dispose();
  }
}
