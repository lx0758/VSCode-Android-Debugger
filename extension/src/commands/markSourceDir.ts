import * as vscode from 'vscode';
import { SourceDirsManager } from '../source/dirs';
import { t } from '../i18n/strings';

export async function runMarkSourceDir(
  dirsManager: SourceDirsManager,
  contextUri?: vscode.Uri
): Promise<void> {
  const targetUri = await pickTargetUri(contextUri);
  if (!targetUri || !targetUri.fsPath) return;

  if (dirsManager.contains(targetUri.fsPath)) {
    vscode.window.showInformationMessage(t.alreadyDir(targetUri.fsPath));
    return;
  }

  const covering = dirsManager.findCoveringAncestor(targetUri.fsPath);
  if (covering) {
    vscode.window.showInformationMessage(
      t.dirCoveredByAncestor(targetUri.fsPath, covering)
    );
    return;
  }

  // folder is no longer needed — paths are stored as absolute strings
  await dirsManager.addDir(undefined, targetUri.fsPath);
  vscode.window.showInformationMessage(t.dirAdded(targetUri.fsPath), t.markUndo()).then(async (choice) => {
    if (choice === t.markUndo()) {
      await dirsManager.removeDir(undefined, targetUri.fsPath);
    }
  });
}

export async function runUnmarkSourceDir(
  dirsManager: SourceDirsManager,
  contextUri?: vscode.Uri
): Promise<void> {
  const targetUri = await pickTargetUri(contextUri);
  if (!targetUri || !targetUri.fsPath) return;

  if (!dirsManager.contains(targetUri.fsPath)) {
    vscode.window.showInformationMessage(t.notADir(targetUri.fsPath));
    return;
  }

  await dirsManager.removeDir(undefined, targetUri.fsPath);
  vscode.window.showInformationMessage(t.dirRemoved(targetUri.fsPath));
}

async function pickTargetUri(contextUri?: vscode.Uri): Promise<vscode.Uri | undefined> {
  if (contextUri && contextUri.fsPath) return contextUri;

  const active = vscode.window.activeTextEditor?.document.uri;
  if (active && active.fsPath) {
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