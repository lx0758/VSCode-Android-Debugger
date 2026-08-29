// extension/src/adb/runner.ts
import { spawn, ChildProcess } from 'child_process';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';

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

export interface AdbRunner {
  exec(args: string[]): Promise<AdbResult>;
  execWithStdin(args: string[], stdin: string): Promise<AdbResult>;
  start(args: string[]): ChildProcess;
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
  constructor(private readonly adbPath: string) {}

  async exec(args: string[]): Promise<AdbResult> {
    return this.run(args);
  }

  async execWithStdin(args: string[], stdin: string): Promise<AdbResult> {
    return this.run(args, stdin);
  }

  start(args: string[]): ChildProcess {
    Logger.debug(t.logSpawningAdb(this.adbPath, args.join(' ')));
    return spawn(this.adbPath, args, { stdio: ['pipe', 'pipe', 'pipe'] });
  }

  private run(args: string[], stdin?: string): Promise<AdbResult> {
    return new Promise((resolve, reject) => {
      Logger.info(`[adb] running: ${this.adbPath} ${args.join(' ')}`);
      const proc = spawn(this.adbPath, args, { stdio: ['pipe', 'pipe', 'pipe'] });
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
