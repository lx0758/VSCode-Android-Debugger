// extension/src/util/port.ts
import * as net from 'net';
import { t } from '../i18n/strings';

export function allocateEphemeralPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.once('listening', () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error(t.errAllocateEphemeralPortFailed()));
        return;
      }
      const port = addr.port;
      server.close(() => resolve(port));
    });
    server.listen(0, '127.0.0.1');
  });
}

export async function allocatePortWithRetry(maxAttempts: number = 3): Promise<number> {
  let lastErr: Error | undefined;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await allocateEphemeralPort();
    } catch (e) {
      lastErr = e as Error;
    }
  }
  throw lastErr ?? new Error(t.errAllocateJdwpPortFailed(maxAttempts));
}
