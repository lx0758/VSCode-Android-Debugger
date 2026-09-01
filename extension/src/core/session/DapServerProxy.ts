// extension/src/core/session/DapServerProxy.ts
//
// Thin wrapper around `vscode.DebugSession.customRequest` for the two
// custom DAP requests the Java Adapter understands. Custom requests are
// `custom/`-prefixed on the wire so they can never collide with standard
// DAP commands; the proxy methods keep the clean names:
//   - `custom/syncBreakpoints` — full per-file breakpoint state
//   - `custom/syncSourceIndex` — full source index (fqn → file) replace
//
// Centralising these here (vs. scattered `session.customRequest(...)` calls)
// makes it easy to swap the wire format in one place and makes
// `SessionManager.broadcast*` loops trivial to read.

import * as vscode from 'vscode';
import { BreakpointFile } from '../BreakpointTypes';
import { FqnEntry } from '../indexer/Indexer';

export class DapServerProxy {
  constructor(private readonly session: vscode.DebugSession) {}

  syncBreakpoints(files: BreakpointFile[]): Promise<unknown> {
    return Promise.resolve(this.session.customRequest('custom/syncBreakpoints', { files }));
  }

  syncSourceIndex(entries: FqnEntry[]): Promise<unknown> {
    return Promise.resolve(this.session.customRequest('custom/syncSourceIndex', { entries }));
  }
}
