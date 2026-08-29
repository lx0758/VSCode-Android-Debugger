// extension/src/commands/restartSession.ts
import * as vscode from 'vscode';
import { Logger } from '../util/logger';
import { runAttachToPidCommand, AttachCommandDeps } from './attach';
import { t } from '../i18n/strings';

/**
 * Re-attach to the same target PID the user originally picked, doing a
 * full re-handshake from `forwardJdwp` onward. The user invokes this
 * via the "Android: Restart Debug Session" command (registered in
 * `extension.local.ts`).
 *
 * Why a custom command instead of intercepting VSCode's
 * `workbench.action.debug.restart`:
 *   - VSCode's restart is implemented as "disconnect + reconnect the
 *     same DAP session", which is fast but doesn't actually rebuild
 *     anything. Our factory's spawn-on-dead logic handles the JVM,
 *     but the adb forward would still be in whatever state the prior
 *     session left it. A full re-attach (new forward, new JVM, fresh
 *     index) is what "Restart" means to our users.
 *   - Overriding the global restart command would also disable
 *     restart for non-android-java debug sessions running in the
 *     same window.
 */
export async function runRestartSessionCommand(
  statusBar: import('../ui/statusBar').StatusBarManager,
  deps: AttachCommandDeps,
): Promise<void> {
  const session = vscode.debug.activeDebugSession;
  if (!session || session.configuration.type !== 'android-java') {
    vscode.window.showWarningMessage(t.restartNoActiveSession());
    return;
  }
  const cfg = session.configuration as Record<string, unknown>;
  const pid = cfg.__pid;
  if (typeof pid !== 'number') {
    Logger.warn(
      `[restart] session is missing __pid in configuration; was this started by an older extension version?`
    );
    vscode.window.showWarningMessage(t.restartNoPid());
    return;
  }

  Logger.info(`[restart] restarting session for PID ${pid}`);
  // Terminate the current one first — otherwise we'd have two sessions
  // competing for the same adapter port and the same adb forward.
  // `onDidTerminateDebugSession` does the atomic teardown (kills the
  // JVM, removes the adb forward), so by the time stopDebugging
  // resolves everything is clean for the re-attach below.
  await vscode.debug.stopDebugging(session);

  await runAttachToPidCommand(statusBar, deps, pid);
}