// The JavaFX node, first start: the screen where the server is chosen, the same filled in, and the Joi menu. Needs the node in
// its screenshot mode (bin/start-node.sh) and the server (bin/start-app.sh). Only looks (it connects, which saves the server in the
// node's own settings, never in ~/.joi-client or ~/.joi-dev-client).
import {click, combo, main, openMenu, closeMenus, serverUrl, snap, wait, texts} from '../lib/node.mjs';

await main(async () => {
  await wait('Connect');
  await snap('node-server');
  await combo(serverUrl);
  await openMenu('Joi');
  await snap('node-joi-menu');
  await closeMenus();
  await click('Connect');
  await new Promise(r => setTimeout(r, 2500));
  console.log('  on screen:', (await texts()).join(' | '));
  await snap('node-after-connect');
});
