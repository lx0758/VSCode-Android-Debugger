import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as cp from 'child_process';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';

type PlatformProbes = Partial<Record<NodeJS.Platform, string[]>>;

const COMMON_ADB_PATHS: PlatformProbes = {
  linux: [
    '/opt/android-sdk/platform-tools/adb',
    '/usr/local/lib/android/sdk/platform-tools/adb',
    '/usr/lib/android-sdk/platform-tools/adb',
    path.join(os.homedir(), 'Android/Sdk/platform-tools/adb'),
  ],
  darwin: [
    path.join(os.homedir(), 'Library/Android/sdk/platform-tools/adb'),
    '/opt/homebrew/bin/adb',
    '/usr/local/bin/adb',
  ],
  win32: [
    path.join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe'),
    path.join(process.env.ProgramFiles || '', 'Android', 'platform-tools', 'adb.exe'),
  ],
};

export interface AdbPathResult {
  adbPath: string;
  /**
   * Where the adb path came from. We expose just two coarse buckets —
   * `config` (the user pointed us at a specific binary via
   * `android-debugger.adbPath`) vs `auto` (everything else: reused
   * running adb, common install paths, $PATH lookup). The internal
   * resolution ladder under "auto" is logged for debugging, but the
   * callers don't need to distinguish.
   */
  source: 'config' | 'auto';
}

/**
 * Resolve the adb binary.
 *
 * Search order:
 *   1. Configured path (`android-debugger.adbPath`). If the user pointed
 *      us at a specific binary, use it. We don't fall back if it
 *      doesn't exist — wrong config is a config bug, surface it.
 *   2. Reuse an adb that's *already running*. On Linux we walk
 *      `/proc/<pid>/cmdline` to find processes whose last argv token
 *      is `adb`, then read `/proc/<pid>/exe` (a symlink) and resolve
 *      it to the real binary. On Windows we ask PowerShell for
 *      `Get-Process adb | Select-Object -ExpandProperty Path`. This
 *      lets the user keep their SDK adb running and we transparently
 *      attach to the same binary — and crucially we report the *real*
 *      path, not a generic "adb" token.
 *   3. Common install paths for the current platform (Android SDK
 *      defaults, Homebrew, system package installs).
 *   4. Explicit PATH search — parse `$PATH`, check every directory for
 *      `adb` / `adb.exe`. We never spawn a bare `adb` token because
 *      that hides which binary we used and obscures misconfigurations.
 *
 * If none of those finds a real file we throw, so the user gets a
 * clear "adb not found, please set adbPath" error instead of an opaque
 * "spawn adb ENOENT" later.
 *
 * macOS does not implement step 2 — there's no `/proc` and no clean
 * way to read another process's executable path without spawning
 * `lsof` / parsing `ps` output. Falling back to PATH for that one
 * platform keeps the code uniform without dragging in an ugly shell
 * pipeline.
 */
export function findAdb(configuredPath: string): AdbPathResult {
  if (configuredPath && configuredPath !== 'auto') {
    if (fs.existsSync(configuredPath)) {
      Logger.info(t.logAdbConfigured(configuredPath));
      return { adbPath: configuredPath, source: 'config' };
    }
    Logger.warn(t.logAdbConfiguredMissing(configuredPath));
    throw new Error(t.errAdbConfiguredMissing(configuredPath));
  }
  return findAdbAuto();
}

function findAdbAuto(): AdbPathResult {
  // 1. Reuse running adb (skipped on platforms without a clean impl).
  const running = findRunningAdb();
  if (running) {
    Logger.info(t.logAdbRunningFound(running));
    return { adbPath: running, source: 'auto' };
  }

  // 2. Common install paths.
  const platform = process.platform as NodeJS.Platform;
  const commonPaths = COMMON_ADB_PATHS[platform] || [];
  for (const p of commonPaths) {
    if (isExecutableFile(p)) {
      Logger.info(t.logAdbAutoFound(p));
      return { adbPath: p, source: 'auto' };
    }
  }

  // 3. $PATH.
  const pathAdb = findAdbInPath();
  if (pathAdb) {
    Logger.info(t.logAdbFoundInPath(pathAdb));
    return { adbPath: pathAdb, source: 'auto' };
  }

  Logger.error(t.errAdbNotFound());
  throw new Error(t.errAdbNotFound());
}

/**
 * Walk the running-process table for `adb`. Returns the resolved
 * binary path or null.
 *
 * Linux: `/proc/<pid>/cmdline` is a NUL-separated argv list; for an
 *        `adb server` invocation the final token is literally `adb`.
 *        `/proc/<pid>/exe` is a symlink to the binary, which we
 *        resolve with `realpathSync` to follow any symlink chain.
 *
 * Windows: `Get-Process adb` reports the full image path on its
 *          `Path` property. One PowerShell round-trip is cleaner
 *          than `tasklist` (which only reports the binary name) +
 *          a second command to recover the path.
 *
 * macOS: returns null — see the comment on the platform branching.
 */
function findRunningAdb(): string | null {
  if (process.platform === 'linux') return findRunningAdbLinux();
  if (process.platform === 'win32') return findRunningAdbWindows();
  return null;
}

function findRunningAdbLinux(): string | null {
  let pids: string[];
  try {
    pids = fs.readdirSync('/proc').filter((p) => /^\d+$/.test(p));
  } catch {
    return null;
  }
  for (const pid of pids) {
    try {
      const cmdline = fs.readFileSync(path.join('/proc', pid, 'cmdline'), 'utf-8');
      const tokens = cmdline.split('\0').filter(Boolean);
      // `adb` binary is the last token: `adb <subcmd> ...` so e.g.
      // `adb\0server` ends in `server` for `adb server`, not `adb`.
      // The form we care about is `adb` invoked directly as a
      // long-running command — most commonly `adb -P 5037 ...`
      // where argv0 is `/some/path/adb` or `adb`. Match either.
      const last = tokens[tokens.length - 1];
      const first = tokens[0];
      const isAdbBinary =
        path.basename(first) === 'adb' ||
        last === 'adb' ||
        /\/adb$/.test(first);
      if (!isAdbBinary) continue;
      const exeLink = path.join('/proc', pid, 'exe');
      const realPath = fs.realpathSync(exeLink);
      if (isExecutableFile(realPath)) {
        return realPath;
      }
    } catch {
      // EACCES on /proc/<pid>/cmdline for other users' processes,
      // ENOENT if the process exited between readdir and readFile,
      // EINVAL if /proc/<pid>/exe is not a symlink (kernel thread),
      // etc. Skip and try the next pid.
    }
  }
  return null;
}

function findRunningAdbWindows(): string | null {
  // PowerShell script: list every running adb.exe and print its full
  // image path one per line. -ErrorAction SilentlyContinue makes a
  // missing match produce no output (instead of a noisy red error).
  const psCommand =
    'Get-Process adb -ErrorAction SilentlyContinue | ' +
    'Select-Object -ExpandProperty Path';
  let raw: string;
  try {
    raw = cp
      .execFileSync(
        'powershell',
        ['-NoProfile', '-NonInteractive', '-Command', psCommand],
        { stdio: ['pipe', 'pipe', 'pipe'], timeout: 5000, windowsHide: true }
      )
      .toString('utf-8');
  } catch {
    return null;
  }
  const candidates = raw
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
  for (const c of candidates) {
    if (isExecutableFile(c)) {
      return c;
    }
  }
  return null;
}

/**
 * Walk `$PATH` looking for `adb` (or `adb.exe`). Returns the full path
 * of the first match, or null if not found. We do this ourselves
 * instead of just shelling out to `which` / `where` because (a) it's
 * platform-portable, (b) we know the answer is a real file (not just
 * something `which` claims exists), and (c) it works even when PATH
 * points to a directory we don't have execute permission on.
 */
function findAdbInPath(): string | null {
  const pathSep = process.platform === 'win32' ? ';' : ':';
  const exeName = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const dirs = (process.env.PATH || '').split(pathSep).filter(Boolean);
  for (const dir of dirs) {
    const candidate = path.join(dir, exeName);
    if (isExecutableFile(candidate)) {
      return candidate;
    }
  }
  return null;
}

function isExecutableFile(p: string): boolean {
  try {
    return fs.statSync(p).isFile();
  } catch {
    return false;
  }
}