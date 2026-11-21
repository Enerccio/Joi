// Step 01-admin -> 02-saiki: the Saiki model (a VRM, see SCREENSHOTS.md) uploaded as a new model in the Bodies tab, and saved (Exit).
// The file is made from the user's own download (tmp/saiki.zip holds source/saiky_with_tail.zip, which holds the .vrm files); its
// clothes are primitives of one mesh, so dataset/split_saiki.py gives each its own node (a toggle for Joi):
//   unzip -oq tmp/saiki.zip -d tmp/saiki-work && unzip -oq tmp/saiki-work/source/saiky_with_tail.zip saiki_with_bag.vrm -d tmp/saiki-work/vrm
//   python3 screenshots/dataset/split_saiki.py tmp/saiki-work/vrm/saiki_with_bag.vrm tmp/saiki-work/vrm/saiki_split.vrm
import fs from 'node:fs';
import path from 'node:path';
import {dialog, fill, idle, login, main, openTab, repo, section, sleep, snap, snapDialog} from '../lib/shot.mjs';

const vrm = path.join(repo, 'tmp', 'saiki-work', 'vrm', 'saiki_split.vrm');
if (!fs.existsSync(vrm)) throw new Error(`${vrm} is missing: make it as the top of this file says`);

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Bodies');
  await page.getByRole('button', { name: 'Add model', exact: true }).click();
  await idle(page, 1500);
  await fill(page, 'Name', 'Saiki');
  await page.locator('vaadin-upload input[type=file]').setInputFiles(vrm);
  // the upload is 24 MB: the dialog says what the file is once it is written and read
  await page.getByText('Saiki', { exact: false }).first().waitFor();
  await page.waitForFunction(() => /VRM\s+\d/.test(document.body.innerText) || /expressions/.test(document.body.innerText), null, { timeout: 120000 });
  await sleep(4000);                       // the viewer loads the model
  await idle(page, 1500);
  await section(page, 'model-dialog', async () => {
    await snap(page, 'model-dialog');
  });
  const rows = (await page.locator('.toggle-row label').allInnerTexts()).filter(t => t && t !== 'Describe' && t !== 'Auto description' && t !== 'Part of');
  console.log('  toggles:', rows.join(', '));
  if (rows.length < 10) throw new Error('the toggles of the split model are missing: ' + rows.join(', '));
  await page.getByRole('button', { name: 'Exit', exact: true }).click();     // writes the .joim
  await idle(page, 4000);
  await section(page, 'models-list', async () => {
    await snap(page, 'tab-bodies-saiki');
  });
});
