// extension/src/adb/AdbRunner.ts
//
// adb execution facade. A runner owns the whole "find the binary → spawn
// commands" chain: resolution runs lazily on first use, so constructing a
// runner is always safe (even with adb missing) while the first exec/start
// surfaces a precise "adb not found" error instead of an opaque ENOENT.
import { spawn, ChildProcess, execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';

export interface AdbRunner {
  exec(args: string[], serial?: string): Promise<AdbResult>;
  execWithStdin(args: string[], stdin: string, serial?: string): Promise<AdbResult>;
  start(args: string[], serial?: string): ChildProcess;
}

export interface AdbResult {
  stdout: string;
  stderr: string;
  exitCode: number;
  timedOut: boolean;
}

export class AdbError extends Error {
  constructor(public readonly command: string, public readonly result: AdbResult, message?: string) {
    super(message || `adb ${command} failed (exit ${result.exitCode}): ${result.stderr.trim()}`);
  }
}

export class DefaultAdbRunner implements AdbRunner {
  private resolvedPath: string | undefined;

  /**
   * @param configuredPath Value of `android-debugger.adbPath` — either an
   *   explicit binary path, or 'auto' (default) to probe a running adb
   *   server first, then $PATH.
   */
  constructor(private readonly configuredPath: string = 'auto') {}

  /** Resolve the binary once, on first use. */
  private get adbPath(): string {
    this.resolvedPath ??= findAdb(this.configuredPath);
    return this.resolvedPath;
  }

  async exec(args: string[], serial?: string): Promise<AdbResult> {
    return this.run(args, undefined, serial);
  }

  async execWithStdin(args: string[], stdin: string, serial?: string): Promise<AdbResult> {
    return this.run(args, stdin, serial);
  }

  start(args: string[], serial?: string): ChildProcess {
    const finalArgs = serial ? ['-s', serial, ...args] : args;
    Logger.debug(t.logSpawningAdb(this.adbPath, finalArgs.join(' ')));
    return spawn(this.adbPath, finalArgs, { stdio: ['pipe', 'pipe', 'pipe'] });
  }

  private run(args: string[], stdin?: string, serial?: string): Promise<AdbResult> {
    return new Promise((resolve, reject) => {
      const finalArgs = serial ? ['-s', serial, ...args] : args;
      Logger.info(t.logRunningAdb(this.adbPath, finalArgs.join(' ')));
      const proc = spawn(this.adbPath, finalArgs, { stdio: ['pipe', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        Logger.warn(`[adb] timeout (${ADB_TIMEOUT_MS}ms), killing process`);
        proc.kill('SIGKILL');
      }, ADB_TIMEOUT_MS);
      proc.stdout.on('data', (d) => (stdout += d.toString()));
      proc.stderr.on('data', (d) => {
        const s = d.toString();
        stderr += s;
        Logger.debug(`[adb] stderr: ${s.trimEnd()}`);
      });
      if (stdin) {
        proc.stdin.write(stdin);
        proc.stdin.end();
      }
      proc.on('error', (err) => {
        clearTimeout(timer);
        Logger.error(`[adb] spawn error: ${err.message}`);
        reject(err);
      });
      proc.on('exit', (exitCode, signal) => {
        clearTimeout(timer);
        const result: AdbResult = {
          stdout,
          stderr,
          exitCode: exitCode ?? -1,
          timedOut,
        };
        Logger.info(
          `[adb] exit: code=${result.exitCode} signal=${signal} stdout=${JSON.stringify(truncateForLog(stdout.trim(), STDOUT_LOG_MAX))} stderr=${JSON.stringify(truncateForLog(stderr.trim(), STDERR_LOG_MAX))}`
        );
        resolve(result);
      });
    });
  }
}

const ADB_TIMEOUT_MS = 30_000;
const STDOUT_LOG_MAX = 200;
const STDERR_LOG_MAX = 500;

/**
 * Collapse a string for logging so commands like `adb shell ps -A -o
 * PID,NAME` (which return hundreds of process rows) don't drown the
 * Output channel. We keep the first 200 chars and record the original
 * length so the user can still tell how much was truncated.
 */
function truncateForLog(text: string, max = STDOUT_LOG_MAX): string {
  if (text.length <= max) return text;
  return `${text.slice(0, max)}... [+${text.length - max} chars]`;
}

/**
 * Resolve the adb binary to a concrete path. Search order:
 *   1. Configured path. If the user pointed us at a specific binary, use
 *      it. We don't fall back if it doesn't exist — wrong config is a
 *      config bug, surface it.
 *   2. Reuse an adb that's *already running*. On Linux we walk
 *      `/proc/<pid>/cmdline` to find processes whose argv mentions `adb`,
 *      then read `/proc/<pid>/exe` (a symlink) and resolve it to the real
 *      binary. On Windows we ask PowerShell for
 *      `Get-Process adb | Select-Object -ExpandProperty Path`. This lets
 *      the user keep their SDK adb running and we transparently attach to
 *      the same binary — and crucially we report the *real* path, not a
 *      generic "adb" token.
 *   3. Explicit PATH search — parse `$PATH`, check every directory for
 *      `adb` / `adb.exe`. We never spawn a bare `adb` token because that
 *      hides which binary we used and obscures misconfigurations.
 *
 * If none of those finds a real file we throw, so the user gets a clear
 * "adb not found, please set adbPath" error instead of an opaque
 * "spawn adb ENOENT" later.
 *
 * macOS does not implement step 2 — there's no `/proc` and no clean way
 * to read another process's executable path without spawning `lsof` /
 * parsing `ps` output. Falling back to PATH for that one platform keeps
 * the code uniform without dragging in an ugly shell pipeline.
 */
function findAdb(configuredPath: string): string {
  if (configuredPath && configuredPath !== 'auto') {
    if (fs.existsSync(configuredPath)) {
      Logger.info(t.logAdbConfigured(configuredPath));
      return configuredPath;
    }
    Logger.warn(t.logAdbConfiguredMissing(configuredPath));
    throw new Error(t.errAdbConfiguredMissing(configuredPath));
  }
  return findAdbAuto();
}

function findAdbAuto(): string {
  // 1. Reuse running adb (skipped on platforms without a clean impl).
  const running = findRunningAdb();
  if (running) {
    Logger.info(t.logAdbRunningFound(running));
    return running;
  }

  // 2. $PATH.
  const pathAdb = findAdbInPath();
  if (pathAdb) {
    Logger.info(t.logAdbFoundInPath(pathAdb));
    return pathAdb;
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
    raw = execFileSync(
      'powershell',
      ['-NoProfile', '-NonInteractive', '-Command', psCommand],
      { stdio: ['pipe', 'pipe', 'pipe'], timeout: 5000, windowsHide: true }
    ).toString('utf-8');
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
