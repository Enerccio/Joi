# The `.joim` file ("Joi model")

A processed 3D model in one file: its VRM with what was decided on it. The server writes one for each model (kept as
a resource of the model, current with it) and imports one as a ready model, with no conversion. A node will cache the
bodies it renders in the same container, with the `NODE` profile. The reader and writer are
`com.github.enerccio.joi.protocol.joim.JoimFile`.

## Container

All numbers are big-endian.

| Field   | Size     | Value                                                                  |
|---------|----------|------------------------------------------------------------------------|
| magic   | 4        | `0x1DEA1A10` ("IDEAL AI 0")                                            |
| major   | 2        | the format's major version, now 1; a reader refuses a newer one        |
| minor   | 2        | the minor version, now 0; a newer minor only adds what a reader skips |
| profile | 1        | `0` FULL (the model with everything decided on it), `1` NODE           |
| sections | to the end of the file | each as below                                             |

A section:

| Field  | Size   | Value                                  |
|--------|--------|----------------------------------------|
| kind   | 4      | ASCII: `META`, `VRM ` (with a space)   |
| length | 8      | the bytes of its data                  |
| hash   | 32     | the SHA-256 of its data                |
| data   | length |                                        |

- `META`: the metadata, JSON as CBOR (RFC 8949, definite lengths; a whole number is an integer, any other a double).
  It comes first.
- `VRM `: the model, the raw bytes of a VRM (or a GLB).

A reader streams the sections, checks each hash, and skips a kind it doesn't know. The section hashes are what a node
caches by, so a new description doesn't send the VRM again.

## Metadata (FULL profile)

```json
{
  "model": {
    "name": "Ryuu",
    "notes": "…",
    "appearance": "You are …",
    "partShown": {"key:Headgear/Hide Halo": "…"},
    "partHidden": {"key:Headgear/Hide Halo": "…"},
    "shownWords": {"key:Headgear/Hide Halo": "worn"},
    "hiddenWords": {"key:Headgear/Hide Halo": "taken off"},
    "describeToggles": ["key:Headgear/Hide Halo"],
    "autoDescribe": ["mesh:Bodysuit"],
    "describeTogglesChosen": true,
    "describedHash": "<SHA-256 of the VRM the description was made from>",
    "capabilityNames": {"mesh:Scarf": "scarf"},
    "defaultState": {"mesh:Scarf": true},
    "toggleGroups": [],
    "toggleParents": {"key:Bodysuit/Hide Panties": "mesh:Bodysuit"},
    "groupSentences": {"<group id>:<state id>": "…"},
    "springChains": [{"root": "Hair Root", "preset": "HAIR", "enabled": true}],
    "appliedSpringChains": [],
    "capabilities": {"vrm": "1.0", "expressions": [], "toggles": [], "bones": []},
    "proposal": {}
  },
  "conversion": {
    "sourceFile": "Ryuu.unitypackage",
    "fbx": "Assets/Ryuu/Ryuu.fbx",
    "removeMeshes": [],
    "dropShapeKeys": [],
    "bakeShapeKeys": [],
    "maxTextureSize": 2048,
    "report": {}
  },
  "vrm": {"format": "VRM", "size": 298000000},
  "exportedAt": "2026-11-19T12:00:00Z"
}
```

`autoDescribe` lists the described toggles that are layers of her look (clothes, accessories): her description is of the
model with them off, and each one on adds its sentence.

`toggleParents` is what the user changed in the tree of toggles (a toggle's id → the id of the toggle it is part of,
`""` for none); a toggle not in it is part of the toggle of its mesh, if it is a shape key.

Every key may be missing; a reader keeps what it has for a missing one. The toggle ids (`mesh:<mesh>`,
`key:<mesh>/<shape key>`) and the bone names are the VRM's. There are no images: the snapshots and the user's reference
images only serve to make the description, and the importer makes their own.
