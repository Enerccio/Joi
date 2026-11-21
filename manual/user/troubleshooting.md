---
label: Troubleshooting
icon: question
order: 400
---

# Troubleshooting

## The server does not start

- Check the Java version: the server needs JDK 25 or newer. Maven on the path may run on an older one: set `JAVA_HOME`.
- A dev database from an older schema fails the migration's checksum. Do not delete it: upgrade it with
  `python3 tools/upgrade_dev_db.py` with the server stopped. It backs the database up, rebuilds it from the current schema and
  copies the data.
- Another program on the port: the development server uses 8080, change it with `-Djetty.http.port=`.
- Look at the server's log.

## I cannot log in

The first visit makes the administrator. If you forgot the password, an administrator can change it in **Administration →
Users**. Behind a proxy, set `joi.trusted-proxies` ([Install the server](getting-started/server.md)).

## A node does not connect

- The address must be the server's, as `http://host:8080`, and reach it from the node's machine.
- *The server wants a token*: make one in **User Settings → Access**, or leave the field empty to pair.
- A pairing or token that was revoked, or a way the administrator turned off (**Administration → API access**), stops working at
  once.
- A node that lost the connection tries again by itself; **Reconnect now** tries at once.
- Behind a proxy, the proxy must pass WebSocket upgrades.

## She does not answer, or the answer is an error

- Does she have a **Chat model**, and does it work? **Models → Text Models**, **Test Connection**.
- A model that is slow to load answers late the first time.
- The error shows in the node's chat and in the **Chats** tab.
- If a model writes a function call into its words (it is spoken instead of done), set the **Function call rule** of the model or tick
  **Ask again when a reply opens with a call written as text** in its settings. See [Text models](models/text-models.md).

## She is silent

She needs a **TTS model** and a **Voice actor** (**About** tab). Test the model with **Test Connection** and the voice with **Try the
voice**. See [Speech](models/speech.md). A speech server on the GPU waits for its lock.

## Conversion with Blender fails

- Is Blender found? It is `joi.blender` (`JOI_BLENDER`), else Blender.app on a Mac, else `blender` on the path. The error says so when
  it is not.
- Does it have the **VRM format** add-on?
- The log in the model's dialog shows what Blender said.
- Only one conversion runs at a time: a second waits.
- A model with too many meshes: leave out props and extra outfits in **Meshes**, lower **Largest texture**.

## A clip plays wrong

The rest pose matters: an FBX without a mesh has no bind pose. If a limb twists, rotate the step (**Rotation of the step**). For
a clip made facing another way, use **In place** and the rotation. See [Animations of an assistant](animations/animations.md).

## The description and snapshots say out of date

The model changed since. **Take snapshots** and **Propose a description** again. See [Appearance](bodies/appearance.md).

## A web search returns nothing

DuckDuckGo has no official API and sometimes answers with no results page. Add a second source (SearXNG on your own network is
reliable). **Test** a source in its dialog shows the error. See [Web search and pages](tools/web-search.md).
