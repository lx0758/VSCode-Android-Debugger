// extension/src/ui/processPicker.ts
import * as vscode from 'vscode';
import { JdwpProcess } from '../adb/AdbDevice';
import { t } from '../i18n/strings';

export interface ProcessPickResult {
  pid: number;
  name: string;
}

/**
 * Hidden field on QuickPickItem so we can recover the PID of the selected
 * row without parsing it back out of `description`. Keeps `label` (the
 * process name) free for fuzzy matching against the user's input.
 */
interface ProcessPickItem extends vscode.QuickPickItem {
  __pid: number;
}

export async function pickProcess(processes: JdwpProcess[]): Promise<ProcessPickResult | undefined> {
  if (processes.length === 0) {
    return undefined;
  }
  const items: ProcessPickItem[] = processes.map((p) => ({
    label: p.name,
    description: t.pidDescription(p.pid),
    picked: false,
    __pid: p.pid,
  }));
  const pick = await vscode.window.showQuickPick<ProcessPickItem>(items, {
    title: t.processPickerTitle(),
    placeHolder: t.processPickerPlaceholder(),
    // Match against `label` only (the process name). Setting
    // matchOnDescription would also let the user fuzzy-match the PID
    // string, but PID is a number nobody memorises — process name is
    // what people actually recognise ("com.example.app" vs 7989).
    matchOnDescription: false,
  } as vscode.QuickPickOptions);
  if (!pick) {
    return undefined;
  }
  return { pid: pick.__pid, name: pick.label };
}
