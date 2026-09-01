// extension/src/util/jdk.ts
import { spawn } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { Logger } from './logger';
import { t } from '../i18n/strings';

export interface JdkInfo {
  javaPath: string;
  version: number;
}

/**
 * Find a JDK 8+ on this machine and return its java executable.
 *
 * Search order, mirroring the adb resolution strategy but simpler:
 *   1. User-configured path (`android-debugger.javaPath`). If set and
 *      the file exists, use it directly — wrong config is a config
 *      bug, surface it.
 *   2. `$JAVA_HOME/bin/java`. The conventional JDK install convention;
 *      respected first because it's how 99% of build tools find Java.
 *   3. `$PATH` lookup — parse the env var, check every directory.
 *
 * After collecting candidates we run `java -version` against each and
 * return the first one that reports version >= 8. This guards against
 * a stale binary pointing at an EOL JDK.
 *
 * We deliberately do NOT enumerate "common install locations" (e.g.
 * `/Library/Java/JavaVirtualMachines`, `C:\Program Files\Eclipse
 * Adoptium\jdk-*`). Those paths change with every JDK update,
 * branch across too many distributions to enumerate usefully, and
 * almost always come with a JAVA_HOME set by the installer anyway.
 * Sticking to JAVA_HOME + PATH keeps detection deterministic and
 * easy to reason about.
 *
 * If nothing matches, return null. The caller surfaces the error to
 * the user; we don't throw here so `detectJdk` can stay optional
 * during activation (the activation path warns-but-continues).
 */
export async function detectJdk(configuredPath: string): Promise<JdkInfo | null> {
  const candidates = resolveJavaCandidates(configuredPath);
  Logger.info(`[jdk] candidates: ${candidates.length === 0 ? '(none)' : candidates.join(' | ')}`);
  for (const javaPath of candidates) {
    try {
      const info = await runJavaVersion(javaPath);
      if (info && info.version >= 8) {
        Logger.info(`JDK detected: ${javaPath} (version ${info.version})`);
        return info;
      }
      if (info) {
        Logger.warn(
          `[jdk] ${javaPath} reports version ${info.version} which is < 8 (need 8+). Skipping.`
        );
      }
    } catch (e) {
      Logger.warn(`[jdk] candidate ${javaPath} failed to spawn: ${(e as Error).message}`);
    }
  }
  Logger.warn(
    `[jdk] no usable JDK found. Checked: ${candidates.length === 0 ? '(no candidates)' : candidates.join(', ')}. ` +
    `Set 'debugger-for-android.javaPath' to an absolute path of a JDK 8+ install if autodetect misses it.`
  );
  return null;
}

function resolveJavaCandidates(configuredPath: string): string[] {
  const candidates: string[] = [];
  if (configuredPath && configuredPath !== 'auto') {
    if (!fs.existsSync(configuredPath)) {
      Logger.error(t.errJavaConfiguredMissing(configuredPath));
      throw new Error(t.errJavaConfiguredMissing(configuredPath));
    }
    Logger.info(t.logJavaConfigured(configuredPath));
    candidates.push(configuredPath);
    return candidates;
  }
  // $JAVA_HOME
  if (process.env.JAVA_HOME) {
    const candidate = path.join(process.env.JAVA_HOME, 'bin', javaExeName());
    if (fs.existsSync(candidate)) {
      Logger.info(t.logJavaFromJavaHome(candidate));
      candidates.push(candidate);
    }
  }
  // $PATH
  const inPath = findJavaInPath();
  if (inPath) {
    Logger.info(t.logJavaFoundInPath(inPath));
    candidates.push(inPath);
  }
  return candidates;
}

function findJavaInPath(): string | null {
  const pathSep = process.platform === 'win32' ? ';' : ':';
  const dirs = (process.env.PATH || '').split(pathSep).filter(Boolean);
  for (const dir of dirs) {
    const candidate = path.join(dir, javaExeName());
    try {
      if (fs.statSync(candidate).isFile()) {
        return candidate;
      }
    } catch {
      // ignore
    }
  }
  return null;
}

function javaExeName(): string {
  return process.platform === 'win32' ? 'java.exe' : 'java';
}

function runJavaVersion(javaPath: string): Promise<JdkInfo | null> {
  return new Promise((resolve, reject) => {
    const proc = spawn(javaPath, ['-version']);
    let stderr = '';
    proc.stderr.on('data', (d) => (stderr += d.toString()));
    proc.on('error', reject);
    proc.on('exit', (code) => {
      if (code !== 0) {
        Logger.debug(`[jdk] ${javaPath} -version exited with code=${code}`);
        resolve(null);
        return;
      }
      Logger.debug(`[jdk] ${javaPath} -version stderr: ${stderr.trim()}`);
      // Java 8 reports its version as `openjdk version "1.8.0_452"`, while
      // Java 9+ reports `openjdk version "11.0.20"` / `"21.0.5"` etc. The
      // major version BEFORE the first `.` is "1" for Java 8 — but Java 8's
      // actual major version is 8. Normalise by optionally consuming the
      // leading "1." before capturing the real major. This regex:
      //   "1.8.0_452"  → captures "8"  → version=8   ✓
      //   "11.0.20"    → captures "11" → version=11  ✓
      //   "21.0.5"     → captures "21" → version=21  ✓
      const m = stderr.match(/version\s+"(?:1\.)?(\d+)(?:\.\d+)*"/);
      if (m) {
        const version = parseInt(m[1], 10);
        resolve({ javaPath, version });
      } else {
        Logger.debug(`[jdk] ${javaPath} output did not contain a recognisable version line`);
        resolve(null);
      }
    });
  });
}

export function getJdkErrorMessage(): string {
  return t.jdkGetJdkErrorMessage();
}

/**
 * Activation-time pre-flight: detect a JDK and warn (but not fail) when
 * missing. Returns the detected JDK, or null — callers hand the null
 * through to SessionManager, which re-detects lazily at attach time.
 */
export async function ensureJdkOrWarn(configuredPath: string): Promise<JdkInfo | null> {
  const jdk = await detectJdk(configuredPath);
  if (jdk) {
    Logger.info(t.jdkDetected(jdk.javaPath, jdk.version));
    return jdk;
  }
  Logger.warn(t.jdkMissingLong());
  vscode.window.showWarningMessage(t.jdkWarning(), t.installJdk(), t.viewOutput());
  return null;
}