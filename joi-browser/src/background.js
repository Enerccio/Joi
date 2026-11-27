// The browser companion of Joi (DESIGN.md → Node → Browser companion): the background page holds the native port to the Joi node
// (the host "joi_node", which relays to the running node) and answers the node's requests. It works only on the sites the user
// granted from the popup (its own list in storage, "sites", each also an optional host permission of Firefox); elsewhere it sees what
// the tabs API shows. Pictures of a tab need Firefox's permission for all sites ("<all_urls>", asked for by the popup's own switch):
// the list still decides where it works. Private windows are left out.
"use strict";

const HOST = "joi_node";
const VERSION = browser.runtime.getManifest().version;
const RETRY_MS = 30000;

let port = null;
// absent: no node runs; connected: the node has us; no_host: Firefox could not start the host (not connected from the node)
let state = { state: "connecting" };

function connect() {
  try {
    port = browser.runtime.connectNative(HOST);
  } catch (e) {
    setState({ state: "no_host", error: String(e && e.message || e) });
    setTimeout(connect, RETRY_MS);
    return;
  }
  port.onMessage.addListener(onMessage);
  port.onDisconnect.addListener((p) => {
    port = null;
    setState({ state: "no_host", error: p && p.error ? p.error.message : "The Joi node's host ended." });
    setTimeout(connect, RETRY_MS);
  });
}

function setState(next) {
  state = next;
  browser.browserAction.setBadgeText({ text: state.state === "connected" ? "" : "!" });
  browser.browserAction.setBadgeBackgroundColor({ color: "#b3261e" });
  browser.runtime.sendMessage({ type: "state", state }).catch(() => {});
}

function hello() {
  post({ type: "hello", browser: "firefox", version: VERSION });
}

function post(message) {
  if (port) {
    port.postMessage(message);
  }
}

async function onMessage(message) {
  if (!message || typeof message !== "object") {
    return;
  }
  if (message.type === "node") {
    setState({ state: message.state });
    if (message.state === "connected") {
      hello();
    }
    return;
  }
  if (message.type === "request") {
    try {
      const data = await handle(message.op, message.args || {});
      post({ type: "result", id: message.id, ok: true, data: data || {} });
    } catch (e) {
      post({ type: "result", id: message.id, ok: false, error: String(e && e.message || e) });
    }
  }
}

// ---- tabs -------------------------------------------------------------------------------------------------------------------

function siteOf(url) {
  try {
    const u = new URL(url);
    return u.protocol === "http:" || u.protocol === "https:" ? u.hostname : u.protocol.replace(":", "");
  } catch (e) {
    return "";
  }
}

function webPage(tab) {
  return /^https?:/.test(tab.url || "");
}

// the sites the user let Joi work on, as origin patterns ("https://www.youtube.com/*"): the extension's own list, since the permission
// for pictures ("<all_urls>") would make Firefox's answer yes for every site
async function sites() {
  const stored = await browser.storage.local.get("sites");
  if (Array.isArray(stored.sites)) {
    return stored.sites;
  }
  // the first start of a version with the list: the sites granted before
  const all = await browser.permissions.getAll();
  const list = (all.origins || []).filter((o) => /^https?:\/\/[^*]/.test(o));
  await browser.storage.local.set({ sites: list });
  return list;
}

async function granted(tab) {
  if (!webPage(tab)) {
    return false;
  }
  try {
    const pattern = new URL(tab.url).origin + "/*";
    return (await sites()).includes(pattern) && await browser.permissions.contains({ origins: [pattern] });
  } catch (e) {
    return false;
  }
}

async function picturesAllowed() {
  try {
    return await browser.permissions.contains({ origins: ["<all_urls>"] });
  } catch (e) {
    return false;
  }
}

async function openTabs() {
  const tabs = await browser.tabs.query({});
  return tabs.filter((t) => !t.incognito);
}

async function tabById(id) {
  const tab = await browser.tabs.get(id);
  if (!tab || tab.incognito) {
    throw new Error("There is no such tab.");
  }
  return tab;
}

async function info(tab) {
  return {
    id: tab.id,
    title: tab.title || "",
    url: tab.url || "",
    site: siteOf(tab.url || ""),
    active: !!tab.active,
    audible: !!tab.audible,
    muted: !!(tab.mutedInfo && tab.mutedInfo.muted),
    granted: await granted(tab),
  };
}

// ---- media in a page ----------------------------------------------------------------------------------------------------------

async function inPage(tab, op, args) {
  if (!(await granted(tab))) {
    throw new Error("The user has not let Joi work on " + siteOf(tab.url) + ": they can allow it from the Joi button in Firefox.");
  }
  await browser.tabs.executeScript(tab.id, { file: "media.js" });
  const results = await browser.tabs.executeScript(tab.id, {
    code: "window.__joiMedia(" + JSON.stringify(op) + ", " + JSON.stringify(args) + ")",
  });
  return results && results.length ? results[0] : null;
}

async function withMedia(tab) {
  const t = await info(tab);
  if (t.granted) {
    try {
      t.media = await inPage(tab, "status", {});
    } catch (e) {
      t.media = null;
    }
  }
  return t;
}

async function mediaTabs() {
  const out = [];
  for (const tab of await openTabs()) {
    if (!webPage(tab)) {
      continue;
    }
    const t = await withMedia(tab);
    if (t.media || t.audible) {
      out.push(t);
    }
  }
  return out;
}

// the tab a media tool works on when none is named: the one that plays, else one with paused media, else the active one
async function pickMediaTab() {
  const tabs = await mediaTabs();
  const playing = tabs.find((t) => t.media && t.media.playing) || tabs.find((t) => t.audible);
  const any = playing || tabs.find((t) => t.media);
  if (any) {
    return any;
  }
  throw new Error("Nothing plays in Firefox now.");
}

async function mediaControl(args) {
  const tab = await tabById(args.tab);
  if (args.action === "mute" || args.action === "unmute") {
    await browser.tabs.update(tab.id, { muted: args.action === "mute" });
    const t = await withMedia(await browser.tabs.get(tab.id));
    return t;
  }
  await inPage(tab, "control", { action: args.action, value: args.value });
  return await withMedia(await browser.tabs.get(tab.id));
}

// ---- a page: its text, a picture --------------------------------------------------------------------------------------------

const MAX_PICTURE_WIDTH = 2000;
const MAX_PICTURE_HEIGHT = 16000;

async function onPage(tab, op, args) {
  if (!(await granted(tab))) {
    throw new Error("The user has not let Joi work on " + siteOf(tab.url) + ": they can allow it from the Joi button in Firefox.");
  }
  await browser.tabs.executeScript(tab.id, { file: "page.js" });
  const results = await browser.tabs.executeScript(tab.id, {
    code: "window.__joiPage(" + JSON.stringify(op) + ", " + JSON.stringify(args) + ")",
  });
  return results && results.length ? results[0] : null;
}

async function readPage(args) {
  const tab = await tabById(args.tab);
  const page = await onPage(tab, "read", { selection: !!args.selection });
  return Object.assign(await info(tab), page);
}

let badgeTimer = null;

// a sign on the button that a picture was taken, for a few seconds
function flashPicture() {
  browser.browserAction.setBadgeBackgroundColor({ color: "#1565c0" });
  browser.browserAction.setBadgeText({ text: "pic" });
  clearTimeout(badgeTimer);
  badgeTimer = setTimeout(() => setState(state), 4000);
}

async function screenshot(args) {
  const tab = await tabById(args.tab);
  if (!(await picturesAllowed())) {
    throw new Error("The user has not let Joi take pictures in Firefox: they can switch it on from the Joi button (Pictures of pages).");
  }
  const page = await onPage(tab, "measure", { full_page: !!args.full_page });
  if (page.password) {
    throw new Error("The page has a password field" + (args.full_page ? "" : " in view") + ", so no picture was taken.");
  }
  const width = args.full_page ? page.width : page.viewWidth;
  const height = args.full_page ? Math.min(page.height, MAX_PICTURE_HEIGHT) : page.viewHeight;
  const scale = Math.min(page.ratio, MAX_PICTURE_WIDTH / Math.max(1, width));
  const options = { format: "jpeg", quality: 85, scale };
  if (args.full_page) {
    options.rect = { x: 0, y: 0, width, height };
  }
  const url = await browser.tabs.captureTab(tab.id, options);
  flashPicture();
  const comma = url.indexOf(",");
  return Object.assign(await info(tab), {
    type: "image/jpeg",
    data: url.slice(comma + 1),
    width: Math.round(width * scale),
    height: Math.round(height * scale),
    cut: !!args.full_page && page.height > MAX_PICTURE_HEIGHT,
  });
}

// ---- the node's requests ------------------------------------------------------------------------------------------------------

async function handle(op, args) {
  switch (op) {
    case "tab_info": {
      if (args.tab !== undefined && args.tab !== null) {
        return await info(await tabById(args.tab));
      }
      if (args.pick === "media") {
        return await pickMediaTab();
      }
      if (args.pick === "active") {
        const [tab] = await browser.tabs.query({ active: true, lastFocusedWindow: true });
        if (!tab || tab.incognito) {
          throw new Error("No tab is active in Firefox.");
        }
        return await info(tab);
      }
      throw new Error("Name the tab.");
    }
    case "list_tabs": {
      const tabs = [];
      for (const tab of await openTabs()) {
        tabs.push(await info(tab));
      }
      return { tabs };
    }
    case "media_status":
      return { tabs: await mediaTabs() };
    case "media_control":
      return await mediaControl(args);
    case "read_page":
      return await readPage(args);
    case "page_screenshot":
      return await screenshot(args);
    case "focus_tab": {
      const tab = await tabById(args.tab);
      await browser.tabs.update(tab.id, { active: true });
      await browser.windows.update(tab.windowId, { focused: true });
      return {};
    }
    case "open_url": {
      if (!/^https?:\/\//i.test(String(args.url || ""))) {
        throw new Error("Only http and https addresses are opened.");
      }
      const tab = await browser.tabs.create({ url: args.url, active: true });
      return { id: tab.id };
    }
    default:
      throw new Error("This version of the extension does not know " + op + ".");
  }
}

// ---- the popup ------------------------------------------------------------------------------------------------------------------

browser.runtime.onMessage.addListener((message) => {
  if (message && message.type === "get_state") {
    return Promise.resolve({ state, version: VERSION });
  }
  if (message && message.type === "get_sites") {
    return sites();
  }
  return undefined;
});

connect();
