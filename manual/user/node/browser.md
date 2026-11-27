---
label: Firefox
icon: browser
order: 503
---

# Firefox

The Joi extension for Firefox lets her work in your own browser, through the node on the same machine: see what plays and control
it, see your tabs, switch to one, and open a page. It is off until you turn it on, it works only on the sites you allow, and every
call goes through the node's [harness](harness.md): its policy, its questions and its audit.

The extension talks only to the node on this machine, never to the server or the internet. The node starts nothing in Firefox by
itself: it answers what she asks, as the policy says.

## Setting it up

1. **Install the extension.** It is signed by Mozilla as an unlisted add-on: open the `.xpi` file in Firefox and confirm. (A
   developer can load `joi-browser/src` from `about:debugging` → *This Firefox* → *Load Temporary Add-on* instead; it is gone at the
   next start of Firefox.)
2. **Connect Firefox to the node.** In the JavaFX node: **Joi → Browser…** → **Connect Firefox**. In the terminal node:
   `/browser connect`. This registers the node with Firefox. Restart Firefox if it was already open.
3. **Turn it on.** The switch in the same dialog, or `/browser on`.

The Joi button in Firefox's toolbar shows whether the node has it. A red **!** means that it has not: the popup says why (the node
is not running, or Firefox is not connected to it yet).

Only one node's home is connected at a time: the last one where you clicked **Connect Firefox**.

## The sites she may work on

At first she may work on no site. On a page, open the Joi button and click **Let Joi work on this site**. Firefox asks you to
confirm. The popup lists the sites you allowed, each with **Remove**.

Without your permission for a site she still sees, with `list_tabs` and `media_status`, what Firefox shows any extension: a tab's
title and address, and whether it plays sound. She can mute and unmute such a tab, but she cannot read or control what plays
there.

Some sites are refused whatever you allow: mail, password managers and payments (`deniedSites` in `policy.json`; add your bank
there). She does not see the title or the address of their tabs. Sign-in pages are not refused as such; they follow your
permission for the site. Private windows are left out.

## What she can do

| Tool | What it does | Asked? |
|---|---|---|
| `media_status` | what plays or is paused in which tab: title, position, volume, speed, captions | no |
| `media_control` | play, pause, seek, volume, mute, speed, captions, next, previous (YouTube has its own next, previous and captions) | not when you asked for it in this turn; otherwise yes |
| `list_tabs` | the open tabs: title, address, which is active, which plays sound | yes |
| `focus_tab` | switch to a tab | not when you asked for it in this turn; otherwise yes |
| `open_url` | open an `http` or `https` page in a new tab | yes |

"When you asked for it in this turn" means the turn started from your own message on this node. A turn she started herself (an
event, a task) asks you.

The questions are answered like the harness's: **Allow once**, **Allow for session** (the same tool on the same site until the node
quits) or **Deny**; in the terminal node `/yes`, `/session` or `/no`.

## Changing the tiers

Each tool's tier is in `policy.json` in the node's home, under `browserTools`: `allow`, `asked` (silent when you asked for it in
this turn), `ask` or `off` (she does not get the tool). A tool you leave out keeps its default.

```json
{
  "browser": "on",
  "browserTools": { "list_tabs": "allow", "open_url": "off" },
  "deniedSites": ["mail.google.com", "mybank.example"]
}
```

Writing `deniedSites` replaces the default list. Copy the defaults into it if you want to keep them (they are in the developer docs,
`Policy.DEFAULT_DENIED_SITES`).

## What is logged

Every call is in the node's `audit.jsonl`, like the harness's: the tool, the site (not the whole address), what you answered. Never
a page's text or a title.
