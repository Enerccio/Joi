// The workspace after the login: one picture of each tab. Only looks, so it runs on any step from 01-admin on.
import {idle, login, main, openTab, section, snap, CONTENT} from '../lib/shot.mjs';

await main(async ({ page }) => {
  await login(page);
  await section(page, 'workspace', async () => {
    await snap(page, 'workspace');
  });
  const tabs = [
    ['Jois', 'tab-jois'], ['Bodies', 'tab-bodies'], ['Clips', 'tab-clips'], ['Animations', 'tab-animations'], ['Models', 'tab-models'],
    ['Trash', 'tab-trash'], ['User Settings', 'tab-user-settings'],
  ];
  for (const [label, name] of tabs) {
    await section(page, name, async () => {
      await openTab(page, label);
      await idle(page, 600);
      await snap(page, name);
    });
  }
  // the Administration page is a button under the tabs
  await section(page, 'admin', async () => {
    await page.getByRole('button', { name: 'Administration', exact: true }).click();
    await idle(page, 1000);
    await snap(page, 'admin');
  });
});
