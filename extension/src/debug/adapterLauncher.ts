// extension/src/debug/adapterLauncher.ts
import { spawn, ChildProcess } from 'child_process';
import * as path from 'path';
import * as fs from 'fs';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';

export interface AdapterLaunchOptions {
  javaPath: string;
  jarPath: string;
  args?: string[];
  cwd?: string;
}

export class AdapterLauncher {
  launch(options: AdapterLaunchOptions): ChildProcess {
    // Force UTF-8 on every stream: on Windows CN/JP/KR systems the JVM
    // defaults to GBK/EUC-KR/etc and crashes Chinese log output. Set all
    // three properties so stdin/stdout/stderr are UTF-8 from boot.
    const jvmArgs = [
      '-Dfile.encoding=UTF-8',
      '-Dsun.stdout.encoding=UTF-8',
      '-Dsun.stderr.encoding=UTF-8',
      '-jar', options.jarPath,
      ...(options.args || []),
    ];
    Logger.info(t.logAdapterSpawn(options.javaPath, jvmArgs.join(' ')));
    const proc = spawn(options.javaPath, jvmArgs, {
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: options.cwd,
    });
    proc.on('exit', (code, signal) => {
      Logger.info(t.logAdapterExited(code, signal));
    });
    proc.on('error', (err) => {
      Logger.error(t.logAdapterSpawnFail(), err);
    });
    proc.stdout?.on('data', (chunk: Buffer) => {
      for (const line of chunk.toString('utf-8').split(/\r?\n/)) {
        if (line) Logger.info(`[adapter stdout] ${line}`);
      }
    });
    proc.stderr?.on('data', (chunk: Buffer) => {
      for (const line of chunk.toString('utf-8').split(/\r?\n/)) {
        if (line) Logger.info(`[adapter stderr] ${line}`);
      }
    });
    return proc;
  }

  resolveAdapterJar(extensionPath: string): string {
    const candidates = [
      path.join(extensionPath, 'resources', 'adapter.jar'),
      path.join(extensionPath, '..', 'adapter', 'build', 'libs', 'adapter.jar'),
    ];
    for (const p of candidates) {
      if (fs.existsSync(p)) {
        Logger.info(t.logAdapterJarFound(p));
        return p;
      }
    }
    throw new Error(t.errAdapterJarMissing(candidates.join('\n')));
  }
}
