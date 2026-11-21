// Only looks: the model dialog of Saiki (expressions and toggles, the Spring bones panel) and the outfit dialog of her body.
import {dialog, idle, login, main, openTab, press, rowIconButton, section, sleep, snap, snapDialog} from '../lib/shot.mjs';

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Bodies');
  await idle(page, 800);
  await (await rowIconButton(page, 'Saiki', 'folder-open')).click();
  await idle(page, 5000);
  await section(page, 'model-toggles', async () => {
    await page.getByText('Toggles', { exact: true }).first().scrollIntoViewIfNeeded();
    await idle(page, 600);
    await snap(page, 'model-toggles');
  });
  await section(page, 'model-spring-bones', async () => {
    await page.getByText('Spring bones', { exact: true }).first().scrollIntoViewIfNeeded();
    await idle(page, 800);
    await snap(page, 'model-spring-bones');
  });
  await press(page, 'Exit').catch(() => {});
});
