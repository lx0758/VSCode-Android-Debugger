// extension/src/core/indexer/LocalIndexer.ts
import * as vscode from 'vscode';
import * as path from 'path';
import { Logger } from '../../util/logger';
import { t } from '../../i18n/strings';
import { canonicalPath, clientPath, displayPath, parseDirEntry } from '../../util/uri';
import { FqnEntry, IndexTooLargeError, IndexOptions, Indexer } from './Indexer';

const JAVA_PKG_RE = /^\s*package\s+([\w.]+)\s*;/m;
const JAVA_CLASS_RE =
  /(?:public\s+|private\s+|protected\s+|static\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*\b(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)/g;
// Single-type and on-demand imports; static imports fold to their FQN.
const JAVA_IMPORT_RE = /^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/gm;

const KT_PKG_RE = /^\s*package\s+([\w.]+)/m;
const KT_CLASS_RE =
  /(?:public\s+|private\s+|protected\s+|internal\s+|abstract\s+|final\s+|sealed\s+|open\s+|data\s+|inner\s+|enum\s+|annotation\s+)*\b(?:class|object|interface)\s+([A-Za-z_$][\w$]*)/g;
// `import x.y.Z` and `import x.y.*`; `as Alias` renames are folded away —
// the alias is dropped and the aliased FQN form is kept.
const KT_IMPORT_RE = /^\s*import\s+([\w.*]+)(?:\s+as\s+\w+)?\s*$/gm;

const BATCH_SIZE = 50;
const PROGRESS_INTERVAL_MS = 1000;
const SOURCE_EXTS = new Set(['.java', '.kt']);

const SKIP_DIRS = new Set([
  'build',
  'node_modules',
  '.gradle',
  '.idea',
  'target',
  'out',
  'bin',
  '.git',
]);

/**
 * Enumerate java/kotlin sources under `dirUri` as URIs. Two strategies:
 *   - Inside a workspace folder → `vscode.workspace.findFiles` with a
 *     RelativePattern on the dir URI. The glob runs on the FS provider
 *     host, which makes this the fast path for Remote-SSH workspaces
 *     (bulk listing happens on the remote, one result set round-trip).
 *   - Outside any workspace folder → recursive `vscode.workspace.fs`
 *     walk. This API transparently reaches the remote FS even when the
 *     extension host runs on the UI machine — unlike Node `fs`, which
 *     would only see the local disk.
 */
async function findSourceFiles(dirUri: vscode.Uri): Promise<vscode.Uri[]> {
  if (vscode.workspace.getWorkspaceFolder(dirUri)) {
    return await vscode.workspace.findFiles(
      new vscode.RelativePattern(dirUri, '**/*.{java,kt}'),
      '**/build/**'
    );
  }

  Logger.warn(
    `[indexer] ${displayPath(dirUri)} is not under any workspace folder; scanning via vscode.workspace.fs`
  );
  return await findSourceFilesViaFs(dirUri);
}

async function findSourceFilesViaFs(dirUri: vscode.Uri): Promise<vscode.Uri[]> {
  const results: vscode.Uri[] = [];

  try {
    const st = await vscode.workspace.fs.stat(dirUri);
    if (!(st.type & vscode.FileType.Directory)) return results;
  } catch (e) {
    Logger.warn(`[indexer] cannot stat ${displayPath(dirUri)}: ${(e as Error).message}`);
    return results;
  }

  async function walk(dir: vscode.Uri): Promise<void> {
    let entries: [string, vscode.FileType][];
    try {
      entries = await vscode.workspace.fs.readDirectory(dir);
    } catch (e) {
      Logger.debug(`[indexer] cannot readdir ${displayPath(dir)}: ${(e as Error).message}`);
      return;
    }
    for (const [name, type] of entries) {
      const child = vscode.Uri.joinPath(dir, name);
      if (type & vscode.FileType.Directory) {
        if (SKIP_DIRS.has(name)) continue;
        if (name.startsWith('.')) continue;
        await walk(child);
      } else if (type & vscode.FileType.File) {
        const ext = path.extname(name).toLowerCase();
        if (SOURCE_EXTS.has(ext)) {
          results.push(child);
        }
      }
    }
  }

  await walk(dirUri);
  return results;
}

export interface ParsedSourceFile {
  fqns: string[];
  imports: string[];
}

function extractImports(content: string, re: RegExp): string[] {
  const imports: string[] = [];
  re.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(content)) !== null) {
    if (m[1] && !imports.includes(m[1])) imports.push(m[1]);
  }
  return imports;
}

export function parseJavaFile(content: string, fileUri: vscode.Uri): ParsedSourceFile {
  const pkgMatch = content.match(JAVA_PKG_RE);
  const packageName = pkgMatch ? pkgMatch[1] : '';
  const fqns: string[] = [];
  JAVA_CLASS_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = JAVA_CLASS_RE.exec(content)) !== null) {
    const className = m[1];
    const fqn = packageName ? `${packageName}.${className}` : className;
    if (!fqns.some((f) => f.endsWith(`.${className}`))) {
      fqns.push(fqn);
    }
  }
  if (fqns.length === 0) {
    Logger.debug(`[indexer] no java declarations in ${displayPath(fileUri)}`);
  }
  return { fqns, imports: extractImports(content, JAVA_IMPORT_RE) };
}

export function parseKotlinFile(content: string, fileUri: vscode.Uri): ParsedSourceFile {
  const pkgMatch = content.match(KT_PKG_RE);
  const packageName = pkgMatch ? pkgMatch[1] : '';
  const fqns: string[] = [];
  KT_CLASS_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = KT_CLASS_RE.exec(content)) !== null) {
    const className = m[1];
    const fqn = packageName ? `${packageName}.${className}` : className;
    if (!fqns.some((f) => f.endsWith(`.${className}`))) {
      fqns.push(fqn);
    }
  }
  if (fqns.length === 0) {
    Logger.debug(`[indexer] no kotlin declarations in ${displayPath(fileUri)}`);
  }
  return { fqns, imports: extractImports(content, KT_IMPORT_RE) };
}

async function readAndParse(uri: vscode.Uri): Promise<FqnEntry[]> {
  try {
    const bytes = await vscode.workspace.fs.readFile(uri);
    const text = new TextDecoder('utf-8').decode(bytes);
    const parsed = uri.path.endsWith('.kt')
      ? parseKotlinFile(text, uri)
      : parseJavaFile(text, uri);
    if (parsed.imports.length > 0) {
      Logger.debug(
        `[indexer] FileEntry: ${displayPath(uri)} imports=[${parsed.imports.join(', ')}]`
      );
    }
    // Canonical key: fsPath for file: URIs, uri.path for vscode-remote etc.
    // sourceUri: the form VSCode resolves when the adapter echoes it back.
    return parsed.fqns.map((fqn) => ({
      fqn,
      file: canonicalPath(uri),
      ...(uri.scheme === 'file' ? {} : { sourceUri: uri.toString() }),
      ...(parsed.imports.length > 0 ? { imports: parsed.imports } : {}),
    }));
  } catch (e) {
    Logger.warn(
      `[indexer] read/parse failed, skipping: ${displayPath(uri)} - ${(e as Error).message}`
    );
    return [];
  }
}

export class LocalIndexer implements Indexer {
  /**
   * @param sourceDirs marked dirs as URI strings (see SourceDirManager).
   *   Legacy bare-fsPath entries are interpreted as file: URIs.
   */
  async buildIndex(sourceDirs: string[], options: IndexOptions = {}): Promise<FqnEntry[]> {
    const { progress, token, maxFiles } = options;
    const allUris: vscode.Uri[] = [];
    for (const dir of sourceDirs) {
      if (token?.isCancellationRequested) {
        throw new vscode.CancellationError();
      }
      const dirUri = parseDirEntry(dir);
      const uris = await findSourceFiles(dirUri);
      Logger.info(`[indexer] ${displayPath(dirUri)}: found ${uris.length} files`);
      allUris.push(...uris);
    }

    if (maxFiles !== undefined && allUris.length > maxFiles) {
      const estimatedSeconds = Math.ceil(allUris.length / 1000);
      throw new IndexTooLargeError(allUris.length, maxFiles, estimatedSeconds);
    }

    const entries: FqnEntry[] = [];
    let lastReportTime = Date.now();
    let lastReportCount = 0;

    for (let i = 0; i < allUris.length; i += BATCH_SIZE) {
      if (token?.isCancellationRequested) {
        throw new vscode.CancellationError();
      }
      const batch = allUris.slice(i, i + BATCH_SIZE);
      const batchResults = await Promise.all(batch.map(readAndParse));
      for (const arr of batchResults) {
        entries.push(...arr);
      }
      const done = Math.min(i + BATCH_SIZE, allUris.length);
      const now = Date.now();
      if (
        progress &&
        (done - lastReportCount >= 50 || now - lastReportTime >= PROGRESS_INTERVAL_MS)
      ) {
        progress.report({
          message: t.indexProgress(done, allUris.length, sourceDirs.length),
        });
        lastReportCount = done;
        lastReportTime = now;
      }
    }

    Logger.info(`[indexer] done: ${entries.length} FQNs from ${allUris.length} files`);
    return entries;
  }

  dispose(): void {
    // Local indexer holds no native resources.
  }
}
