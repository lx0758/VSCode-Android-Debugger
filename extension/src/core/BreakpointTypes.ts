// extension/src/core/BreakpointTypes.ts
//
// Shared data contracts for the breakpoint side-channel. Kept separate
// from BreakpointManager so consumers (SessionManager, DapServerProxy)
// can import the wire types without referencing the manager itself —
// managers never depend on managers, not even for types.

import * as vscode from 'vscode';

export interface BreakpointEntry {
  line: number;
  condition?: string;
  logMessage?: string;
  /**
   * Mirrors VSCode's `Breakpoint.enabled`. Defaults to true on the adapter
   * side when absent (back-compat with older senders). The extension
   * ALWAYS sends the actual value so the adapter can run the state machine:
   *   enabled=true  → ACTIVE  (install/reuse JDI request)
   *   enabled=false → DISABLED (remove JDI request, KEEP the id)
   * A BP not present in the payload is treated as REMOVED (clear id too).
   */
  enabled: boolean;
}

export interface BreakpointFile {
  source: { path: string };
  breakpoints: BreakpointEntry[];
}

export type BreakpointsChangeCallback = (files: BreakpointFile[]) => void;
/** Emitted with the URIs of Java/Kotlin files that need auto-including. */
export type NewFileNeedsIndexCallback = (uris: vscode.Uri[]) => void;
