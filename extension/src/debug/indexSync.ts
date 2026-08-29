// extension/src/debug/indexSync.ts
import * as vscode from 'vscode';
import { SourceDirsManager } from '../source/dirs';
import {
  FqnEntry,
  IndexTooLargeError,
  parseJavaFile,
  parseKotlinFile,
} from '../source/indexer';
import { Logger } from '../util/logger';

export interface IndexSyncOptions {
  maxFiles: number;
  extensionId: string;
  buildIndexWithProgress: (
    dirs: string[],
    onFallback: () => void,
    maxFiles: number,
    extensionId: string
  ) => Promise<FqnEntry[]>;
}

export class DebugIndexSync {
  private disposed = false;

  constructor(
    private readonly session: vscode.DebugSession,
    private readonly dirsManager: SourceDirsManager,
    private readonly opts: IndexSyncOptions
  ) {
  }

  async pushFullIndex(): Promise<void> {
    if (this.disposed) return;
    const dirs = await this.dirsManager.getAllDirs();
    // Don't skip when dirs is empty — we still need to push an empty
    // index so the adapter knows every previously-indexed source dir is
    // gone and can mark the BPs in those files as unverified (and tear
    // down their JDI requests). Without this, removing the last source
    // dir leaves stale JDI BPs that keep firing.
    try {
      const entries =
        dirs.length === 0
          ? []
          : await this.opts.buildIndexWithProgress(
              dirs,
              () => {},
              this.opts.maxFiles,
              this.opts.extensionId
            );
      if (this.disposed) return;
      Logger.info(`[indexSync] full re-index: ${entries.length} entries`);
      await this.session.customRequest('sourceDirs/set', { entries });
    } catch (e) {
      if (e instanceof IndexTooLargeError) {
        Logger.warn(
          `[indexSync] full re-index skipped (too large): ${e.totalFiles} files > ${e.maxFiles}`
        );
        return;
      }
      if (this.disposed) return;
      Logger.error('[indexSync] full re-index failed', e as Error);
    }
  }

  async addFiles(files: string[]): Promise<void> {
    if (this.disposed) return;
    const javaKt = files.filter((f) => /\.(java|kt)$/.test(f));
    if (javaKt.length === 0) return;

    const entries: FqnEntry[] = [];
    for (const file of javaKt) {
      try {
        const uri = vscode.Uri.file(file);
        const bytes = await vscode.workspace.fs.readFile(uri);
        const text = new TextDecoder('utf-8').decode(bytes);
        const fqns = file.endsWith('.kt')
          ? parseKotlinFile(text, uri)
          : parseJavaFile(text, uri);
        for (const fqn of fqns) {
          entries.push({ fqn, file });
        }
        Logger.info(`[indexSync] auto-included ${uri.fsPath} (${fqns.length} FQNs)`);
      } catch (e) {
        Logger.warn(`[indexSync] failed to parse ${file}: ${(e as Error).message}`);
      }
    }

    if (entries.length === 0) return;
    if (this.disposed) return;
    try {
      await this.session.customRequest('sourceDirs/add', { entries });
    } catch (e) {
      Logger.error('[indexSync] addFiles failed', e as Error);
    }
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
  }

  isDisposed(): boolean {
    return this.disposed;
  }
}
