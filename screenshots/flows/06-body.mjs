// Step 06-appearance -> 07-body: her body made of Saiki, with three outfits (presets of the toggles). The description of each is made
// by the photoshoot: the viewer takes the renders, the fake model answers from dataset/saiki.mjs by the name of the outfit.
import {dialog, fill, idle, login, main, openTab, press, section, sleep, snap} from '../lib/shot.mjs';

// outfit → the parts that are hidden in it (her names for the toggles)
const outfits = [
  ['School uniform', []],
  ['Light', ['scarf', 'bag strap', 'shoulder bag']],
  ['Cold day', ['bag strap', 'shoulder bag']],
];

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Jois');
  await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
  await idle(page, 2000);
  await page.getByText('Bodies', { exact: true }).last().click();
  await idle(page, 1000);
  await page.getByRole('button', { name: 'Add body', exact: true }).click();
  await idle(page, 2000);
  await fill(page, 'Name', 'School');
  await page.locator('vaadin-select').filter({ has: page.locator('label', { hasText: /^Model$/ }) }).first().click();
  await sleep(500);
  await page.locator('vaadin-select-item').filter({ hasText: 'Saiki' }).first().click();
  await sleep(5000);
  await idle(page, 1500);

  for (const [name, hidden] of outfits) {
    await page.getByRole('button', { name: 'Add outfit', exact: true }).scrollIntoViewIfNeeded();
    await page.getByRole('button', { name: 'Add outfit', exact: true }).click();
    await idle(page, 3000);
    const d = dialog(page);                               // the outfit dialog is above the body's
    await fill(d, 'Name', name);
    for (const part of hidden) {
      const box = d.getByRole('checkbox', { name: part, exact: true });
      await box.uncheck();
      await sleep(400);
      if (await box.isChecked()) throw new Error('the part ' + part + ' is still shown');
    }
    await sleep(1500);                                    // the preview shows the outfit
    await press(d, 'Photoshoot');
    // the renders are taken in the viewer, the answer is the fake model's: the description fills the field
    await page.waitForFunction(() => {
      const open = [...document.querySelectorAll('vaadin-dialog[opened]')].pop();
      return [...open.querySelectorAll('vaadin-text-area')].some(a => a.value && a.value.startsWith('You '));
    }, null, { timeout: 120000 });
    await idle(page, 1000);
    await section(page, 'outfit-' + name.toLowerCase().replace(/\W+/g, '-'), async () => {
      await snap(page, 'outfit-' + name.toLowerCase().replace(/\W+/g, '-'));
    });
    await press(d, 'Save');
    await idle(page, 1500);
  }
  await section(page, 'body-dialog', async () => {
    await page.getByText('Outfits', { exact: true }).first().scrollIntoViewIfNeeded();
    await snap(page, 'body-dialog');
  });
  await press(dialog(page), 'Exit');                 // the body
  await idle(page, 2500);
  await section(page, 'joi-bodies', async () => { await snap(page, 'joi-bodies'); });
});
