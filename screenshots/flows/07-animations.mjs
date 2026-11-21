// Step 07-body -> 08-animations: a library of clips (the FBX files of tmp/animations, converted with Blender, one at a time), three
// animations made of them, and the roles of her Animations tab chosen. The clips are the user's own test files, kept in tmp/.
import path from 'node:path';
import {dialog, field, fill, idle, login, main, openTab, pick, press, repo, rowIconButton, section, sleep, snap, snapDialog} from '../lib/shot.mjs';

const clips = ['Sleeping Idle On Belly', 'Sleeping Idle Wakeup', 'Idle', 'Happy Idle', 'Talking', 'Talking 2', 'Standing Greeting', 'Waving for Attention'];

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Clips');
  await idle(page, 800);
  for (const clip of process.env.SKIP_UPLOAD ? [] : clips) {
    await page.locator('vaadin-upload input[type=file]').setInputFiles(path.join(repo, 'tmp', 'animations', clip + '.fbx'));
    await page.getByRole('gridcell', { name: clip, exact: true }).or(page.locator('vaadin-grid-cell-content', { hasText: new RegExp('^' + clip + '$') })).first()
      .waitFor({ timeout: 180000 });
    console.log('  converted', clip);
    await idle(page, 800);
  }
  if (!process.env.SKIP_UPLOAD) await section(page, 'tab-clips-library', async () => { await snap(page, 'tab-clips-library'); });
  await section(page, 'clip-dialog', async () => {
    await (await rowIconButton(page, 'Happy Idle', 'folder-open')).click();
    await sleep(5000);
    await idle(page, 1000);
    await snap(page, 'clip-dialog');
    await press(page, 'Exit');
    await idle(page, 800);
  });
  // an animation of a clip is made with the button of the clip's row; more clips are added in its dialog
  async function makePack({ clip, name, description, extra = [], loopStep = false, loopAll = false, shot = null }) {
    await (await rowIconButton(page, clip, 'layer-group')).click();
    await sleep(4000);
    const d = dialog(page);
    await fill(d, 'Name', name);
    await fill(d, 'Description', description);
    if (extra.length) {
      for (const more of extra) {
        await d.locator('vaadin-multi-select-combo-box').first().click();
        await sleep(500);
        await page.getByRole('option', { name: more, exact: true }).first().click();
        await sleep(300);
        await page.keyboard.press('Escape').catch(() => {});
      }
      await press(d, 'Add');
      await sleep(1500);
    }
    if (loopStep) await d.locator('vaadin-grid vaadin-checkbox').first().click();
    if (loopAll) await d.getByText('Loop the whole animation', { exact: true }).click();
    await idle(page, 1500);
    if (shot) await section(page, shot, async () => { await snap(page, shot); });
    await press(d, 'Exit');
    await idle(page, 1000);
  }
  await openTab(page, 'Clips');
  await idle(page, 800);
  await makePack({ clip: 'Sleeping Idle On Belly', name: 'Asleep', description: 'She sleeps on her belly, breathing slowly.', loopStep: true });
  await makePack({ clip: 'Sleeping Idle Wakeup', name: 'Waking up', description: 'She wakes up from sleeping on her belly and stretches.' });
  await makePack({ clip: 'Idle', name: 'Standing idle', description: 'She stands and waits, shifting a little.', loopAll: true });
  await makePack({ clip: 'Talking', name: 'Talking', description: 'She talks with her hands.', extra: ['Talking 2'], loopAll: true });
  await makePack({ clip: 'Standing Greeting', name: 'Greeting', description: 'She greets, then waves to get attention.', extra: ['Waving for Attention'], shot: 'animation-dialog' });

  // the roles of her Animations tab
  await openTab(page, 'Jois');
  await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
  await idle(page, 2500);
  await page.locator('vaadin-tab').filter({ hasText: /^\s*Animations\s*$/ }).last().click();
  await idle(page, 1200);
  for (const [role, value] of [['Asleep', 'Asleep'], ['Waking up', 'Waking up'], ['Idle', 'Standing idle'], ['Talking', 'Talking']]) {
    await page.locator('vaadin-combo-box, vaadin-multi-select-combo-box').filter({ has: page.locator('label', { hasText: new RegExp('^' + role + '$') }) }).first().click();
    await sleep(500);
    if (role === 'Idle' || role === 'Talking') {
      await page.getByRole('option', { name: value, exact: true }).first().click();
      await sleep(500);
      await page.keyboard.press('Escape').catch(() => {});
    } else {
      await page.keyboard.type(value);
      await sleep(500);
      await page.keyboard.press('Enter');
    }
    await sleep(300);
  }
  await idle(page, 1000);
  await section(page, 'joi-animations-roles', async () => { await snap(page, 'joi-animations-roles'); });
  await press(page, 'Exit');
  await idle(page, 1500);
  await section(page, 'tab-animations-library', async () => {
    await openTab(page, 'Animations');
    await idle(page, 800);
    await snap(page, 'tab-animations-library');
  });
});
