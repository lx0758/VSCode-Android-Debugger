// extension/src/ui/UIManager.ts
//
// Owns every UI surface the extension contributes to the workbench:
//   - status bar (attach state + marked-dir count)
//   - "Android Debugger" source-dirs tree view
//   - file decorations (⚡ badge on marked dirs)
//   - the `debugger-for-android.sourceDirPaths` context key that drives
//     the explorer context menu (mark/unmark entries)
//   - all user-facing commands
//
// The UI layer never holds manager references: all data flows through
// the SourceDirsBridge / SessionBridge callbacks wired in extension.ts.
// Without the createTreeView call in here, VSCode renders the
// contributed view with "no data provider registered".
import * as vscode from 'vscode';
import { Logger } from '../util/logger';
import { StatusBarManager } from './sourceStatusBar';
import { SourceDirsTreeProvider, SourceDirItem } from './sourceDirsTreeView';
import { SourceDirDecorationProvider } from './sourceDirDecoration';
import { refreshSourceDirsContext } from './sourceDirsContext';
import { runMarkSourceDir, runUnmarkSourceDir } from '../commands/markSourceDir';
import { runAttachCommand, runRestartSessionCommand } from '../commands/attach';
import { SourceDirsBridge, SessionBridge } from './UiBridges';
import { t } from '../i18n/strings';

export interface UIManagerDeps {
  dirs: SourceDirsBridge;
  session: SessionBridge;
}

export class UIManager implements vscode.Disposable {
  readonly statusBar: StatusBarManager;

  private readonly decorationProvider: SourceDirDecorationProvider;
  private readonly treeProvider: SourceDirsTreeProvider;
  private readonly dirs: SourceDirsBridge;
  private readonly session: SessionBridge;
  private readonly disposables: vscode.Disposable[] = [];

  constructor(deps: UIManagerDeps) {
    this.dirs = deps.dirs;
    this.session = deps.session;

    this.statusBar = new StatusBarManager();
    this.statusBar.show();
    this.statusBar.setDirsProvider(deps.dirs.getDirs);

    this.decorationProvider = new SourceDirDecorationProvider(deps.dirs.contains);    this.treeProvider = new SourceDirsTreeProvider(deps.dirs.getDirs);

    this.disposables.push(
      this.statusBar,
      vscode.window.registerFileDecorationProvider(this.decorationProvider),
      vscode.window.createTreeView('debugger-for-android.sourceDirs', {
        treeDataProvider: this.treeProvider,
        showCollapseAll: false,
      }),
      // Any sourceDirs change must refresh every UI surface that mirrors it.
      deps.dirs.onDidChange(() => this.refreshAll())
    );

    // Set the `debugger-for-android.sourceDirPaths` context up-front so the
    // explorer context menu (unmark entry) reflects dirs marked in earlier
    // sessions right after activation.
    void this.refreshContextKey();
  }

  registerCommands(): void {
    let openExplorerHintShown = false;
    this.disposables.push(
      vscode.commands.registerCommand('debugger-for-android.markSourceDir', (uri?: vscode.Uri) => {
        void runMarkSourceDir(this.dirs, uri);
      }),
      vscode.commands.registerCommand('debugger-for-android.unmarkSourceDir', (uri?: vscode.Uri) => {
        void runUnmarkSourceDir(this.dirs, uri);
      }),
      vscode.commands.registerCommand('debugger-for-android.unmarkFromDir', (item: SourceDirItem) => {
        const uri = item?.resourceUri;
        if (!uri) return;
        void runUnmarkSourceDir(this.dirs, uri);
      }),
      vscode.commands.registerCommand(
        'debugger-for-android.revealInExplorer',
        async (item: SourceDirItem) => {
          if (!item?.resourceUri) return;
          await vscode.commands.executeCommand('workbench.view.explorer');
          await vscode.commands.executeCommand('revealInExplorer', item.resourceUri);
        }
      ),
      vscode.commands.registerCommand('debugger-for-android.openExplorer', async () => {
        // Primary: the auto-registered command for our extension-contributed
        // activity-bar view container.
        try {
          await vscode.commands.executeCommand(
            'workbench.view.extension.debugger-for-android-explorer'
          );
          return;
        } catch (e) {
          Logger.warn(
            `[UI] workbench.view.extension.debugger-for-android-explorer failed: ${(e as Error).message}`
          );
        }
        // Fallback: focus the activity bar so the user can click the icon,
        // plus a one-shot hint (see openExplorerFallback* strings).
        try {
          await vscode.commands.executeCommand('workbench.action.focusActivityBar');
        } catch {
          // ignore
        }
        if (!openExplorerHintShown) {
          openExplorerHintShown = true;
          const choice = await vscode.window.showInformationMessage(
            t.openExplorerFallbackHint(),
            t.openExplorerFallbackAction()
          );
          if (choice === t.openExplorerFallbackAction()) {
            await vscode.commands.executeCommand('workbench.view.explorer');
            await vscode.commands.executeCommand('debugger-for-android.markSourceDir');
          }
        }
      }),
      vscode.commands.registerCommand('debugger-for-android.attach', () => {
        void runAttachCommand({
          attachToProcess: (device, process) => this.session.attachToProcess(device, process),
          restartActiveSession: () => this.session.restartActiveSession(),
          statusBar: this.statusBar,
        });
      }),
      vscode.commands.registerCommand('debugger-for-android.restartSession', () => {
        void runRestartSessionCommand({
          attachToProcess: (device, process) => this.session.attachToProcess(device, process),
          restartActiveSession: () => this.session.restartActiveSession(),
          statusBar: this.statusBar,
        });
      })
    );
  }

  dispose(): void {
    for (const d of this.disposables) {
      d.dispose();
    }
  }

  private refreshAll(): void {
    this.decorationProvider.refresh();
    this.treeProvider.refresh();
    void this.statusBar.refreshCount();
    void this.refreshContextKey();
  }

  private async refreshContextKey(): Promise<void> {
    await refreshSourceDirsContext(await this.dirs.getDirs());
  }
}
