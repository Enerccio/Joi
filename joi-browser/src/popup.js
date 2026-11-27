// The popup: whether the Joi node has the extension, and the sites Joi may work on (optional host permissions, granted here).
"use strict";

const STATES = {
  connected: ["Connected to the node", "ok", null],
  absent: ["The node is not running", "bad", "Start the Joi node on this machine; it connects by itself."],
  no_host: ["Not set up", "bad", "On the Joi node: Joi → Browser… → Connect Firefox (the terminal node: /browser connect). Then restart Firefox."],
  connecting: ["Connecting…", "", null],
};

function originPattern(url) {
  try {
    const u = new URL(url);
    return u.protocol === "http:" || u.protocol === "https:" ? u.origin + "/*" : null;
  } catch (e) {
    return null;
  }
}

function showState(state) {
  const [text, kind, help] = STATES[state.state] || [state.state, "", null];
  const el = document.getElementById("state");
  el.textContent = text;
  el.className = "state " + kind;
  const helpEl = document.getElementById("help");
  helpEl.hidden = !help;
  helpEl.textContent = help || "";
}

async function showSites() {
  const all = await browser.permissions.getAll();
  const origins = (all.origins || []).filter((o) => /^https?:/.test(o));
  const list = document.getElementById("sites");
  list.textContent = "";
  for (const origin of origins) {
    const li = document.createElement("li");
    const name = document.createElement("span");
    name.textContent = origin.replace(/\/\*$/, "");
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "Remove";
    remove.addEventListener("click", async () => {
      await browser.permissions.remove({ origins: [origin] });
      await refresh();
    });
    li.append(name, remove);
    list.append(li);
  }
  document.getElementById("no-sites").hidden = origins.length > 0;
}

async function showSite() {
  const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
  const pattern = tab && originPattern(tab.url || "");
  const section = document.getElementById("site");
  section.hidden = !pattern;
  if (!pattern) {
    return;
  }
  const has = await browser.permissions.contains({ origins: [pattern] });
  const host = new URL(tab.url).hostname;
  document.getElementById("site-text").textContent = has
    ? "Joi may work on " + host + ": read and control what plays there."
    : "Joi does not work on " + host + ".";
  const grant = document.getElementById("grant");
  const revoke = document.getElementById("revoke");
  grant.hidden = has;
  revoke.hidden = !has;
  // permissions.request must run in the click itself
  grant.onclick = async () => {
    await browser.permissions.request({ origins: [pattern] });
    await refresh();
  };
  revoke.onclick = async () => {
    await browser.permissions.remove({ origins: [pattern] });
    await refresh();
  };
}

async function refresh() {
  const reply = await browser.runtime.sendMessage({ type: "get_state" });
  if (reply) {
    showState(reply.state);
  }
  await showSite();
  await showSites();
}

browser.runtime.onMessage.addListener((message) => {
  if (message && message.type === "state") {
    showState(message.state);
  }
});

refresh();
