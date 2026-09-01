// extension/src/core/BreakpointManager.ts
import * as vscode from 'vscode';
import { canonicalPath, clientPath } from '../util/uri';
import {
  BreakpointEntry,
  BreakpointFile,
  BreakpointsChangeCallback,
  NewFileNeedsIndexCallback,
} from './BreakpointTypes';

export type { BreakpointEntry, BreakpointFile } from './BreakpointTypes';

/**
 * Owns the VSCode breakpoint side-channel:
 *   - registers `vscode.debug.onDidChangeBreakpoints` once
 *   - parses every change into a normalized `BreakpointFile[]` payload
 *     (canonical file keys — fsPath for local files, posix path for
 *     vscode-remote URIs — matching what the Java adapter's
 *     `custom/syncBreakpoints` request and the source index expect)
 *   - emits `onBreakpointsChanged(files)` whenever any BP add/remove/change
 *     touches one or more files; the consumers (SessionManager via the
 *     extension.ts bridge) decide what to do with it
 *   - emits `onNewFileNeedsIndex(uris)` when a BP is added in a
 *     Java/Kotlin file that isn't yet covered by a marked source dir;
 *     the consumer (IndexManager via the extension.ts bridge) re-indexes
 *     those files so subsequent setBreakpoints resolve correctly
 *
 * The manager itself does NOT push anything to the adapter — it only
 * surfaces the data. Pushing is the SessionManager's job (multi-session
 * aware: every active session gets a fresh `custom/syncBreakpoints`).
 */
export class BreakpointManager {
  private readonly bpEmitter = new vscode.EventEmitter<BreakpointFile[]>();
  private readonly indexEmitter = new vscode.EventEmitter<vscode.Uri[]>();
  private current: Map<string, BreakpointEntry[]> = new Map();
  /** canonical key → the form VSCode resolves (fsPath / full remote URI). */
  private clientPathByFile: Map<string, string> = new Map();

  constructor(
    context: vscode.ExtensionContext,
    /**
     * Bridge callback: tells whether a file URI is already covered by a
     * marked source dir. Injected so BreakpointManager never holds a
     * SourceDirManager reference (managers never depend on managers).
     */
    private readonly isPathMarked: (file: vscode.Uri) => boolean
  ) {
    this.refreshCurrent();
    context.subscriptions.push(
      vscode.debug.onDidChangeBreakpoints((e) => this.handleChange(e))
    );
    context.subscriptions.push(this.bpEmitter);
    context.subscriptions.push(this.indexEmitter);
  }

  onBreakpointsChanged(cb: BreakpointsChangeCallback): vscode.Disposable {
    return this.bpEmitter.event(cb);
  }

  onNewFileNeedsIndex(cb: NewFileNeedsIndexCallback): vscode.Disposable {
    return this.indexEmitter.event(cb);
  }

  /** Current snapshot of breakpoints per file (always includes disabled BPs). */
  getCurrentBreakpoints(): BreakpointFile[] {
    const files: BreakpointFile[] = [];
    for (const [path, breakpoints] of this.current) {
      files.push({ source: { path: this.clientPathByFile.get(path) ?? path }, breakpoints: [...breakpoints] });
    }
    return files;
  }

  private refreshCurrent(): void {
    const byFile = new Map<string, BreakpointEntry[]>();
    const clientPaths = new Map<string, string>();
    for (const bp of vscode.debug.breakpoints) {
      const entry = toEntry(bp);
      if (!entry) continue;
      const file = entry.file;
      if (!file) continue;
      if (entry.clientFilePath) clientPaths.set(file, entry.clientFilePath);
      let list = byFile.get(file);
      if (!list) {
        list = [];
        byFile.set(file, list);
      }
      list.push(entry.entry);
    }
    this.current = byFile;
    this.clientPathByFile = clientPaths;
  }

  private handleChange(e: vscode.BreakpointsChangeEvent): void {
    const urisToAdd: vscode.Uri[] = [];
    for (const added of e.added) {
      if (!(added instanceof vscode.SourceBreakpoint)) continue;
      const uri = added.location?.uri;
      if (!uri || !/\.(java|kt)$/.test(uri.path)) continue;
      if (this.isPathMarked(uri)) continue;
      urisToAdd.push(uri);
    }
    if (urisToAdd.length > 0) {
      const unique = new Map(urisToAdd.map((u) => [u.toString(), u]));
      this.indexEmitter.fire([...unique.values()]);
    }

    const affected = new Set<string>();
    for (const bp of [...e.added, ...e.removed, ...e.changed]) {
      const entry = toEntry(bp);
      if (entry?.file) affected.add(entry.file);
    }
    if (affected.size === 0) return;

    this.refreshCurrent();

    const files: BreakpointFile[] = [];
    for (const path of affected) {
      const list = this.current.get(path);
      files.push({
        source: { path: this.clientPathByFile.get(path) ?? path },
        breakpoints: list ? [...list] : [],
      });
    }
    this.bpEmitter.fire(files);
  }
}

function toEntry(bp: vscode.Breakpoint):
  | { entry: BreakpointEntry; file?: string; clientFilePath?: string }
  | undefined {
  if (!(bp instanceof vscode.SourceBreakpoint)) return undefined;
  const loc = bp.location;
  if (!loc?.uri) return undefined;
  // Canonical key (fsPath for file: URIs, uri.path for vscode-remote: etc.)
  // — must match the `file` field of index entries so the adapter can
  // resolve the same file in both directions.
  const file = canonicalPath(loc.uri);
  return {
    file,
    entry: {
      line: loc.range.start.line + 1,
      condition: bp.condition,
      logMessage: bp.logMessage,
      enabled: bp.enabled,
    },
    // What VSCode sent us in setBreakpoints (full remote URI in remote
    // windows, fsPath locally) — the adapter echoes this back verbatim.
    clientFilePath: clientPath(loc.uri),
  };
}
