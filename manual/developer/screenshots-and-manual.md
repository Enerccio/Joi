---
label: Screenshots and the manual
icon: image
order: 680
covers:
  - screenshots/SCREENSHOTS.md
  - tools/retype_build.sh
  - tools/check_dev_docs.py
  - joi-node/src/main/java/com/github/enerccio/joi/node/fx/NodeShots.java
  - joi-node/src/main/java/com/github/enerccio/joi/node/fx/ShotCommands.java
verified: a86e400
---

# Screenshots and the manual

## The manual

The manual is Retype: `retype.yml`, sources in `manual/` (`user/` and `developer/`), pictures in `manual/images/`. `tools/retype_build.sh` builds it into
`docs/`, which is not in Git. The `Manual` workflow (`.github/workflows/manual.yml`) publishes it from `master`. It is English only.

The pages of this guide carry `covers` (the code they describe) and `verified` (the commit they were checked against). `python3 tools/check_dev_docs.py`
lists the pages whose code changed since; `--stamp` marks pages as verified at HEAD, in a commit of their own after the review. A page is only as good as its `covers`.

When you implement a feature or a fix, update the docs that describe it: the user guide in `manual/user`, this guide here, and the flows of the pictures
(`screenshots/flows`) when a screen changed.

## The pictures

`screenshots/` is a scripted environment that takes every picture of the manual from the same dataset. Its full description is `screenshots/SCREENSHOTS.md`. In short:

- its own server on port 8099 with its own data folder (`screenshots/.data/joi`), never `~/.joi`;
- a fake inference server on port 8098 that answers by rules (`llm/rules.mjs`), so there is no model, key or network;
- headless Google Chrome driven by Playwright for the server's UI;
- the JavaFX node in a screenshot mode, driven by commands on port 8097, with its own settings folder, a temporary one (`$TMPDIR/joi-shots-client`, `SHOT_NODE_HOME`), never `~/.joi-client` or `~/.joi-dev-client`;
- step snapshots of the data folder, so a picture is taken from a named state.

```bash
cd screenshots && npm install                    # once
bin/rebuild.sh                                   # the whole setup chain from the empty installation
bin/shoot-all.sh                                 # all pictures that the chain does not take itself
bin/publish.sh --dry ; bin/publish.sh            # compare / copy out/*.png to manual/images/
bin/shoot-some.sh 01-admin shoot-workspace.mjs   # restore a state and run only those flows
```

The chain (`bin/chain.txt`) lists each step: a flow and the snapshot taken after it. It ends with the clips and animations made from `tmp/animations`
(`08-animations`) and a conversation with a memory and a task that waits (`09-conversation`, which needs the fake model). Flows that only look
(`shoot-settings`, `shoot-joi`, `shoot-moods`, `shoot-model`) run on any late step, `node-chat` on `09-conversation`, and `bin/crop-node.sh` cuts two pictures out of the node's windows. Rules: one state per picture; never use real data; after a flow fails
restore the step; coordinates are for 1920x932 and a control's text beats a coordinate; look at the pictures with your own eyes before publishing.

## The node's screenshot mode

`Launcher shots <out dir> <port>` starts the normal app and a small command server on the loopback (`fx/NodeShots`, `fx/ShotCommands`). A script presses buttons of the
scene, types into its fields and writes the window as a PNG with `Node.snapshot`, so it needs no screen recording permission; the Chromium pane that shows her body is part
of the picture. OpenJFX has no headless mode, so the window opens. `lib/node.mjs` has the commands (`click`, `type`, `combo`, `wait`, `texts`, the menus, `snap`, `snapDialog`,
`quit`). A command is a POST with `Content-Type: application/json` (a page in a browser can't send that without asking), and a picture's name is
letters, digits, `.`, `_` and `-` only, so it stays in the out folder. Of the Looking Glass only its controls are taken, never the display.

## Rules

The model in the pictures is Saiki; the credits page names it. Never use the purchased test avatars. Run one heavy thing at a time: the server, Chrome, a Maven build and
the node together are too much for the machine.
