---
label: Bodies and models
icon: apps
order: 720
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/bodies
  - joi-server/src/main/java/com/github/enerccio/joi/domain/animations
verified: cd7efe8
---

# Bodies and models

## Entities

`Model` is a 3D model of the user: the upload, the converted VRM, the snapshots and images, the `.joim`, all as resources, and what was decided on
it (toggle names, the description, groups, default state, spring chains, notes, the last proposal, conversion settings), stored as extended
attributes. `Body` belongs to an assistant and points to the user's models (the full one and, optionally, a lighter one for the Looking Glass). One
body of hers is active. Her bodies have different names, case aside (she switches by name): `BodyService.create` refuses a taken one, and
`BodyDialog` asks `BodyService.nameTaken` before it stores a rename, marking the field and keeping the old name.

| Class | In `domain/bodies` | What it is |
|---|---|---|
| `ModelCapabilities` | | read from the VRM's glTF JSON: expressions, toggles, humanoid bones |
| `ToggleTree` | | what is part of what; the effective state of a toggle |
| `Appearance` | | the description plus the sentence of each part off its default, put together for a state |
| `AppearanceProposal` | | what the multimodal model proposed, kept until used or discarded |
| `DescribedPart`, `ToggleGroup` | | parts described apart; groups with named states |
| `BodyState` | | a body's state of its parts (part id to state id) and of toggles under an outfit (`@<toggle id>`) |
| `Outfit` | | a preset of the model's toggles with a name and a description; a JSON list on the body |
| `SpringBones`, `SpringChain` | | the spring chains guessed from bone names and written into the VRM |
| `Blender` | | runs headless Blender, one process at a time; each run is a call of the job of the conversion (see [Jobs](jobs.md)); its output is read on a thread of its own, so the timeout holds and a hung Blender is killed and frees the lock |
| `ModelFiles`, `ConversionSource`, `ConversionReport` | | unpacking the upload, finding the avatar, what the converter reports |

## Conversion

`ModelConversionService` runs Blender with `convert_avatar.py` (in the resources of `domain/bodies`): FBX to VRM 1.0 with the VRM add-on. The upload is
unpacked into a work folder, which is deleted afterwards; from a package only the FBX, its `.meta`, the `.mat` files and the textures. The config
keys are in the script's docstring. By hand:

```bash
/Applications/Blender.app/Contents/MacOS/Blender -b --factory-startup --python convert_avatar.py -- config.json
```

Nothing is filtered on its own: the avatar FBX, the meshes, the shape keys to delete or bake and the largest texture are the user's choice.
Spring bones are not made by the converter: `SpringBones` rewrites only the VRM's JSON chunk and copies the binary. A VRM comes from the user
(an imported `.joim` too), so its node tree is not trusted: a child that would close a loop is left out of the tree. Likewise `VrmaFiles` and
`UnityMaterial` give nothing for a file they can't read instead of failing the whole import, and the FBX path from an archive must stay in the
work folder.

## Appearance

`AppearanceService` takes snapshots (the viewer renders them and sends them like uploads), proposes a description, and describes outfits. A proposal
runs in the background, one at a time, goes to the user's deep reasoning model when it takes images (else the chat model), and shows its progress.
Images larger than 1568 px are scaled down. Its prompts are templates: the appearance prompt (`SelfImageTemplateData`), the part prompt
(`PartImageTemplateData`) and the outfit prompt (`OutfitImageTemplateData`).

## The containers

A processed model is exported as a `.joim`, written on the dialog's Exit when it changed (`JoimService`; the changed check is a fingerprint,
`ModelService.exportFingerprint`). A body's animations are written into its `.joia` the same way (`JoiaService.exportBody`). The one a new file
replaces is deleted with its file at once (`ModelService.discardDerived`). `ModelService.cleanUp` frees what a converted model no longer needs. The
formats are in `joi-protocol` (see [The protocol](protocol.md)).

## Animations

`Animation` is a clip (a VRMA resource with its name, description, tags and what `VrmaFiles` read from the JSON chunk). `AnimationPack` is an
animation: steps (`AnimationStep`: clip, loop, speed, in place, reverse, rotation as `StepRotation`, blend in), a loop flag and the preview body.
FBX and BVH are converted by `AnimationConversionService` with `convert_animation.py` in the same Blender process queue. `AnimationRoles` is
the JSON on an assistant that says what plays asleep, falling asleep, waking up, idle and talking. `AnimationTools` offers `play_animation` and
sends an `animation` frame to her nodes.

## The viewer

`joi-model-viewer.js` plays clips with `@pixiv/three-vrm-animation` (retargeting to the body's rig) and `playSequence` plays steps. The node's
renderer page (`joi-node/src/main/renderer`) wraps the same file.
