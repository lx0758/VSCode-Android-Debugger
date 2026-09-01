// extension/src/commands/markSourceDir.ts
import * as vscode from 'vscode';
import { SourceDirsBridge } from '../ui/UiBridges';
import { t } from '../i18n/strings';
import { displayPath, parseDirEntry } from '../util/uri';

export async function runMarkSourceDir(
  dirs: SourceDirsBridge,
  contextUri?: vscode.Uri
): Promise<void> {
  const targetUri = await pickTargetUri(contextUri);
  if (!targetUri) return;
  const target = displayPath(targetUri);

  if (dirs.contains(targetUri)) {
    vscode.window.showInformationMessage(t.alreadyDir(target));
    return;
  }

  const covering = dirs.findCoveringAncestor(targetUri);
  if (covering) {
    vscode.window.showInformationMessage(
      t.dirCoveredByAncestor(target, displayPath(parseDirEntry(covering)))
    );
    return;
  }

  await dirs.addDir(targetUri);
  vscode.window.showInformationMessage(t.dirAdded(target), t.markUndo()).then(async (choice) => {
    if (choice === t.markUndo()) {
      await dirs.removeDir(targetUri);
    }
  });
}

export async function runUnmarkSourceDir(
  dirs: SourceDirsBridge,
  contextUri?: vscode.Uri
): Promise<void> {
  const targetUri = await pickTargetUri(contextUri);
  if (!targetUri) return;
  const target = displayPath(targetUri);

  // No contains() precondition: removeDir matches stale/legacy entries too,
  // so a wrongly-stored path can always be unmarked.
  const removed = await dirs.removeDir(targetUri);
  vscode.window.showInformationMessage(removed ? t.dirRemoved(target) : t.notADir(target));
}

async function pickTargetUri(contextUri?: vscode.Uri): Promise<vscode.Uri | undefined> {
  if (contextUri) return contextUri;

  const active = vscode.window.activeTextEditor?.document.uri;
  if (active) {
    return vscode.Uri.joinPath(active, '..');
  }

  const folders = vscode.workspace.workspaceFolders || [];
  if (folders.length === 0) {
    vscode.window.showWarningMessage(t.noWorkspaceFolderHint());
    return undefined;
  }
  if (folders.length === 1) return folders[0].uri;
  const picked = await vscode.window.showWorkspaceFolderPick({ placeHolder: t.pickWorkspaceFolderTitle() });
  return picked?.uri;
}
