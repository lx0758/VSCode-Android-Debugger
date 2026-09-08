// extension/src/adb/AdbDevice.ts
//
// One connected Android device. Every device-scoped adb command issued
// here goes out with `-s <serial>` so it hits the right handset when
// several are connected. Forward bookkeeping (activeForwards) is
// per-instance: the extension creates one AdbDevice per debug session,
// mirroring the session-scoped forward lifecycle.
import { ChildProcess } from 'child_process';
import { AdbRunner, AdbError } from './AdbRunner';
import { Logger } from '../util/logger';
import { allocatePortWithRetry } from '../util/port';
import { t } from '../i18n/strings';

export interface JdwpProcess {
  pid: number;
  name: string;
}

export class AdbDevice {
  private activeForwards: Set<number> = new Set();

  constructor(
    private readonly runner: AdbRunner,
    readonly serial: string,
    readonly state: string = 'unknown'
  ) {}

  /**
   * Enumerate debuggable (JDWP) processes on this device:
   *   1. "adb -s <serial> jdwp" prints every currently-debuggable PID on
   *      connect, then blocks waiting for new attach/detach events. Capture
   *      the initial burst with a short timeout, then kill the process.
   *   2. Look up process names via "ps -A -o PID,NAME".
   */
  async listJdwpProcesses(): Promise<JdwpProcess[]> {
    const pids = await this.captureJdwpPids(1000);
    if (pids.length === 0) {
      Logger.info(`[AdbDevice] "adb jdwp" captured 0 process(es) on ${this.serial}`);
      return [];
    }
    Logger.info(
      `[AdbDevice] "adb jdwp" captured ${pids.length} PID(s) on ${this.serial}: ${pids.join(', ')}`
    );

    const nameMap = await this.lookupProcessNames(pids);
    return pids.map((pid) => ({ pid, name: nameMap.get(pid) ?? `pid ${pid}` }));
  }

  async forwardJdwp(pid: number): Promise<number> {
    const port = await allocatePortWithRetry();
    const result = await this.runner.exec(['forward', `tcp:${port}`, `jdwp:${pid}`], this.serial);
    if (result.exitCode !== 0) {
      throw new AdbError('forward', result, t.logForwardFailed(port));
    }
    this.activeForwards.add(port);
    Logger.info(t.logForwardEstablished(port, pid));
    return port;
  }

  async removeForward(port: number): Promise<void> {
    if (!this.activeForwards.has(port)) {
      return;
    }
    const result = await this.runner.exec(['forward', '--remove', `tcp:${port}`], this.serial);
    if (result.exitCode !== 0) {
      Logger.warn(t.logForwardRemoveFailed(port, result.stderr));
    } else {
      Logger.info(t.logForwardRemoved(port));
    }
    this.activeForwards.delete(port);
  }

  async cleanupAllForwards(): Promise<void> {
    const ports = Array.from(this.activeForwards);
    for (const port of ports) {
      await this.removeForward(port);
    }
  }

  getActiveForwardCount(): number {
    return this.activeForwards.size;
  }

  private async captureJdwpPids(captureMs: number): Promise<number[]> {
    return new Promise((resolve, reject) => {
      Logger.info(`[AdbDevice] spawning "adb jdwp" on ${this.serial} (kill after ${captureMs}ms capture)`);
      const proc: ChildProcess = this.runner.start(['jdwp'], this.serial);
      let stdout = '';
      let settled = false;
      const timer = setTimeout(() => {
        Logger.info(`[AdbDevice] killing "adb jdwp" after ${captureMs}ms capture window`);
        killQuietly(proc);
      }, captureMs);
      const cleanup = (): void => {
        clearTimeout(timer);
        killQuietly(proc);
      };
      proc.stdout?.on('data', (d) => {
        stdout += d.toString();
      });
      proc.stderr?.on('data', (d) => {
        Logger.warn(`[AdbDevice] "adb jdwp" stderr: ${d.toString().trimEnd()}`);
      });
      proc.on('error', (err) => {
        if (settled) return;
        settled = true;
        Logger.error(`[AdbDevice] "adb jdwp" spawn error: ${err.message}`);
        cleanup();
        reject(err);
      });
      proc.on('exit', () => {
        if (settled) return;
        settled = true;
        cleanup();
        const pids = stdout
          .split('\n')
          .map((l) => l.trim())
          .filter((l) => /^\d+$/.test(l))
          .map((p) => parseInt(p, 10));
        resolve(pids);
      });
    });
  }

  private async lookupProcessNames(pids: number[]): Promise<Map<number, string>> {
    const map = new Map<number, string>();
    const result = await this.runner.exec(['shell', 'ps -A -o PID,NAME'], this.serial);
    if (result.exitCode !== 0) {
      Logger.warn(
        `[AdbDevice] "ps -A -o PID,NAME" failed on ${this.serial} (exit=${result.exitCode}), names will be fallback`
      );
      return map;
    }
    const want = new Set(pids);
    for (const line of result.stdout.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      const m = trimmed.match(/^(\d+)\s+(.+)$/);
      if (!m) continue;
      const pid = parseInt(m[1], 10);
      if (want.has(pid)) {
        map.set(pid, m[2].trim());
      }
    }
    return map;
  }
}

function killQuietly(proc: ChildProcess): void {
  try {
    proc.kill('SIGKILL');
  } catch {
    // already dead
  }
}
