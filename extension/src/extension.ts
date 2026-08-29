// extension/src/extension.ts
// VSCode dual-extension dispatcher.
//
// `extensionKind: ["ui", "workspace"]` makes VSCode load this extension on
// both the UI host and the workspace host. Each host runs a different
// code path:
//   - UI side       (kind=ui):       UI + adb + Java Adapter + remote buildIndex call
//   - Workspace side (kind=workspace): only exposes buildIndex() API
//
// Dispatch on `context.extension.extensionKind` (the host-effective kind)
// rather than `vscode.env.remoteName` — the latter is non-empty on the UI
// side too in a Remote-SSH window and would route the local side wrong.
//
// All sides share a single "Android Debugger" OutputChannel; logs are
// tagged with the side they came from so the two can be distinguished
// in the same channel.
import * as vscode from 'vscode';
import { Logger } from './util/logger';
import { t } from './i18n/strings';

export async function activate(context: vscode.ExtensionContext): Promise<unknown> {
  const kind = context.extension.extensionKind;
  const isUI = kind === vscode.ExtensionKind.UI;
  const tag = isUI ? 'DISPATCHER UI' : 'DISPATCHER Workspace';
  const sideTag = isUI ? 'UI' : 'Workspace';

  const outputChannel = vscode.window.createOutputChannel(t.outputChannel());
  Logger.initialize(outputChannel);
  outputChannel.show(true);

  Logger.info(`=========================================`);
  Logger.info(`[${tag}] activate() entered`);
  Logger.info(`[${tag}]   side (kind)        = ${sideTag} (UI=1, Workspace=2, raw=${kind})`);
  Logger.info(`[${tag}]   declared in pkg.json = ${JSON.stringify(
    (context.extension.packageJSON as { extensionKind?: unknown }).extensionKind
  )}`);
  Logger.info(`[${tag}]   extensionId        = ${context.extension.id}`);
  Logger.info(`[${tag}]   extensionPath      = ${context.extension.extensionPath}`);
  Logger.info(`[${tag}]   sessionId          = ${vscode.env.sessionId}`);
  Logger.info(
    `[${tag}]   remoteName         = "${vscode.env.remoteName || '<none>'}" ` +
      `(isRemoteWindow=${!!vscode.env.remoteName})`
  );
  Logger.info(`=========================================`);

  console.log(
    `[ADB Dispatcher] side=${sideTag} kind=${kind} ` +
      `sessionId=${vscode.env.sessionId} remoteName="${vscode.env.remoteName || '<none>'}"`
  );

  if (isUI) {
    Logger.info(`[${tag}] -> routing to UI side (extension.local.ts)`);
    try {
      const { activate: activateUI } = await import('./extension.local');
      await activateUI(context);
      Logger.info(`[${tag}] UI side activated OK`);
      return undefined;
    } catch (e) {
      const err = e as Error;
      Logger.error(`[${tag}] ERROR activating UI side`, err);
      throw e;
    }
  }

  Logger.info(`[${tag}] -> routing to Workspace side (extension.remote.ts)`);
  try {
    const { activate: activateWorkspace } = await import('./extension.remote');
    const api = activateWorkspace(context);
    Logger.info(`[${tag}] Workspace side activated OK`);
    return api;
  } catch (e) {
    const err = e as Error;
    Logger.error(`[${tag}] ERROR activating Workspace side`, err);
    throw e;
  }
}

export function deactivate(): void {
  // Sub-activate functions are responsible for their own teardown.
}
