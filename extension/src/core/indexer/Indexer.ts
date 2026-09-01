// extension/src/core/indexer/Indexer.ts
import * as vscode from 'vscode';

export interface FqnEntry {
  fqn: string;
  /**
   * Canonical file key (fsPath for file: URIs, posix path for remote) —
   * matches the adapter's normalized breakpoint keys.
   */
  file: string;
  /**
   * The form VSCode resolves when the adapter echoes it back as DAP
   * `source.path`: full URI string for non-file schemes (remote windows),
   * omitted for file: URIs (the canonical key already IS the local path).
   */
  sourceUri?: string;
}

export interface SourceEntry {
  fqn: string;
  file: string;
}

export interface IndexOptions {
  progress?: vscode.Progress<{ message?: string }>;
  token?: vscode.CancellationToken;
  maxFiles?: number;
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

export interface Indexer {
  buildIndex(sourceDirs: string[], options?: IndexOptions): Promise<FqnEntry[]>;
  dispose(): void;
}
