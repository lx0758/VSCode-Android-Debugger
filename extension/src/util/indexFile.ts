// extension/src/util/indexFile.ts
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { FqnEntry } from '../source/indexer';

/**
 * Single, dedicated directory for our index temp files. Every write
 * lands directly in here (no nested per-process subdirs); the file
 * name itself carries enough uniqueness (`pid-timestamp-random.json`)
 * to avoid collisions between concurrent instances.
 */
export const INDEX_TMP_DIR = path.join(os.tmpdir(), 'd4a-index');

/**
 * Write the pre-built FQN→file index to a temp file under the
 * dedicated d4a-index dir. The adapter deletes the file after
 * reading it (see Main.kt#loadInitialIndex), so each write is paired
 * with a read.
 */
export function writeIndexFile(entries: FqnEntry[]): string {
  fs.mkdirSync(INDEX_TMP_DIR, { recursive: true });
  const filePath = path.join(
    INDEX_TMP_DIR,
    `index-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.json`
  );
  fs.writeFileSync(filePath, JSON.stringify(entries), 'utf-8');
  return filePath;
}

/**
 * Best-effort sweep of stale index files. Called at extension activate
 * so a crashed previous session's files don't pile up forever.
 */
export function sweepStaleIndexFiles(maxAgeMs = 24 * 60 * 60 * 1000): void {
  let entries: string[];
  try {
    entries = fs.readdirSync(INDEX_TMP_DIR);
  } catch {
    return;
  }
  const now = Date.now();
  for (const entry of entries) {
    const full = path.join(INDEX_TMP_DIR, entry);
    try {
      const stat = fs.statSync(full);
      if (stat.isFile() && now - stat.mtimeMs > maxAgeMs) {
        fs.unlinkSync(full);
      }
    } catch {
      // ignore
    }
  }
}