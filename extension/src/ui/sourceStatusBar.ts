// extension/src/ui/statusBar.ts
import * as vscode from 'vscode';
import { t } from '../i18n/strings';

export type DebugState = 'idle' | 'indexing' | 'attaching' | 'attached' | 'error';
export type IndexerMode = 'fast' | 'fallback' | 'unknown';

export class StatusBarManager {
  private item: vscode.StatusBarItem;
  private countItem: vscode.StatusBarItem;
  private indexerModeItem: vscode.StatusBarItem;
  private getDirs: (() => Promise<string[]>) | undefined;

  constructor() {
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    this.countItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 1);
    this.indexerModeItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 2);
    this.countItem.command = 'vscode-android-debugger.openExplorer';
    this.countItem.tooltip = t.sourceDirsCountHint();
  }

  show(): void {
    this.item.show();
    this.countItem.show();
    this.setIndexerMode('unknown');
    this.setState('idle');
  }

  /** Bridge callback: supplies the marked-dirs snapshot for the count item. */
  setDirsProvider(provider: () => Promise<string[]>): void {
    this.getDirs = provider;
    this.refreshCount();
  }

  async refreshCount(): Promise<void> {
    if (!this.getDirs) {
      this.countItem.hide();
      return;
    }
    const dirs = await this.getDirs();
    const count = dirs.length;
    const useZh = vscode.env.language.startsWith('zh');
    this.countItem.text = useZh
      ? `$(zap) ${t.sourceDirsCountZh(count)}`
      : `$(zap) ${count} ${t.sourceDirsCountWord()}`;
    if (count === 0) {
      this.countItem.tooltip = t.sourceDirsEmptyHint();
      this.countItem.backgroundColor = undefined;
    } else {
      this.countItem.tooltip = t.sourceDirsListTooltip(dirs);
      this.countItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
    }
  }

  setState(state: DebugState, message?: string): void {
    switch (state) {
      case 'idle':
        this.item.text = `$(debug-disconnect) ${t.statusBarIdle()}`;
        this.item.tooltip = t.statusBarIdleTooltip();
        this.item.command = 'vscode-android-debugger.attach';
        this.hideIndexerMode();
        break;
      case 'indexing':
        this.item.text = `$(loading) ${t.statusBarIndexing(message || t.statusBarIdleDefault())}`;
        this.item.tooltip = t.statusBarIndexingTooltip();
        this.item.command = undefined;
        break;
      case 'attaching':
        this.item.text = `$(sync~spin) ${t.statusBarAttaching(message || t.statusBarAttachingDefault())}`;
        this.item.tooltip = t.statusBarAttachingTooltip();
        this.item.command = undefined;
        break;
      case 'attached':
        this.item.text = `$(debug-breakpoint) ${t.statusBarAttached(message || t.statusBarAttachedDefault())}`;
        this.item.tooltip = t.statusBarAttachedTooltip();
        this.item.command = undefined;
        break;
      case 'error':
        this.item.text = `$(error) ${t.statusBarError(message || t.statusBarErrorDefault())}`;
        this.item.tooltip = t.statusBarErrorTooltip();
        this.item.command = undefined;
        break;
    }
  }

  setIndexerMode(mode: IndexerMode): void {
    if (mode === 'unknown') {
      this.hideIndexerMode();
      return;
    }
    if (mode === 'fast') {
      this.indexerModeItem.text = t.statusBarIndexerFast();
      this.indexerModeItem.tooltip = t.statusBarIndexerFastTooltip();
      this.indexerModeItem.backgroundColor = undefined;
    } else {
      this.indexerModeItem.text = t.statusBarIndexerFallback();
      this.indexerModeItem.tooltip = t.statusBarIndexerFallbackTooltip();
      this.indexerModeItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
    }
    this.indexerModeItem.show();
  }

  hideIndexerMode(): void {
    this.indexerModeItem.hide();
  }

  dispose(): void {
    this.item.dispose();
    this.countItem.dispose();
    this.indexerModeItem.dispose();
  }
}
