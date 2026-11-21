// Step 04-joi -> 05-user-models: the user's default models (User Settings → General). The model dialog's appearance, the
// summaries and the jobs that no assistant owns use these; the assistant's own choice (About tab) wins over them.
import {idle, login, main, openTab, pick, press, snap} from '../lib/shot.mjs';

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'User Settings');
  await idle(page, 1000);
  await pick(page, 'Chat model', 'Local chat');
  await pick(page, 'Deep reasoning model', 'Local reasoner');
  await pick(page, 'Auxiliary model', 'Local chat');
  await idle(page, 600);
  await snap(page, 'settings-general');
  await press(page, 'Save');
  await idle(page, 1000);
});
