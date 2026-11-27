// The popup: whether the Joi node has the extension, the sites Joi may work on (the extension's list in storage, each also an optional
// host permission, granted here) and whether she may take pictures of pages (Firefox's permission for all sites, which captureTab needs).
"use strict";

const STATES = {
  connected: ["Connected to the node", "ok", null],
  absent: ["The node is not running", "bad", "Start the Joi node on this machine; it connects by itself."],
  no_host: ["Not set up", "bad", "On the Joi node: Joi → Browser… → Connect Firefox (the terminal node: /browser connect). It connects within half a minute."],
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

async function siteList() {
  // the background page makes the list the first time, from the sites granted before
  return (await browser.runtime.sendMessage({ type: "get_sites" })) || [];
}

async function setSites(list) {
  await browser.storage.local.set({ sites: list });
}

async function removeSite(origin) {
  await setSites((await siteList()).filter((o) => o !== origin));
  try {
    await browser.permissions.remove({ origins: [origin] });
  } catch (e) {
    // kept by the permission for pictures: the list is what counts
  }
}

async function showSites() {
  const origins = await siteList();
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
      await removeSite(origin);
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
  const has = (await siteList()).includes(pattern);
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
    if (await browser.permissions.request({ origins: [pattern] })) {
      const list = await siteList();
      if (!list.includes(pattern)) {
        await setSites(list.concat([pattern]));
      }
    }
    await refresh();
  };
  revoke.onclick = async () => {
    await removeSite(pattern);
    await refresh();
  };
}

async function showPictures() {
  const box = document.getElementById("pictures");
  box.checked = await browser.permissions.contains({ origins: ["<all_urls>"] });
  // permissions.request must run in the click itself
  box.onchange = async () => {
    if (box.checked) {
      box.checked = await browser.permissions.request({ origins: ["<all_urls>"] });
    } else {
      await browser.permissions.remove({ origins: ["<all_urls>"] });
    }
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
  await showPictures();
}

browser.runtime.onMessage.addListener((message) => {
  if (message && message.type === "state") {
    showState(message.state);
  }
});

refresh();
