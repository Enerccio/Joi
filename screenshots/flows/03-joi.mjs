// Step 03-models -> 04-joi: the assistant. Created with the default name, her models chosen on the About tab.
import {dialog, idle, login, main, openTab, pick, press, snap} from '../lib/shot.mjs';

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Jois');
  await page.getByRole('button', { name: /Create Joi/ }).click();
  await idle(page, 1000);
  await snap(page, 'joi-create', { clip: { x: 800, y: 300, width: 320, height: 300 } });
  await page.getByRole('button', { name: 'Yes', exact: true }).click();
  await idle(page, 2000);
  await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
  await idle(page, 2500);
  await pick(page, 'Chat model', 'Local chat');
  await pick(page, 'Deep reasoning model', 'Local reasoner');
  await pick(page, 'Auxiliary model', 'Local chat');
  await idle(page, 800);
  await snap(page, 'joi-about');
  await press(page, 'Exit');
  await idle(page, 1500);
  await snap(page, 'tab-jois-joi');
});
