// The browser companion of Joi (DESIGN.md → Node → Browser companion): the background page holds the native port to the Joi node
// (the host "joi_node", which relays to the running node) and answers the node's requests. It works only on the sites the user
// granted from the popup (optional host permissions); elsewhere it sees what the tabs API shows. Private windows are left out.
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

async function granted(tab) {
  if (!webPage(tab)) {
    return false;
  }
  try {
    return await browser.permissions.contains({ origins: [new URL(tab.url).origin + "/*"] });
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
  return undefined;
});

connect();
