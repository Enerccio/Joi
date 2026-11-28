---
label: Bodies and models
icon: apps
order: 720
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/bodies
  - joi-server/src/main/java/com/github/enerccio/joi/domain/animations
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/BodyServiceImpl.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/ProfileServiceImpl.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/impl/StanceServiceImpl.java
  - joi-server/src/main/java/com/github/enerccio/joi/domain/service/StanceService.java
verified: 7664595
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
| `ModelCapabilities` | | read from the VRM's glTF JSON: expressions (the ones she can show with `set_expression` are `BodyService.expressions`: the mood presets but neutral and the model's own), toggles, humanoid bones |
| `ToggleTree` | | what is part of what; the effective state of a toggle |
| `Appearance` | | the description plus the sentence of each part off its default, put together for a state |
| `AppearanceProposal` | | what the multimodal model proposed, kept until used or discarded |
| `DescribedPart`, `ToggleGroup` | | parts described apart; groups with named states; a toggle's states are named by `Model.shownWords` / `hiddenWords` ("worn", "taken off"), which is what her prompt, events and `change_body_part` say |
| `BodyState` | | a body's state of its parts (part id to state id) and of toggles under an outfit (`@<toggle id>`) |
| `Outfit` | | a preset of the model's toggles with a name and a description; a JSON list on the body |
| `SpringBones`, `SpringChain` | | the spring chains guessed from bone names and written into the VRM |
| `Blender` | | runs headless Blender, one process at a time; each run is a call of the job of the conversion (see [Jobs](jobs.md)); its output is read on a thread of its own, so the timeout holds and a hung Blender is killed and frees the lock |
| `ModelFiles`, `ConversionSource`, `ConversionReport` | | unpacking the upload, finding the avatar, what the converter reports |

## Profiles

A profile (home, work) is the state of an assistant's bodies in one place: the body she is in and the state of its parts and outfit
(`domain/bodies/Profiles`, kept by `ProfileService` as one JSON extended attribute of the `Joi`, `Joi.profiles`, no migration). The
**default profile** is not stored there: it is what the bodies keep themselves (`Body.active`, `Body.partStates`), so an assistant
without profiles works as before and the existing state is the default profile. Another profile keeps its active body's uuid and, for each
body, its part states (a new one starts as a copy of the default's). `Profiles` also has the nodes' assignment (node id → profile),
the names of the nodes that connected (`seen`, at hello, so a node can be assigned while it is away) and the profile of her last turn
(`last`).

`BodyService`'s calls that read or change the state take the profile (`null` or `"default"` for the default one): `active(joi, profile)`,
`partStates`, `switchPart`, `changePart`, `wear`, `wearOutfit`, `activate`, `switchBody`, `appearance`, `toggles`, `currentOutfit`,
`bodyInfos`, `prompt`. The calls without it are the default profile (they are overrides with their own transaction, not default methods: a
default method called through the proxy would run its inner call outside the transaction). A change calls `bodyChanged(joi, profile)`, which
sends the `event` `body` through `NodeService.notify(joi, event, profile)` to the nodes in that profile only (a change of the default
profile's active body, a deleted body or a model's change goes to all).

Which profile a turn is in: a node's turn gets `TurnInput.profile` from `NodeService.profileOf(session)` (its `hello.nodeId`);
`ChatServiceImpl.chat` fills a turn that comes from no node with the profile of the last one. `PrepareStep` gives
`BodyService.prompt(joi, profile, last)`, where `last` is the profile of her previous turn, and remembers the new one; the body prompt's
`{{profile}}` and `{{profileFrom}}` (only when there is more than one profile) are how she is told where she is and that she moved.
Her functions (`BodyTools`, `AnimationTools`) take the profile from the turn's input. Events about her body (`body.part_switched`,
`body.outfit_changed`, `body.switched`) are queued only when the change is in the profile she was in last; a change elsewhere is
told when she moves there, by the note in the prompt.

A turn the server starts (`NodeService.tell`: a task that ended, a summary) goes to the node with the most recent user activity
(`NodeSession.lastActive`: a `say`, a `part` or a `display`), among the ones that speak, so the answer is voiced there and she is in
that node's profile.

The API's `GET /api/v1/jois/{id}/body` takes `?node=<nodeId>` (protocol 1.12) and answers the body of that node's profile; the Java node and the iOS
client send their stable id. The UI is the Profiles tab (`ui/dialogs/joi/JoiProfilesPart`) and the Profile choice at the top of the Bodies
tab (`JoiBodiesPart`), which passes the profile to `BodyDialog` and `OutfitDialog`.

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
animation: steps (`AnimationStep`: clip, loop, speed, in place, reverse, rotation as `StepRotation`, translation as `StepTranslation`, blend in), a
loop flag and the preview body. The translation is an offset `[x, y, z]` in metres in the model's space, held within ±10 m per axis and null
below a tenth of a millimetre; the viewer adds it to the hips after the rotation.

Clips and animations are in folders (`AnimationFolder`, of the kind `CLIPS` or `ANIMATIONS`), as Marginalia's book folders: nested, the
names of one parent unique case insensitive, without a slash. They are a list in the user setting (`JoiUserSetting.animationFolders`, an
extended attribute, no table) and an item names its folder by uuid (`Animation.folder`, `AnimationPack.folder`, extended attributes); an
unknown uuid is the top level. `AnimationFolders` is the tree (children, ancestors, paths, the label `Idle / Standing / Breathe` and the
order by it); `AnimationFolderService` changes it: create, rename, move (not into itself or below), delete with the content moved up
(refused when a folder in it has the name of one above) or with all of it, move items, `clipsByPath` / `animationsByPath` for the
choices.
FBX and BVH are converted by `AnimationConversionService` with `convert_animation.py` in the same Blender process queue. `AnimationRoles` is
the JSON on an assistant that says what plays asleep, falling asleep, waking up, idle and talking. `AnimationTools` offers `play_animation` and
sends an `animation` frame to her nodes.

**Stances.** `AnimationTools` also offers `set_stance` (an animation by name, the same list) and `end_stance`: an animation she holds,
looped in place of idle and talking until she ends it or takes another (dancing, sitting). `StanceService` (`StanceServiceImpl`) keeps it
on her (`Joi.stance`, the uuid, an extended attribute), notes it in the conversation (`stance.taken`, `stance.left`), and tells her nodes
(`NodeService.stance`, the `stance` frame); when the user ends it (`end(joi, true)`, the End button of `JoiAnimationsPart`) she gets the
event `stance.ended` (`JoiDefaults.eventStanceEnded`). `BodyServiceImpl.prompt` sets `BodyTemplateData.stance` (with `stanceSince` and
`stanceReason`) when the active body plays that animation, `animated` when it plays any, and `recentStances` from her ledger
(`StanceLog`: `Joi.stanceLog`, the last five, each the uuid, from, to, `why` and `endedWhy`, said by `describe` with fixed times); the
default body prompt says what she holds or that she holds none, then the ledger. `set_stance` and `end_stance` take a required `reason`
(`StanceLog.oneLine`, at most 200 characters); `StanceServiceImpl.save` moves the stance she held into the ledger. Both functions are in `ToolEffects.NO_ANSWER`. On a node,
`AvatarDirector.stance` (Java and Swift) plays it looped while she is idle or talking: entering talking or idle in a stance keeps it
playing (`enterHeld`), a gesture is ignored (`gesture` returns early), asleep and waking up win over it. The server refuses
`play_animation` too while she holds a stance her body plays (`AnimationTools.heldStance`), and tells her to change or end it. The JavaFX node and the iOS app pass null for an
animation the body shown lacks (`AvatarPane.has`, `AnimationPlayer.has`) and check again when a body is shown (`RendererBridge.onShown`,
`ChatModel.bodyShown`).

## The viewer

`joi-model-viewer.js` plays clips with `@pixiv/three-vrm-animation` (retargeting to the body's rig) and `playSequence` plays steps. A
step's clip is prepared from a copy of the clip as made: reversed, turned (`_rotateClip`: the hips' rotation and their path around
where they start), moved (`_translateClip`: the offset added to the hips' path; a clip with no hips track gets one key at the rest
place, `normalizedRestPose`), held in place. `setRotationTool` and `setTranslationTool` put `TransformControls` (rings or arrows) on a
handle at the hips; the step is made again as it is dragged (`_remakeStep`) and `step-rotated` / `step-translated` tell the dialog
when the mouse lets go. The node's
renderer page (`joi-node/src/main/renderer`) wraps the same file. An animation can force her face while it plays (`AnimationPack.eyes` and
`expression`, in the `.joia` as `eyes` and `expression`, set in `AnimationPackDialog`): see *Her face* in [Writing a node](nodes.md).
