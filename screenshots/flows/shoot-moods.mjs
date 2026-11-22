// Only looks (any step from 07-body): the Moods tab, the dialog of a built-in mood with its face, and her dialog's About tab with Today's mood.
import {dialog, idle, login, main, openTab, press, rowIconButton, section, snap, snapDialog} from '../lib/shot.mjs';

async function innerTab(page, name) {
  await page.locator('vaadin-tab').filter({ hasText: new RegExp('^\\s*' + name + '\\s*$') }).first().click();
  await idle(page, 800);
}

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'User Settings');
  await section(page, 'settings-moods', async () => {
    await innerTab(page, 'Moods');
    await idle(page, 500);
    await snap(page, 'settings-moods');
  });
  await section(page, 'mood-dialog', async () => {
    await innerTab(page, 'Moods');
    await (await rowIconButton(page, 'Happy', 'pen')).click();
    await idle(page, 1500);
    await snapDialog(page, 'mood-dialog');
    await press(dialog(page), 'Cancel').catch(() => page.keyboard.press('Escape'));
  });
  await section(page, 'joi-about', async () => {
    await openTab(page, 'Jois');
    await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
    await idle(page, 2500);
    await page.locator('vaadin-select').filter({ has: page.locator('label', { hasText: 'Mood today' }) }).click();
    await idle(page, 400);
    await page.locator('vaadin-select-item').filter({ hasText: /^\s*Happy\s*$/ }).first().click();
    await idle(page, 800);
    await snap(page, 'joi-about');
    await press(page, 'Exit');
  });
});
