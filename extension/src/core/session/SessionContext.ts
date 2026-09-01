// extension/src/core/session/SessionContext.ts
//
// Read-only view of a single debug session's runtime context. Consumers
// (the SessionManager broadcast loops, the BreakpointManager, the
// IndexManager) only need to know "which session / which device / which
// process" — they don't reach into the JVM lifecycle or the adb forward
// state. Those live behind the concrete `SessionContextImpl`.

import * as vscode from 'vscode';
import { DapServerProxy } from './DapServerProxy';

export interface DeviceInfo {
  serial: string;
  state?: string;
}

export interface ProcessInfo {
  pid: number;
  name?: string;
}

export interface SessionContext {
  readonly session: vscode.DebugSession;
  readonly device: DeviceInfo;
  readonly process: ProcessInfo;
  readonly dapPort: number;
  readonly jdwpPort: number;
  readonly dap: DapServerProxy;
}
