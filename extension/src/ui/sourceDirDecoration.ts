// extension/src/ui/sourceDirDecoration.ts
import * as vscode from 'vscode';
import { t } from '../i18n/strings';

export class SourceDirDecorationProvider implements vscode.FileDecorationProvider {

  private emitter = new vscode.EventEmitter<vscode.Uri | undefined>();
  onDidChangeFileDecorations = this.emitter.event;

  /**
   * Bridge callback: marks whether a URI is a marked source dir. The
   * provider never sees a SourceDirManager reference.
   */
  constructor(private isMarked: (uri: vscode.Uri) => boolean) {}

  refresh(): void {
    this.emitter.fire(undefined);
  }

  provideFileDecoration(uri: vscode.Uri): vscode.ProviderResult<vscode.FileDecoration> {
    if (this.isMarked(uri)) {
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
