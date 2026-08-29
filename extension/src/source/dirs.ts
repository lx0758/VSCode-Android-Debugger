// extension/src/source/dirs.ts
import * as vscode from 'vscode';
import * as path from 'path';
import { Logger } from '../util/logger';

const STORE_KEY = 'sourceDirs';

export type SourceDirsChangeListener = () => void;

/**
 * Per-workspace store for marked source directories, backed by
 * `ExtensionContext.workspaceState` (VSCode Memento). Paths are stored as
 * absolute strings, deduplicated, and never written to git-trackable
 * settings. Each workspace has its own SQLite database (keyed by
 * workspace ID), so single-folder and multi-folder workspaces are
 * isolated automatically.
 *
 * Listeners receive a fire-and-forget notification whenever the list
 * changes; VSCode does NOT emit `onDidChangeConfiguration` for Memento
 * writes, so we use a custom EventEmitter instead.
 */
export class SourceDirsManager {
  private readonly emitter = new vscode.EventEmitter<void>();
  private migrated = false;

  constructor(private readonly store: vscode.Memento) {}

  onDidChange(listener: SourceDirsChangeListener): vscode.Disposable {
    return this.emitter.event(listener);
  }

  async getAllDirs(): Promise<string[]> {
    await this.migrateLegacyConfigIfNeeded();
    return Array.from(new Set(this.store.get<string[]>(STORE_KEY, []) ?? []));
  }

  contains(absolutePath: string): boolean {
    const list = this.store.get<string[]>(STORE_KEY, []) ?? [];
    const norm = path.resolve(absolutePath);
    return list.some((d) => path.resolve(d) === norm);
  }

  /**
   * If `absolutePath` is already covered by an existing source dir — i.e.,
   * an existing entry is `absolutePath` itself, or an ancestor of it —
   * return that covering entry's absolute path. Otherwise return undefined.
   * Used to reject marking a child of an already-marked parent.
   */
  findCoveringAncestor(absolutePath: string): string | undefined {
    const list = this.store.get<string[]>(STORE_KEY, []) ?? [];
    const norm = path.resolve(absolutePath);
    for (const d of list) {
      const existing = path.resolve(d);
      if (existing === norm) return existing;
      const rel = path.relative(existing, norm);
      // path.relative('a', 'a/b') returns 'b' (forward slash on all platforms).
      // path.relative('a/b', 'a') returns '..'. Anything starting with '..'
      // or being an absolute path means `existing` is NOT an ancestor.
      if (rel && !rel.startsWith('..') && !path.isAbsolute(rel)) {
        return existing;
      }
    }
    return undefined;
  }

  getDirsForUriSync(_uri: vscode.Uri): string[] {
    return (this.store.get<string[]>(STORE_KEY, []) ?? []).slice();
  }

  async addDir(_folder: vscode.WorkspaceFolder | undefined, absolutePath: string): Promise<void> {
    await this.migrateLegacyConfigIfNeeded();
    const norm = path.resolve(absolutePath);
    // Silently skip if already covered (exact match or ancestor already
    // marked). UI callers should check first via findCoveringAncestor() to
    // surface a friendly message; this guard protects direct callers.
    if (this.findCoveringAncestor(norm)) {
      return;
    }
    const current = this.store.get<string[]>(STORE_KEY, []) ?? [];
    await this.store.update(STORE_KEY, [...current, norm]);
    Logger.info(`[local] sourceDirs add: ${norm} (now ${current.length + 1} total)`);
    this.emitter.fire();
  }

  async removeDir(_folder: vscode.WorkspaceFolder | undefined, absolutePath: string): Promise<void> {
    await this.migrateLegacyConfigIfNeeded();
    const current = this.store.get<string[]>(STORE_KEY, []) ?? [];
    const norm = path.resolve(absolutePath);
    const updated = current.filter((d) => path.resolve(d) !== norm);
    if (updated.length === current.length) {
      return;
    }
    await this.store.update(STORE_KEY, updated);
    Logger.info(`[local] sourceDirs remove: ${norm} (now ${updated.length} total)`);
    this.emitter.fire();
  }

  /**
   * One-shot migration from legacy `ConfigurationTarget`-based storage
   * (`.vscode/settings.json` for single-folder, `.code-workspace` for
   * multi-folder) into Memento. Idempotent: if Memento already has entries
   * or no legacy config exists, this is a no-op. The legacy settings entry
   * is left intact so the user can verify the migration before manually
   * removing it.
   */
  private async migrateLegacyConfigIfNeeded(): Promise<void> {
    if (this.migrated) return;
    this.migrated = true;

    const existing = this.store.get<string[]>(STORE_KEY, []) ?? [];
    if (existing.length > 0) return;

    const folders = vscode.workspace.workspaceFolders ?? [];
    const migrated = new Set<string>();

    // Per-folder legacy config (`.vscode/settings.json`)
    for (const folder of folders) {
      try {
        const cfg = vscode.workspace.getConfiguration('debugger-for-android', folder.uri);
        const dirs = cfg.get<string[]>('sourceDirs', []);
        for (const r of dirs) {
          const abs = path.isAbsolute(r) ? r : path.resolve(folder.uri.fsPath, r);
          migrated.add(abs);
        }
      } catch {
        // ignore per-folder failures
      }
    }

    // Workspace-level legacy config (`.code-workspace` top-level `settings`)
    if (vscode.workspace.workspaceFile) {
      try {
        const cfg = vscode.workspace.getConfiguration('debugger-for-android');
        const dirs = cfg.get<string[]>('sourceDirs', []);
        for (const r of dirs) {
          if (path.isAbsolute(r)) {
            migrated.add(r);
            continue;
          }
          // `<folderName>/sub/path` form — resolve against the matching folder.
          const slash = r.indexOf('/');
          if (slash === -1) {
            const folder = folders.find((f) => f.name === r);
            if (folder) migrated.add(folder.uri.fsPath);
          } else {
            const folderName = r.slice(0, slash);
            const folder = folders.find((f) => f.name === folderName);
            if (folder) {
              migrated.add(path.resolve(folder.uri.fsPath, r.slice(slash + 1)));
            }
          }
        }
      } catch {
        // ignore workspace-level failures
      }
    }

    if (migrated.size === 0) return;

    await this.store.update(STORE_KEY, Array.from(migrated));
    Logger.info(
      `[local] migrated ${migrated.size} source dir(s) from legacy settings to memento ` +
        `(workspace: ${vscode.workspace.workspaceFile?.fsPath ?? folders[0]?.uri.fsPath ?? 'unknown'})`
    );
  }
}
