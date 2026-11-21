// Step 08-animations -> 09-conversation: two exchanges with her through the API (the fake model needs to run: bin/start-llm.sh).
// First she writes a memory (the function `remember`) and answers with reasoning, then she asks to read a page (`fetch_page`), which waits for
// the user's approval. Then the pictures of her Chats, Memories and Tasks tabs. The answers are queued (llm/mock-llm.mjs /_queue).
import {idle, login, main, openTab, section, sleep, snap} from '../lib/shot.mjs';

const server = process.env.SHOT_URL || 'http://127.0.0.1:8099';
const llm = `http://127.0.0.1:${process.env.SHOT_LLM_PORT || 8098}`;
const post = (url, body) => fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
const queue = answer => post(llm + '/_queue', answer);

const { jois } = await (await fetch(server + '/api/v1/jois')).json();
const joi = jois[0].id;
await post(llm + '/_reset', {});

async function say(text, answers) {
  for (const answer of answers) await queue(answer);
  const res = await post(`${server}/api/v1/jois/${joi}/chat`, { text, speak: false });
  const body = await res.json();
  console.log('  she says:', JSON.stringify(body.text ?? body).slice(0, 160));
}

await say('Good morning! I had green tea again today, I drink it every morning before work. Please remember that.', [
  { toolCalls: [{ name: 'remember', arguments: { text: 'The user drinks green tea every morning before work.', keywords: 'green tea, morning, work', importance: 'medium' } }] },
  { reply: 'Good morning! I wrote it down: green tea every morning, before work. Is it a habit you like, or one you keep out of routine?',
    reasoning: 'The user told me a fact about their mornings and asked me to remember it. I wrote it as a medium memory and I answer warmly, with a question that goes on.' },
]);
await say('A habit I like. Could you read https://example.com/green-tea-guide and tell me which tea it recommends?', [
  { toolCalls: [{ name: 'fetch_page', arguments: { url: 'https://example.com/green-tea-guide', question: 'Which green tea does the page recommend?' } }] },
  { reply: 'I will read it. I need your approval for the page first; it is waiting in my Tasks.',
    reasoning: 'The user gave me a page to read. Reading it needs the user\'s approval, so I ask for it and tell them.' },
]);

await main(async ({ page }) => {
  await login(page);
  await openTab(page, 'Jois');
  await page.locator('vaadin-grid vaadin-button:has(vaadin-icon[icon$=":folder-open"])').first().click();
  await idle(page, 2500);
  const inner = async name => {
    await page.locator('vaadin-tab').filter({ hasText: new RegExp('^\\s*' + name + '\\s*$') }).last().click();
    await idle(page, 1500);
  };
  await section(page, 'joi-chats', async () => { await inner('Chats'); await snap(page, 'joi-chats'); });
  await section(page, 'joi-memories', async () => { await inner('Memories'); await snap(page, 'joi-memories'); });
  await section(page, 'joi-tasks-waiting', async () => { await inner('Tasks'); await snap(page, 'joi-tasks-waiting'); });
});
