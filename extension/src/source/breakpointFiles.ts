// extension/src/source/breakpointFiles.ts
import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';

export class BreakpointFileTracker {
  private trackedFiles: Set<string> = new Set();

  track(filePath: string): void {
    this.trackedFiles.add(filePath);
  }

  untrack(filePath: string): void {
    this.trackedFiles.delete(filePath);
  }

  getTracked(): string[] {
    return Array.from(this.trackedFiles);
  }

  async watchBreakpoints(): Promise<void> {
    const bpMap = vscode.debug.breakpoints;
    bpMap.forEach((bp) => {
      const source = (bp as vscode.SourceBreakpoint).location?.uri;
      if (source) {
        this.trackedFiles.add(source.fsPath);
      }
    });
    vscode.debug.onDidChangeBreakpoints((e) => {
      for (const added of e.added) {
        const source = (added as vscode.SourceBreakpoint).location?.uri;
        if (source) {
          this.trackedFiles.add(source.fsPath);
        }
      }
      for (const removed of e.removed) {
        const source = (removed as vscode.SourceBreakpoint).location?.uri;
        if (source) {
          this.trackedFiles.delete(source.fsPath);
        }
      }
    });
  }
}

export async function sendBreakpointFilesToAdapter(files: string[]): Promise<void> {
  for (const file of files) {
    if (fs.existsSync(file) && /\.(java|kt)$/.test(file)) {
      await vscode.commands.executeCommand('debugger-for-android.sendBreakpointFile', file);
    }
  }
}
