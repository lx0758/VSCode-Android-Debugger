// extension/src/ui/UiBridges.ts
//
// Bridge contracts between the UI layer and the core managers. UIManager
// (and the command handlers it wires) never hold manager references —
// everything flows through these callback interfaces, wired in
// extension.ts. DeviceInfo/ProcessInfo are type-only imports (data
// contracts, not manager dependencies).
import * as vscode from 'vscode';
import type { DeviceInfo, ProcessInfo } from '../core/session/SessionContext';

/**
 * Everything the UI needs from SourceDirManager, expressed as callbacks.
 * Dirs flow as URI strings (`uri.toString()`); UI-side lookups take URIs.
 */
export interface SourceDirsBridge {
  /** All marked dirs (URI strings). */
  getDirs(): Promise<string[]>;
  /** Whether the dir is already a marked dir (exact match). */
  contains(dir: vscode.Uri): boolean;
  /** Covering marked ancestor of the dir, if any (URI string). */
  findCoveringAncestor(dir: vscode.Uri): string | undefined;
  addDir(dir: vscode.Uri): Promise<void>;
  /** Tolerant remove: matches stale/legacy entries too. Returns true when removed. */
  removeDir(dir: vscode.Uri): Promise<boolean>;
  /** Whether a file URI is covered by any marked dir (auto-include gate). */
  isPathMarked(file: vscode.Uri): boolean;
  /** Fire-and-forget change notification (drives UI refresh). */
  onDidChange(cb: () => void): vscode.Disposable;
}

/**
 * Everything the UI needs from SessionManager, expressed as callbacks.
 */
export interface SessionBridge {
  attachToProcess(device: DeviceInfo, process: ProcessInfo): Promise<boolean>;
  restartActiveSession(): Promise<void>;
}
