import * as vscode from 'vscode';
import { SourceDirsManager } from './dirs';
import { t } from '../i18n/strings';

export class SourceDirDecorationProvider implements vscode.FileDecorationProvider {

  private emitter = new vscode.EventEmitter<vscode.Uri | undefined>();
  onDidChangeFileDecorations = this.emitter.event;

  constructor(private dirsManager: SourceDirsManager) {}

  refresh(): void {
    this.emitter.fire(undefined);
  }

  provideFileDecoration(uri: vscode.Uri): vscode.ProviderResult<vscode.FileDecoration> {
    if (!uri.fsPath) return undefined;
    if (this.dirsManager.contains(uri.fsPath)) {
      return {
        badge: '⚡',
        tooltip: t.sourceDirsDecorationTooltip(),
        color: new vscode.ThemeColor('charts.red'),
        suffix: ' ← debug',
        propagate: true,
      } as vscode.FileDecoration;
    }
    return undefined;
  }
}