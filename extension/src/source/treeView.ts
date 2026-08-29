import * as vscode from 'vscode';
import { SourceDirsManager } from './dirs';
import { t } from '../i18n/strings';

export class SourceDirsTreeProvider implements vscode.TreeDataProvider<SourceDirItem> {

  private emitter = new vscode.EventEmitter<SourceDirItem | undefined>();
  onDidChangeTreeData = this.emitter.event;

  constructor(private dirsManager: SourceDirsManager) {}

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: SourceDirItem): vscode.TreeItem {
    return element;
  }

  async getChildren(): Promise<SourceDirItem[]> {
    const dirs = await this.dirsManager.getAllDirs();
    if (dirs.length === 0) {
      const placeholder = new SourceDirItem(
        t.sourceDirsEmpty(),
        vscode.TreeItemCollapsibleState.None,
        'placeholder'
      );
      placeholder.description = t.sourceDirsHint();
      placeholder.iconPath = new vscode.ThemeIcon('info');
      return [placeholder];
    }
    return dirs.map((r) => {
      const label = vscode.workspace.asRelativePath(r, false);
      const item = new SourceDirItem(label, vscode.TreeItemCollapsibleState.None, 'dir', vscode.Uri.file(r));
      item.description = label;
      item.tooltip = r;
      item.iconPath = new vscode.ThemeIcon('debug-breakpoint', new vscode.ThemeColor('charts.red'));
      return item;
    });
  }
}

export class SourceDirItem extends vscode.TreeItem {
  constructor(
    public readonly label: string,
    public readonly collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly contextValue: string,
    public readonly resourceUri?: vscode.Uri
  ) {
    super(label, collapsibleState);
    this.contextValue = contextValue;
  }
}