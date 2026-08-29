// extension/src/source/context.ts
import * as vscode from 'vscode';
import { Logger } from '../util/logger';

export const SOURCE_DIR_PATHS_CONTEXT = 'debugger-for-android.sourceDirPaths';

export async function refreshSourceDirsContext(
  absoluteDirs: string[]
): Promise<void> {
  // Keep native OS separators. VSCode's `resourcePath` in `when` clauses
  // uses the same form (Uri.fsPath), so on Windows both are `c:\foo\bar`
  // and string equality holds. Forward-slash normalization breaks the
  // `resourcePath in <contextSet>` lookup on Windows.
  Logger.info(
    `[local] setContext ${SOURCE_DIR_PATHS_CONTEXT} = ${JSON.stringify(absoluteDirs)}`
  );
  await vscode.commands.executeCommand(
    'setContext',
    SOURCE_DIR_PATHS_CONTEXT,
    absoluteDirs
  );
}