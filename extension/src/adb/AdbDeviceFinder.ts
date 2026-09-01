// extension/src/adb/AdbDeviceFinder.ts
//
// Device discovery. Runs `adb devices` and wraps every entry in an
// AdbDevice so callers can immediately issue device-scoped commands
// without touching the raw runner themselves.
import { AdbRunner } from './AdbRunner';
import { AdbDevice } from './AdbDevice';
import { Logger } from '../util/logger';

export class AdbDeviceFinder {
  constructor(private readonly runner: AdbRunner) {}

  /**
   * List all devices adb currently knows about. Returns [] when adb itself
   * fails — the caller decides how to surface that (no devices vs broken
   * adb are distinguished by the empty list + caller-side messaging).
   * Note: `adb devices` is inherently global and cannot take `-s`.
   */
  async findAll(): Promise<AdbDevice[]> {
    const result = await this.runner.exec(['devices']);
    if (result.exitCode !== 0) {
      Logger.warn(`[AdbDeviceFinder] "adb devices" failed (exit=${result.exitCode})`);
      return [];
    }
    const devices = parseDevices(result.stdout).map(
      (d) => new AdbDevice(this.runner, d.serial, d.state)
    );
    Logger.info(`[AdbDeviceFinder] found ${devices.length} device(s)`);
    return devices;
  }
}

function parseDevices(output: string): { serial: string; state: string }[] {
  const lines = output.split('\n').filter((l) => l.trim() && !l.startsWith('List of devices'));
  return lines.map((line) => {
    const parts = line.trim().split(/\s+/);
    return {
      serial: parts[0],
      state: parts[1] || 'unknown',
    };
  });
}
