// extension/src/commands/attach.ts
//
// attach.ts no longer manages ports / forwards / JVM spawns — it just
// does the user-facing UI (device + process picker) and delegates the
// attach through the `attachToProcess` bridge callback (wired to
// SessionManager in extension.ts). The SessionManager's factory lambda
// then constructs a per-session `SessionContextImpl` inside
// `createDebugAdapterDescriptor` and returns its DAP port.
//
// For restart, the `restartActiveSession` bridge callback is wired up
// via the existing `vscode-android-debugger.restartSession` command.

import * as vscode from 'vscode';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';
import { DefaultAdbRunner, AdbRunner } from '../adb/AdbRunner';
import { pickProcess } from '../ui/selectProcessPicker';
import { pickDevice } from '../ui/selectDevicePicker';
import { StatusBarManager } from '../ui/sourceStatusBar';
import type { DeviceInfo, ProcessInfo } from '../core/session/SessionContext';

export interface AttachCommandDeps {
  /** Bridge callbacks — the command layer never holds a SessionManager. */
  attachToProcess(device: DeviceInfo, process: ProcessInfo): Promise<boolean>;
  restartActiveSession(): Promise<void>;
  statusBar: StatusBarManager;
}

export async function runAttachCommand(deps: AttachCommandDeps): Promise<void> {
  const { statusBar } = deps;

  statusBar.setState('attaching', t.attachCheckingDevices());

  // Pick a device via plain adb (no session-specific serial yet — the
  // device picker runs BEFORE any session exists, so the runner
  // doesn't need a `-s` argument). adb resolution happens lazily inside
  // the runner on first use.
  const runner: AdbRunner = new DefaultAdbRunner();
  const { AdbDeviceFinder } = await import('../adb/AdbDeviceFinder');
  const devices = await new AdbDeviceFinder(runner).findAll();
  if (devices.length === 0) {
    vscode.window.showErrorMessage(t.noDevices());
    statusBar.setState('idle');
    return;
  }
  const ready = devices.filter((d) => d.state === 'device');
  if (ready.length === 0) {
    vscode.window.showErrorMessage(t.devicesNotReady(devices.map((d) => `${d.serial}=${d.state}`).join(', ')));
    statusBar.setState('idle');
    return;
  }

  statusBar.setState('attaching', t.attachEnumerating());
  // Single ready device: skip the picker entirely. The serial is logged so
  // multi-device setups stay auditable in the output channel.
  const device =
    ready.length === 1 ? ready[0] : await pickDevice(ready);
  if (!device) {
    statusBar.setState('idle');
    return;
  }
  if (ready.length === 1) {
    Logger.info(`[attach] single device auto-selected: ${device.serial}`);
  }

  // device is an AdbDevice — process enumeration and the JDWP forward
  // are all scoped to it via `-s <serial>`.
  const processes = await device.listJdwpProcesses();
  if (processes.length === 0) {
    vscode.window.showErrorMessage(t.noJdwpProcesses());
    statusBar.setState('idle');
    return;
  }

  const pick = await pickProcess(processes);
  if (!pick) {
    statusBar.setState('idle');
    return;
  }

  const ok = await deps.attachToProcess(
    { serial: device.serial, state: device.state },
    { pid: pick.pid, name: pick.name }
  );
  if (!ok) {
    vscode.window.showErrorMessage(t.attachFail('startDebugging returned false'));
    statusBar.setState('error', 'startDebugging failed');
    return;
  }
  Logger.info(t.attachSuccessLog(pick.pid, 0));
}

/**
 * Restart the active Android debug session. Thin wrapper around the
 * `restartActiveSession` bridge callback that updates the status bar.
 */
export async function runRestartSessionCommand(deps: AttachCommandDeps): Promise<void> {
  const { statusBar } = deps;
  statusBar.setState('attaching', t.attachPreparing());
  await deps.restartActiveSession();
}
