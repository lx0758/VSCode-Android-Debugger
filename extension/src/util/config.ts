// extension/src/util/config.ts
import * as vscode from 'vscode';

export interface ExtensionConfig {
  adbPath: string;
  javaPath: string;
  indexMaxFiles: number;
}

export function getConfig(): ExtensionConfig {
  const cfg = vscode.workspace.getConfiguration('debugger-for-android');
  return {
    adbPath: cfg.get<string>('adbPath', 'auto'),
    javaPath: cfg.get<string>('javaPath', 'auto'),
    indexMaxFiles: Math.max(100, cfg.get<number>('index.maxFiles', 10000)),
  };
}
