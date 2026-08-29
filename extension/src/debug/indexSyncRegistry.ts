// extension/src/debug/indexSyncRegistry.ts
import { DebugIndexSync } from './indexSync';

let current: DebugIndexSync | undefined;

export function setActiveSync(sync: DebugIndexSync | undefined): void {
  if (current && current !== sync) {
    current.dispose();
  }
  current = sync;
}

export function getActiveSync(): DebugIndexSync | undefined {
  if (current?.isDisposed()) {
    current = undefined;
  }
  return current;
}

export function clearActiveSync(): void {
  if (current) {
    current.dispose();
    current = undefined;
  }
}
