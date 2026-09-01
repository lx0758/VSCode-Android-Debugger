import * as vscode from 'vscode';
import { t } from '../i18n/strings';
import { displayPath, parseDirEntry } from '../util/uri';

export class SourceDirsTreeProvider implements vscode.TreeDataProvider<SourceDirItem> {

  private emitter = new vscode.EventEmitter<SourceDirItem | undefined>();
  onDidChangeTreeData = this.emitter.event;

  /**
   * Bridge callback: supplies the marked-dirs snapshot (URI strings).
   * The provider never sees a SourceDirManager reference.
   */
  constructor(private getDirs: () => Promise<string[]>) {}

  refresh(): void {
    this.emitter.fire(undefined);
  }

  getTreeItem(element: SourceDirItem): vscode.TreeItem {
    return element;
  }

  async getChildren(): Promise<SourceDirItem[]> {
    const dirs = await this.getDirs();
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
    return dirs.map((entry) => {
      const uri = parseDirEntry(entry);
      const label = vscode.workspace.asRelativePath(uri, false);
      const item = new SourceDirItem(label, vscode.TreeItemCollapsibleState.None, 'dir', uri);
      item.description = label;
      item.tooltip = displayPath(uri);
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