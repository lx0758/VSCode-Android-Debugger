// extension/src/util/logger.ts
import * as vscode from 'vscode';

export class Logger {
  private static outputChannel: vscode.OutputChannel | undefined;

  static initialize(channel: vscode.OutputChannel): void {
    Logger.outputChannel = channel;
  }

  static info(message: string): void {
    Logger.outputChannel?.appendLine(`[INFO] ${message}`);
  }

  static warn(message: string): void {
    Logger.outputChannel?.appendLine(`[WARN] ${message}`);
  }

  static error(message: string, err?: Error): void {
    Logger.outputChannel?.appendLine(`[ERROR] ${message}`);
    if (err) {
      Logger.outputChannel?.appendLine(err.stack || err.message);
    }
  }

  static debug(message: string): void {
    if (process.env.VSCODE_DEBUG_TYPE) {
      Logger.outputChannel?.appendLine(`[DEBUG] ${message}`);
    }
  }
}
