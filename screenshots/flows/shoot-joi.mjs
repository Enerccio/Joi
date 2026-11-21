// Only looks, except one memory typed by hand (it is not saved: the flow cancels it): her dialog's Templates tab (Memories and Animations are taken by the later steps, when they hold something).
import {dialog, fill, idle, login, main, openTab, press, section, snap, snapDialog} from '../lib/shot.mjs';

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Jois');
  await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
  await idle(page, 2500);
  const inner = async name => {
    await page.locator('vaadin-tab').filter({ hasText: new RegExp('^\\s*' + name + '\\s*$') }).last().click();
    await idle(page, 1200);
  };
  await section(page, 'joi-templates', async () => { await inner('Templates'); await snap(page, 'joi-templates'); });
  await press(page, 'Exit');
});
