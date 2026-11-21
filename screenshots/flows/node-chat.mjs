// The JavaFX node with her body and the chat (needs the step 09-conversation, the fake model, and the node in its screenshot mode):
// the node connects (one assistant: it opens her at once), loads her body, shows the history and asks for the approval of her
// page task. Pictures: the window (node-chat; node-controls and node-looking-glass-tuning are cropped from the windows by bin/crop-node.sh), the dialog (node-approval).
import {click, combo, dialogOpen, main, serverUrl, sleep, snap, snapDialog, wait, texts} from '../lib/node.mjs';

await main(async () => {
  await wait('Connect');
  await combo(serverUrl);
  await click('Connect');
  await wait('Send', 90);                     // her body is loaded: the chat is there
  await wait('Approve', 60);                  // the page task that waits asks
  await sleep(4000);                          // the renderer shows her
  await snapDialog('node-approval');
  await click('Later');
  await sleep(1500);
  await snap('node-chat');
  // "On the Looking Glass" without a display still opens her tuning pane (the page here closes)
  await click('On the Looking Glass');
  await sleep(5000);
  await snap('_node-tuning-window');
  console.log('  on screen:', (await texts()).slice(0, 12).join(' | '));
});
