import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { config } from './config.js';
import { createApp } from './http.js';
import { attachSockets } from './socket.js';
import { recoverInterruptedGames } from './gameStore.js';

const refunded = recoverInterruptedGames();
if (refunded) console.log(`Refunded ${refunded} game(s) interrupted by the last shutdown`);

const httpServer = createServer(createApp());
attachSockets(httpServer);

httpServer.listen(config.port, '0.0.0.0', () => {
  console.log(`Hide & Seek server on port ${config.port} (${config.devMode ? 'DEV mode' : 'production'})`);
  // Phones on the same Wi-Fi reach the server through one of these addresses.
  for (const list of Object.values(networkInterfaces())) {
    for (const net of list || []) {
      if (net.family === 'IPv4' && !net.internal) console.log(`  -> http://${net.address}:${config.port}`);
    }
  }
});
