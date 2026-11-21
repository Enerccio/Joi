// Step 05-user-models -> 06-appearance: how she looks in Saiki. The renders are taken by the viewer in the browser, the "model" that
// looks at them is the fake one: llm/rules.mjs answers the request with the text of dataset/saiki.mjs. The proposal is applied.
import {idle, login, main, openTab, press, section, sleep, snap} from '../lib/shot.mjs';

const sidebar = { x: 16, y: 78, width: 760, height: 760 };

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Bodies');
  await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
  await idle(page, 4000);
  await press(page, 'Take snapshots');
  await page.waitForFunction(() => document.querySelectorAll('.model-sidebar img').length >= 2, null, { timeout: 120000 }).catch(() => {});
  await sleep(8000);
  await idle(page, 1000);
  await page.getByText('How she looks in it', { exact: false }).first().scrollIntoViewIfNeeded();
  await section(page, 'appearance-snapshots', async () => { await snap(page, 'appearance-snapshots'); });
  await press(page, 'Propose a description');
  await page.waitForFunction(() => !/looking at the images/.test(document.body.innerText), null, { timeout: 90000 });   // the request and the answer
  await idle(page, 1500);
  await idle(page, 1500);
  await page.getByText('Proposed description', { exact: true }).scrollIntoViewIfNeeded();
  await idle(page, 800);
  await section(page, 'appearance-proposal', async () => { await snap(page, 'appearance-proposal'); });
  await press(page, 'Use it');
  await idle(page, 1500);
  await page.getByText('How she looks (what never changes)', { exact: true }).scrollIntoViewIfNeeded();
  await idle(page, 600);
  await section(page, 'appearance-applied', async () => { await snap(page, 'appearance-applied'); });
  await press(page, 'Exit');                // writes the .joim with what was decided
  await idle(page, 4000);
});
