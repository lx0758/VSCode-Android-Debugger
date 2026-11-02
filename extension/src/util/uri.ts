// extension/src/util/uri.ts
//
// Canonical path model for cross-environment (local + Remote-SSH) workspaces:
//
//   - Source dirs are stored as URI strings (`uri.toString()`) so a
//     `vscode-remote://ssh-remote+host/home/liux/x` dir survives a Windows
//     UI host intact. `uri.fsPath` on a Windows host mangles remote URIs
//     into `\home\liux\x` and `path.resolve` then prefixes a fake `C:` —
//     the bug this module exists to prevent.
//
//   - Index entries and breakpoint payloads (what crosses the wire to the
//     Java adapter) use the "canonical key": `uri.fsPath` for `file:` URIs
//     (keeps the historical local behavior, e.g. `C:\x\y.java`) and
//     `uri.path` for every other scheme (e.g. `/home/liux/x.java`).
//     The adapter normalizes `vscode-remote://…` source paths down to the
//     same key, so all three sides agree.

import * as vscode from 'vscode';

/** Canonical file key: fsPath for file: URIs, uri.path otherwise. */
export function canonicalPath(uri: vscode.Uri): string {
  return uri.scheme === 'file' ? uri.fsPath : uri.path;
}

/** Parse a stored dir entry. Legacy entries are bare fsPath strings. */
export function parseDirEntry(entry: string): vscode.Uri {
  if (entry.includes('://')) {
    try {
      return vscode.Uri.parse(entry);
    } catch {
      // fall through to file-URI interpretation
    }
  }
  return vscode.Uri.file(entry);
}

/** Canonical form for stored dir entries (lowercase drive on Windows). */
export function normalizeDirUri(uri: vscode.Uri): vscode.Uri {
  if (uri.scheme !== 'file') return uri;
  const m = uri.path.match(/^\/([A-Za-z]):(\/|$)/);
  if (!m) return uri;
  return uri.with({ path: `/${m[1].toLowerCase()}${uri.path.slice(2)}` });
}

/** Upgrade a raw stored entry (URI string or legacy fsPath) to canonical form. */
export function normalizeDirEntry(entry: string): string {
  return normalizeDirUri(parseDirEntry(entry)).toString();
}

/** Human-facing path for log/UI messages. */
export function displayPath(uri: vscode.Uri): string {
  return uri.scheme === 'file' ? uri.fsPath : uri.path;
}

/**
 * The path form to hand BACK to VSCode (DAP source.path, BP payloads).
 * `file:` → fsPath (a local path VSCode resolves natively); every other
 * scheme → the full URI string, so a remote window gets
 * `vscode-remote://ssh-remote+host/...` back — a bare posix path would be
 * interpreted as a Windows-local path (`\home\...`) and never resolve.
 */
export function clientPath(uri: vscode.Uri): string {
  return uri.scheme === 'file' ? uri.fsPath : uri.toString();
}

function filePathsEqual(a: vscode.Uri, b: vscode.Uri): boolean {
  // Windows paths are case-insensitive (drive letter casing differs between
  // Uri.file() and VSCode-canonical explorer URIs).
  if (process.platform === 'win32') {
    return a.fsPath.toLowerCase() === b.fsPath.toLowerCase();
  }
  return a.fsPath === b.fsPath;
}

/** Strict equality of two dir URIs across environments. */
export function dirUriEquals(a: vscode.Uri, b: vscode.Uri): boolean {
  const na = normalizeDirUri(a);
  const nb = normalizeDirUri(b);
  if (na.scheme !== nb.scheme || na.authority !== nb.authority) return false;
  if (na.scheme === 'file') return filePathsEqual(na, nb);
  return na.path === nb.path;
}

/** Whether `child` equals or lives under `dir` (same scheme + authority). */
export function isUnderDir(child: vscode.Uri, dir: vscode.Uri): boolean {
  const nc = normalizeDirUri(child);
  const nd = normalizeDirUri(dir);
  if (nc.scheme !== nd.scheme || nc.authority !== nd.authority) return false;
  if (nc.scheme === 'file') {
    const c = nc.fsPath;
    const d = nd.fsPath;
    const cc = process.platform === 'win32' ? c.toLowerCase() : c;
    const dc = process.platform === 'win32' ? d.toLowerCase() : d;
    if (cc === dc) return true;
    const sep = process.platform === 'win32' ? '\\' : '/';
    return cc.startsWith(dc.endsWith(sep) ? dc : dc + sep);
  }
  if (nc.path === nd.path) return true;
  return nc.path.startsWith(nd.path.endsWith('/') ? nd.path : nd.path + '/');
}

/**
 * Every string form a dir may take in `when`-clause comparisons and legacy
 * lookups. Used for the `vscode-android-debugger.sourceDirPaths` context key so
 * `resourcePath in ...` matches regardless of which form VSCode provides.
 */
export function dirContextForms(uri: vscode.Uri): string[] {
  const forms = new Set<string>([normalizeDirUri(uri).toString()]);
  forms.add(uri.path);
  if (uri.fsPath) forms.add(uri.fsPath);
  return [...forms];
}
