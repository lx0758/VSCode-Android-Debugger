// extension/src/adb/jdwp.ts
import { AdbRunner, AdbError } from './runner';
import { Logger } from '../util/logger';
import { allocatePortWithRetry } from '../util/port';
import { t } from '../i18n/strings';

export interface AdbDevice {
  serial: string;
  state: string;
}

export interface JdwpProcess {
  pid: number;
  name: string;
}

export class AdbJdwpController {
  private activeForwards: Set<number> = new Set();

  constructor(private readonly runner: AdbRunner) {}

  async listDevices(): Promise<AdbDevice[]> {
    const result = await this.runner.exec(['devices']);
    if (result.exitCode !== 0) {
      throw new AdbError('devices', result);
    }
    return parseDevices(result.stdout);
  }

  async listJdwpProcesses(): Promise<JdwpProcess[]> {
    // Step 1: "adb jdwp" prints every currently-debuggable PID on connect,
    // then blocks waiting for new attach/detach events. Capture the initial
    // burst with a short timeout, then kill the process.
    const pids = await this.captureJdwpPids(1000);
    if (pids.length === 0) {
      Logger.info(`[UI] "adb jdwp" captured 0 process(es)`);
      return [];
    }
    Logger.info(`[UI] "adb jdwp" captured ${pids.length} PID(s): ${pids.join(', ')}`);

    // Step 2: look up process names via "ps -A -o PID,NAME".
    const nameMap = await this.lookupProcessNames(pids);
    return pids.map((pid) => ({ pid, name: nameMap.get(pid) ?? `pid ${pid}` }));
  }

  private async captureJdwpPids(captureMs: number): Promise<number[]> {
    return new Promise((resolve, reject) => {
      Logger.info(`[UI] spawning "adb jdwp" (kill after ${captureMs}ms capture)`);
      const proc = this.runner.start(['jdwp']);
      let stdout = '';
      proc.stdout!.on('data', (d) => {
        stdout += d.toString();
      });
      proc.stderr!.on('data', (d) => {
        Logger.warn(`[UI] "adb jdwp" stderr: ${d.toString().trimEnd()}`);
      });
      proc.on('error', (err) => {
        Logger.error(`[UI] "adb jdwp" spawn error: ${err.message}`);
        reject(err);
      });
      const timer = setTimeout(() => {
        Logger.info(`[UI] killing "adb jdwp" after ${captureMs}ms capture window`);
        try {
          proc.kill('SIGKILL');
        } catch {
          // already dead
        }
      }, captureMs);
      proc.on('exit', () => {
        clearTimeout(timer);
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
    const result = await this.runner.exec(['shell', 'ps -A -o PID,NAME']);
    if (result.exitCode !== 0) {
      Logger.warn(
        `[UI] "ps -A -o PID,NAME" failed (exit=${result.exitCode}), names will be fallback`
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

  async forwardJdwp(pid: number): Promise<number> {
    const port = await allocatePortWithRetry();
    const result = await this.runner.exec(['forward', `tcp:${port}`, `jdwp:${pid}`]);
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
    const result = await this.runner.exec(['forward', '--remove', `tcp:${port}`]);
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
}

function parseDevices(output: string): AdbDevice[] {
  const lines = output.split('\n').filter((l) => l.trim() && !l.startsWith('List of devices'));
  return lines.map((line) => {
    const parts = line.trim().split(/\s+/);
    return {
      serial: parts[0],
      state: parts[1] || 'unknown',
    };
  });
}
