// Fresh installation (step 00-empty): the administrator dialog, then the login card. Result: step 01-admin.
import {fill, goto, idle, launch, snap, users} from '../lib/shot.mjs';

const { browser, page } = await launch();
await goto(page, '/');
await fill(page, 'Username', users.admin.login);
await fill(page, 'Full Name', users.admin.name);
await fill(page, 'Password', users.admin.password);
await fill(page, 'Repeat password', users.admin.password);
await page.locator('body').click({ position: { x: 10, y: 10 } });
await snap(page, 'first-start', { clip: await cardClip(page) });
await page.getByRole('button', { name: 'OK' }).click();
await idle(page, 1500);
await page.locator('body').click({ position: { x: 10, y: 10 } });
await page.evaluate(() => document.activeElement && document.activeElement.blur());
// the platform's build text under the button ("Marginalia dev": a build detail) is left out
await page.evaluate(() => {
  const walk = root => {
    for (const el of root.querySelectorAll('*')) {
      if (el.shadowRoot) walk(el.shadowRoot);
      if (el.children.length === 0 && /^\s*Marginalia\b/.test(el.textContent)) el.style.display = 'none';
    }
  };
  walk(document);
});
await idle(page, 400);
const form = await page.locator('vaadin-login-form-wrapper').first().boundingBox();
const brand = 180;                                    // the blue header (the name and the slogan) above the form
await snap(page, 'login', { clip: { x: form.x - 20, y: form.y - brand - 20, width: form.width + 40, height: form.height + brand + 40 } });
await browser.close();

/** The dialog card (the first start asks for the administrator in a dialog, the next visits show the login form). */
async function cardClip(page) {
  const card = page.locator('vaadin-dialog[opened] [part="overlay"], vaadin-login-form-wrapper').first();
  const box = await card.boundingBox();
  return { x: box.x - 20, y: box.y - 20, width: box.width + 40, height: box.height + 40 };
}
