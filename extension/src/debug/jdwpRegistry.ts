// extension/src/debug/jdwpRegistry.ts
import { AdbJdwpController } from '../adb/jdwp';

/**
 * Module-level registry holding the shared `AdbJdwpController` for
 * the lifetime of the extension. We need a single instance because:
 *
 *  - `AdbJdwpController.activeForwards` is the source of truth for
 *    which forwards we currently have open on the device. If every
 *    `attach` call constructed its own controller and then threw it
 *    away when the function returned, the in-memory list would be
 *    empty even though adb still has forwards bound.
 *  - The `onDidTerminateDebugSession` hook in `extension.local.ts`
 *    needs to be able to look up the controller and call
 *    `removeForward` for the session's port — without sharing the
 *    reference across modules that isn't possible.
 *
 * Construction is lazy: the controller depends on `findAdb(config)`
 * which we don't want to run at activation (a misconfigured adb
 * shouldn't block the extension from activating). `attach.ts`
 * constructs it on the first attach call and registers it here.
 */

let controller: AdbJdwpController | undefined;

export function setJdwpController(c: AdbJdwpController): void {
  controller = c;
}

export function getJdwpController(): AdbJdwpController | undefined {
  return controller;
}

export function clearJdwpController(): void {
  controller = undefined;
}