---
label: Managing extensions
icon: plug
order: 70
---

# Managing extensions

The **Extensions** tab of the Administration page installs and removes [extensions](../extensions/index.md) for the whole
installation.

## Installing

Click **Load Extension (.jar)** (or drop the file on it) and choose the extension's JAR file. It is copied to the `extensions` folder of
the [data folder](../getting-started/server.md#the-data-folder) and started right away; *Extension loaded successfully.* confirms it.
It appears in the list:

| Column | |
|---|---|
| **ID** | The internal number of the extension in this run. |
| **Extension Name** | Its name. |
| **Version** | Its version. The **#** button shows the SHA-256 hash of the extension's JAR when you hover it; click it to copy the hash, to compare it with the one published by the author. |
| **State** | *Active* when it runs; *Stopped* when you stopped it; *Installed* or *Resolved* when it failed to start. |
| **Verification** | *Valid* or *Invalid*; click it to read the report of the check made before the extension was started. |
| **Actions** | **Stop** / **Start**, **Log** and **Unload**. |

What an extension adds shows in the windows you open from then on: a workspace that is open already needs a reload of the page.

A script can do the same through the API with a token that may *Manage the extensions*: see
[Administration from a script](../access.md#administration-from-a-script).

JAR files can also be copied into the `extensions` folder directly, for example on a server without access to the web UI; they are loaded
on the next start. This is how the Docker setup is prepared: see [Extensions](../getting-started/docker-server.md#extensions).

!!!warning Use extensions built for this version
An extension is built for one version of Joi. Use the ones built from the same source as the server. An incompatible extension may
fail to load, and is marked *Invalid*.
!!!

## Stopping and starting

**Stop** unloads the extension at once without removing it: what it added goes away, its JAR and its data stay, and it is listed as
*Stopped*. **Start** loads it again (checked first, like at the start of the server; if the check fails it stays stopped and the report is
shown). A stopped extension stays stopped after a restart. The stop is remembered by an empty file named like the JAR with `.STOPPED` added
(`grok-0.1.0-SNAPSHOT.jar.STOPPED`) in the `extensions` folder: create it to keep an extension from starting on a server without the web
UI, delete it to let it start.

## The extension log

**Log** opens the log the extension writes itself: the last part of the file `extensions/<name>.jar.log`. **Refresh** reads it again and
**Clear** deletes it. A file over 2 MB is moved to `.log.old` and a new one is started; it is deleted when the extension is unloaded.

## Unloading

**Unload** asks first, then stops the extension and **deletes its JAR**. Models you made with an extension's provider stay in the list
and work again when the extension is loaded again.
