// extension/src/debug/portAllocator.ts
import * as net from 'net';
import { Logger } from '../util/logger';
import { t } from '../i18n/strings';

export async function allocateFreePort(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error(t.errBindPortFailed()));
        return;
      }
      const port = addr.port;
      server.close(() => {
        Logger.info(`[port] allocated ${port}`);
        resolve(port);
      });
    });
  });
}
