---
label: Firefox
icon: browser
order: 503
---

# Firefox

The Joi extension for Firefox lets her work in your own browser, through the node on the same machine: see what plays and control
it, see your tabs, switch to one, open a page, read a page and take a picture of it. It is off until you turn it on, it works only on the sites you allow, and every
call goes through the node's [harness](harness.md): its policy, its questions and its audit.

The extension talks only to the node on this machine, never to the server or the internet. The node starts nothing in Firefox by
itself: it answers what she asks, as the policy says.

## Setting it up

1. **Install the extension.** It is signed by Mozilla as an unlisted add-on: open the `.xpi` file in Firefox and confirm. (A
   developer can load `joi-browser/src` from `about:debugging` → *This Firefox* → *Load Temporary Add-on* instead; it is gone at the
   next start of Firefox.)
2. **Connect Firefox to the node.** In the JavaFX node: **Joi → Browser…** → **Connect Firefox**. In the terminal node:
   `/browser connect`. This registers the node with Firefox. The extension tries to reach the node every 30 seconds, so within half a
   minute the Joi button shows that it is connected; Firefox needs no restart.
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
| `media_control` | play, pause, seek, volume, mute, speed, captions, next, previous (YouTube has its own next, previous and captions) | no: it works only on the sites you allowed in Firefox |
| `list_tabs` | the open tabs: title, address, which is active, which plays sound | yes |
| `focus_tab` | switch to a tab | not when you asked for it in this turn; otherwise yes |
| `open_url` | open an `http` or `https` page in a new tab | yes, and always after she read a page in the same turn |
| `read_page` | a page's main text (without its menus, up to 50 000 characters), its title and address, or only what you selected | yes |
| `page_screenshot` | a picture of a tab: what is visible, or the whole page (up to 16 000 pixels high) | yes |

"When you asked for it in this turn" means the turn started from your own message on this node. A turn she started herself (an
event, a task) asks you.

The questions are answered like the harness's: **Allow once**, **Allow for session** (that tool, on every site, until the node
quits) or **Deny**; in the terminal node `/yes`, `/session` or `/no`. The node does not ask again for each site: which sites she
may work on is what you allow from the Joi button in Firefox, and the refused sites below.

## Reading a page and taking a picture

`read_page` and `page_screenshot` work only on the sites you allowed, on the active tab unless she names another one. She sees a
picture after the results of her tools, as with a picture on this machine (see [Files and shell](harness.md)); if her chat model
cannot see pictures, the describing model writes what it shows. The Joi button shows **pic** for a few seconds when a picture was
taken.

Firefox lets an extension take a picture of a tab only with its permission for all sites. So pictures have their own switch in the
Joi button's popup: **Pictures of pages**. Firefox asks you to confirm it. The extension keeps its own list of the sites you
allowed, and it still works, and takes pictures, only on them. A page with a password field in view is never pictured.

What she reads or sees on a page is data for her, never instructions. A page could still try to make her open an address, so for the
rest of a turn in which she read a page or took a picture, `open_url` is asked every time, whatever its tier or an earlier **Allow for
session**, and the question says that she read a page first.

## Changing the tiers

**Joi → Browser…** lists the tools with a choice for each:

| Choice | Meaning |
|---|---|
| **Allow** | never asked |
| **When I ask** | done at once when you asked for it in this turn, otherwise asked |
| **Ask each time** | always asked (Allow for session lets it go on until the node quits) |
| **Off** | she does not get the tool |

In the terminal node: `/browser` shows them, `/browser <tool> allow`, `asked`, `ask` or `off` changes one (`/browser open_url allow`).
The choice is kept in `policy.json` in the node's home, under `browserTools`; a tool at its default is left out. It can be
edited by hand too:

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
a page's text, a picture or a title.
