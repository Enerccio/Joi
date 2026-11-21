// Only looks (any step from 07-body): the tabs of User Settings, the Administration page, the Models tabs and a text model's dialog.
import {dialog, idle, login, main, openTab, press, rowIconButton, section, snap, snapDialog} from '../lib/shot.mjs';

async function innerTab(page, name) {
  await page.locator('vaadin-tab').filter({ hasText: new RegExp('^\\s*' + name + '\\s*$') }).first().click();
  await idle(page, 800);
}

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'User Settings');
  for (const [tab, name] of [['Prompts', 'settings-prompts'], ['Personalities', 'settings-personalities'], ['Moods', 'settings-moods'],
    ['Access', 'settings-access'], ['Tools', 'settings-tools']]) {
    await section(page, name, async () => {
      await innerTab(page, tab);
      await idle(page, 500);
      await snap(page, name);
    });
  }
  await section(page, 'admin', async () => {
    await page.getByRole('button', { name: 'Administration', exact: true }).click();
    await idle(page, 1000);
    for (const [tab, name] of [['GPUs', 'admin-gpus'], ['API access', 'admin-api-access'], ['Database Backups', 'admin-backups']]) {
      await innerTab(page, tab);
      await snap(page, name);
    }
  });
  await openTab(page, 'Models');
  await section(page, 'tts-models', async () => { await innerTab(page, 'TTS Models'); await snap(page, 'tts-models'); });
  await section(page, 'voice-actors', async () => { await innerTab(page, 'Voice Actors'); await snap(page, 'voice-actors'); });
  await section(page, 'text-model-dialog', async () => {
    await innerTab(page, 'Text Models');
    await (await rowIconButton(page, 'Local chat', 'pen')).click();
    await idle(page, 1500);
    await snapDialog(page, 'text-model-dialog');
    await press(dialog(page), 'Cancel').catch(() => page.keyboard.press('Escape'));
  });
});
