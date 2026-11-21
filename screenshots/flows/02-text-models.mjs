// Step 02-saiki -> 03-models: two text models, both pointing at the fake server (llm/mock-llm.mjs). *Local chat* answers the
// conversation and the small jobs, *Local reasoner* the long tasks and the jobs that look at pictures (it is marked multimodal:
// the fake model "sees" nothing, it answers the photoshoot and the appearance from llm/rules.mjs).
// Order matters: General Settings first, then Per Type Settings, then OK (the dialog forgets the key otherwise).
import {dialog, field, fill, idle, llmUrl, login, main, openTab, pick, press, sleep, snap} from '../lib/shot.mjs';

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Models');
  await idle(page, 600);

  async function addModel({ name, model, used, ctx = 32768, resp = 2048, reasoning = null, multimodal = false }) {
    await press(page, 'Add model');
    const d = dialog(page);
    await fill(d, 'Name', name);
    for (const role of used) {
      await d.locator('vaadin-multi-select-combo-box, vaadin-combo-box').filter({ hasText: /Used for/ }).first().click();
      await sleep(400);
      await page.getByRole('option', { name: role, exact: true }).first().click();
      await sleep(300);
      await page.keyboard.press('Escape').catch(() => {});
    }
    await fill(d, 'Max Context Size', String(ctx));
    await fill(d, 'Max Response Tokens', String(resp));
    if (reasoning) {
      await field(d, 'Enable Reasoning').check();
      await idle(page, 300);
      await pick(d, 'Reasoning Effort', reasoning);
    }
    if (multimodal) await field(d, 'Multimodal (accepts images)').check();
    await d.getByText('Per Type Settings', { exact: true }).click();
    await idle(page, 400);
    await fill(d, 'OpenAI URL (ends with /v1)', llmUrl);
    await fill(d, 'Api Key', 'demo-key');
    await press(d, 'Refresh');
    await sleep(800);
    await pick(d, 'Model', model);
    await idle(page, 400);
    await snap(page, '_model-dialog-' + name.replace(/\W+/g, '-'));
    await press(d, 'OK');
  }

  await addModel({ name: 'Local chat', model: 'joi-demo-12b', used: ['Chat', 'Auxiliary'] });
  await addModel({ name: 'Local reasoner', model: 'joi-demo-reasoner', used: ['Deep reasoning'], resp: 4096, reasoning: 'Medium', multimodal: true });
  await snap(page, 'models-text');
});
