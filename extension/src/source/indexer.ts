import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';

export interface FqnEntry {
  fqn: string;
  file: string;
}

export class IndexTooLargeError extends Error {
  constructor(
    public readonly totalFiles: number,
    public readonly maxFiles: number,
    public readonly estimatedSeconds: number
  ) {
    super(
      `Source dir contains ~${totalFiles} files which exceeds index.maxFiles=${maxFiles}. ` +
      `Estimated indexing time: ${estimatedSeconds}s.`
    );
    this.name = 'IndexTooLargeError';
  }
}

const JAVA_PKG_RE = /^\s*package\s+([\w.]+)\s*;/m;
const JAVA_CLASS_RE =
  /(?:public\s+|private\s+|protected\s+|static\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*\b(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)/g;

const KT_PKG_RE = /^\s*package\s+([\w.]+)/m;
const KT_CLASS_RE =
  /(?:public\s+|private\s+|protected\s+|internal\s+|abstract\s+|final\s+|sealed\s+|open\s+|data\s+|inner\s+|enum\s+|annotation\s+)*\b(?:class|object|interface)\s+([A-Za-z_$][\w$]*)/g;

const BATCH_SIZE = 50;
const PROGRESS_INTERVAL_MS = 1000;

export async function findSourceFiles(sourceDir: string): Promise<vscode.Uri[]> {
  const normalized = path.normalize(sourceDir);

  // 1) Fast path: sourceDir sits under a workspace folder. Use VSCode's
  //    file watcher / exclude patterns so we get incremental indexing.
  const folder = vscode.workspace.workspaceFolders?.find((f) => {
    const folderPath = path.normalize(f.uri.fsPath);
    return normalized === folderPath || normalized.startsWith(folderPath + path.sep);
  });
  if (folder) {
    const folderPath = path.normalize(folder.uri.fsPath);
    const rel = path.relative(folderPath, normalized);
    const baseUri = rel && !rel.startsWith('..') && rel !== '.'
      ? vscode.Uri.joinPath(folder.uri, rel)
      : folder.uri;
    return await vscode.workspace.findFiles(
      new vscode.RelativePattern(baseUri, '**/*.{java,kt}'),
      '**/build/**'
    );
  }

  // 2) Fallback: sourceDir is outside any workspace folder (typical for
  //    the UI-side cross-host fallback when the user points at a path
  //    outside the currently-opened VSCode window). Walk the directory
  //    ourselves with Node fs.
  Logger.warn(
    `[indexer] ${sourceDir} is not under any workspace folder; scanning via Node fs`
  );
  return await findSourceFilesViaFs(normalized);
}

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
const SOURCE_EXTS = new Set(['.java', '.kt']);

async function findSourceFilesViaFs(sourceDir: string): Promise<vscode.Uri[]> {
  const results: vscode.Uri[] = [];
  try {
    const st = await fs.promises.stat(sourceDir);
    if (!st.isDirectory()) return results;
  } catch (e) {
    Logger.warn(`[indexer] cannot stat ${sourceDir}: ${(e as Error).message}`);
    return results;
  }

  async function walk(dir: string): Promise<void> {
    let entries: fs.Dirent[];
    try {
      entries = await fs.promises.readdir(dir, { withFileTypes: true });
    } catch (e) {
      Logger.debug(`[indexer] cannot readdir ${dir}: ${(e as Error).message}`);
      return;
    }
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (SKIP_DIRS.has(entry.name)) continue;
        if (entry.name.startsWith('.')) continue;
        await walk(fullPath);
      } else if (entry.isFile()) {
        const ext = path.extname(entry.name).toLowerCase();
        if (SOURCE_EXTS.has(ext)) {
          results.push(vscode.Uri.file(fullPath));
        }
      }
    }
  }

  await walk(sourceDir);
  return results;
}

export function parseJavaFile(content: string, fileUri: vscode.Uri): string[] {
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
    Logger.debug(`[indexer] no java declarations in ${fileUri.fsPath}`);
  }
  return fqns;
}

export function parseKotlinFile(content: string, fileUri: vscode.Uri): string[] {
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
    Logger.debug(`[indexer] no kotlin declarations in ${fileUri.fsPath}`);
  }
  return fqns;
}

async function readAndParse(
  uri: vscode.Uri
): Promise<Array<{ fqn: string; file: string }>> {
  try {
    const bytes = await vscode.workspace.fs.readFile(uri);
    const text = new TextDecoder('utf-8').decode(bytes);
    const fqns =
      uri.fsPath.endsWith('.kt') ? parseKotlinFile(text, uri) : parseJavaFile(text, uri);
    return fqns.map((fqn) => ({ fqn, file: uri.fsPath }));
  } catch (e) {
    Logger.warn(
      `[indexer] read/parse failed, skipping: ${uri.fsPath} - ${(e as Error).message}`
    );
    return [];
  }
}

export interface BuildIndexOptions {
  progress?: vscode.Progress<{ message?: string }>;
  token?: vscode.CancellationToken;
  maxFiles?: number;
}

export async function buildIndex(
  sourceDirs: string[],
  options: BuildIndexOptions = {}
): Promise<FqnEntry[]> {
  const { progress, token, maxFiles } = options;
  const allUris: vscode.Uri[] = [];
  for (const dir of sourceDirs) {
    if (token?.isCancellationRequested) {
      throw new vscode.CancellationError();
    }
    const uris = await findSourceFiles(dir);
    Logger.info(`[indexer] ${dir}: found ${uris.length} files`);
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
    if (progress && (done - lastReportCount >= 50 || now - lastReportTime >= PROGRESS_INTERVAL_MS)) {
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
