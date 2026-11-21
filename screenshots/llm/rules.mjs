// Rules of the fake inference server: the first rule whose `when(ctx)` is true answers.
// ctx: { text, system, last, messages, model, stream, wantsJson, hasTools, mode, body }
// A rule answers with `reply` (string, or function(ctx) returning a string or {reply, reasoning, status, message}),
// optionally `reasoning` and `delayMs`. A flow that needs one exact answer queues it instead (POST /_queue, see mock-llm.mjs).
//
// When a new kind of request shows up, look at GET /_log (or .run/llm-requests.jsonl), then add a rule above the default.

import {description, outfits, toggleNames} from '../dataset/saiki.mjs';

// the appearance of a model (SELF_IMAGE_PROMPT): JSON with the description and a name for each toggle of the table, by its number
// ("number | mesh / key | name now | ..."): the name is looked up by the node's name that the row ends its second column with
const selfImage = {
  name: 'self-image',
  when: ctx => ctx.last.includes('You look at images of a 3D avatar and describe it'),
  reply: ctx => {
    const toggles = {};
    for (const line of ctx.last.split('\n')) {
      const m = /^\s*(\d+)\s*\|\s*([^|]+?)\s*\|/.exec(line);
      if (!m) continue;
      const node = m[2].split('/')[0].trim();
      if (toggleNames[node]) toggles[m[1]] = toggleNames[node];
    }
    return { reply: JSON.stringify({ description, toggles }), reasoning: 'The renders show a schoolgirl in a uniform with cat ears.' };
  },
};

// the outfit (OUTFIT_IMAGE_PROMPT): the description only, by the outfit's name
const outfitImage = {
  name: 'outfit-image',
  when: ctx => ctx.last.includes('in an outfit: "'),
  reply: ctx => {
    const name = /in an outfit: "([^"]+)"/.exec(ctx.last)[1];
    return outfits[name] ?? outfits['School uniform'];
  },
};

const greeting = {
  name: 'greeting',
  when: ctx => /\b(hello|hi|good (morning|evening))\b/i.test(ctx.last),
  reply: 'Hello! I am here. How was your day?',
};

// the node's connect: the greeting event of the server ("[Event: ... is back with you ...]")
const welcomeBack = {
  name: 'welcome-back',
  when: ctx => ctx.last.includes('is back with you'),
  reply: 'Welcome back! The page you gave me is still waiting for your approval, whenever you like.',
};

const fallback = {
  name: 'default',
  when: () => true,
  reply: 'I hear you. Tell me more.',
};

export default [selfImage, outfitImage, welcomeBack, greeting, fallback];
