// extension/src/ui/sourceDirsContext.ts
import * as vscode from 'vscode';
import { Logger } from '../util/logger';
import { dirContextForms, parseDirEntry } from '../util/uri';

export const SOURCE_DIR_PATHS_CONTEXT = 'debugger-for-android.sourceDirPaths';

/**
 * Publish marked dirs to the `when`-clause context. Entries arrive as URI
 * strings; each is expanded into every string form VSCode may compare
 * against (`resourcePath in <set>` in package.json menus): the full URI,
 * `uri.path` (remote form) and `uri.fsPath` (local form, including
 * Windows separators). Publishing all forms keeps the menu conditions
 * working in local and remote windows alike.
 */
export async function refreshSourceDirsContext(
  dirEntries: string[]
): Promise<void> {
  const forms = new Set<string>();
  for (const entry of dirEntries) {
    for (const form of dirContextForms(parseDirEntry(entry))) {
      forms.add(form);
    }
  }
  const list = [...forms];
  Logger.info(
    `[local] setContext ${SOURCE_DIR_PATHS_CONTEXT} = ${JSON.stringify(list)}`
  );
  await vscode.commands.executeCommand(
    'setContext',
    SOURCE_DIR_PATHS_CONTEXT,
    list
  );
}
