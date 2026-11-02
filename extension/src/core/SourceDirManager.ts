// extension/src/core/SourceDirManager.ts
import * as vscode from 'vscode';
import * as path from 'path';
import { Logger } from '../util/logger';
import {
  dirUriEquals,
  displayPath,
  isUnderDir,
  normalizeDirEntry,
  normalizeDirUri,
  parseDirEntry,
} from '../util/uri';

const STORE_KEY = 'sourceDirs';

export type DirsChangeCallback = () => void;

/**
 * Per-workspace store for marked source directories, backed by
 * `ExtensionContext.workspaceState` (VSCode Memento). Entries are stored as
 * URI strings (`vscode-remote://ssh-remote+host/home/liux/x`,
 * `file:///c:/x/y`) so remote dirs survive a Windows UI host intact —
 * `uri.fsPath` would mangle them into unresolvable `C:\home\...` paths.
 * Legacy entries (bare fsPath) are upgraded to URI form on first read.
 *
 * Listeners receive a fire-and-forget notification whenever the list
 * changes; VSCode does NOT emit `onDidChangeConfiguration` for Memento
 * writes, so we use a custom EventEmitter instead.
 */
export class SourceDirManager {
  private readonly emitter = new vscode.EventEmitter<void>();
  private migrated = false;

  constructor(private readonly store: vscode.Memento) {}

  onDidChange(listener: DirsChangeCallback): vscode.Disposable {
    return this.emitter.event(listener);
  }

  /** All marked dirs, as canonical URI strings (legacy entries upgraded). */
  async getAllDirs(): Promise<string[]> {
    await this.migrateLegacyConfigIfNeeded();
    return await this.upgradeAndRead();
  }

  /** Exact-match check (mark/unmark dedup), URI-aware. */
  contains(dir: vscode.Uri): boolean {
    return this.storedUris().some((d) => dirUriEquals(d, dir));
  }

  /**
   * If `dir` is already covered by an existing entry — i.e., an entry is
   * `dir` itself or an ancestor of it — return that entry (URI string).
   * Used to reject marking a child of an already-marked parent.
   */
  findCoveringAncestor(dir: vscode.Uri): string | undefined {
    for (const d of this.storedUris()) {
      if (dirUriEquals(d, dir) || isUnderDir(dir, d)) {
        return d.toString();
      }
    }
    return undefined;
  }

  /** Whether a file URI is covered by any marked dir (auto-include gate). */
  isPathMarked(file: vscode.Uri): boolean {
    return this.storedUris().some((d) => isUnderDir(file, d));
  }

  async addDir(dir: vscode.Uri): Promise<void> {
    await this.migrateLegacyConfigIfNeeded();
    const target = normalizeDirUri(dir);
    if (this.findCoveringAncestor(target)) {
      return;
    }
    const current = await this.upgradeAndRead();
    if (current.includes(target.toString())) {
      return;
    }
    await this.store.update(STORE_KEY, [...current, target.toString()]);
    Logger.info(`[local] sourceDirs add: ${displayPath(target)} (now ${current.length + 1} total)`);
    this.emitter.fire();
  }

  /**
   * Remove every entry matching `target` under any of its string forms
   * (URI string / path / fsPath, Windows case-insensitive). Returns true
   * when something was actually removed — deliberately tolerant of stale
   * or wrongly-stored entries so unmark always works.
   */
  async removeDir(target: vscode.Uri): Promise<boolean> {
    await this.migrateLegacyConfigIfNeeded();
    const current = await this.upgradeAndRead();
    const normalizedTarget = normalizeDirUri(target);
    const targetForms = new Set(
      [normalizedTarget.toString(), target.toString(), target.path, target.fsPath]
        .map((s) => s.toLowerCase())
    );
    const resolvedTargetFs = path.resolve(target.fsPath).toLowerCase();
    const updated = current.filter((entry) => {
      const uri = parseDirEntry(entry);
      if (targetForms.has(uri.toString().toLowerCase())) return false;
      if (dirUriEquals(uri, normalizedTarget)) return false;
      try {
        if (path.resolve(uri.fsPath).toLowerCase() === resolvedTargetFs) return false;
      } catch {
        // fsPath unavailable for exotic schemes — URI comparison above suffices
      }
      return true;
    });
    if (updated.length === current.length) {
      Logger.info(`[local] sourceDirs remove: no entry matched ${displayPath(normalizedTarget)}`);
      return false;
    }
    await this.store.update(STORE_KEY, updated);
    Logger.info(
      `[local] sourceDirs remove: ${displayPath(normalizedTarget)} (now ${updated.length} total)`
    );
    this.emitter.fire();
    return true;
  }

  /** Read + upgrade legacy bare-fsPath entries to URI strings (persists once). */
  private async upgradeAndRead(): Promise<string[]> {
    const raw = this.store.get<string[]>(STORE_KEY, []) ?? [];
    let changed = false;
    const upgraded: string[] = [];
    const seen = new Set<string>();
    for (const entry of raw) {
      const next = normalizeDirEntry(entry);
      if (next !== entry) changed = true;
      if (!seen.has(next)) {
        seen.add(next);
        upgraded.push(next);
      }
    }
    if (changed) {
      await this.store.update(STORE_KEY, upgraded);
      Logger.info(`[local] sourceDirs upgraded ${raw.length} entr(y/ies) to URI form`);
    }
    return upgraded;
  }

  /** Current entries as parsed URIs (best-effort, no persistence). */
  private storedUris(): vscode.Uri[] {
    return (this.store.get<string[]>(STORE_KEY, []) ?? []).map(parseDirEntry);
  }

  private async migrateLegacyConfigIfNeeded(): Promise<void> {
    if (this.migrated) return;
    this.migrated = true;

    const existing = this.store.get<string[]>(STORE_KEY, []) ?? [];
    if (existing.length > 0) return;

    const folders = vscode.workspace.workspaceFolders ?? [];
    const migrated = new Set<string>();

    for (const folder of folders) {
      try {
        const cfg = vscode.workspace.getConfiguration('vscode-android-debugger', folder.uri);
        const dirs = cfg.get<string[]>('sourceDirs', []);
        for (const r of dirs) {
          const abs = path.isAbsolute(r) ? r : path.resolve(folder.uri.fsPath, r);
          migrated.add(abs);
        }
      } catch {
        // ignore per-folder failures
      }
    }

    if (vscode.workspace.workspaceFile) {
      try {
        const cfg = vscode.workspace.getConfiguration('vscode-android-debugger');
        const dirs = cfg.get<string[]>('sourceDirs', []);
        for (const r of dirs) {
          if (path.isAbsolute(r)) {
            migrated.add(r);
            continue;
          }
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
