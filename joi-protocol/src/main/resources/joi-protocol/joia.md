# The `.joia` file ("Joi animations")

Animations with the clips they play, in one file: to share them or move them to another server, and to give a node
the animations of a body. The server exports the animations checked in the Animations tab, and imports a `.joia`
there. Each body keeps a `.joia` of the animations it plays, written when the user exits the body's dialog; a node
downloads it with `GET /api/v1/bodies/{id}/joia` (see `openapi.yaml`), cached by its SHA-256. The reader and writer are
`com.github.enerccio.joi.protocol.joia.JoiaFile`, the metadata `JoiaMetadata`.

## Container

All numbers are big-endian. The sections are those of a [`.joim`](joim.md).

| Field    | Size                   | Value                                                                  |
|----------|------------------------|------------------------------------------------------------------------|
| magic    | 4                      | `0x1DEA1AC7` ("IDEAL ACT")                                            |
| major    | 2                      | the format's major version, now 1; a reader refuses a newer one        |
| minor    | 2                      | the minor version, now 0; a newer minor only adds what a reader skips |
| sections | to the end of the file | each as below                                                          |

A section:

| Field  | Size   | Value                   |
|--------|--------|-------------------------|
| kind   | 4      | ASCII: `META`, `VRMA`   |
| length | 8      | the bytes of its data   |
| hash   | 32     | the SHA-256 of its data |
| data   | length |                         |

- `META`: the metadata, JSON as CBOR (as in a `.joim`). It comes first, once.
- `VRMA`: a clip, the raw bytes of a VRMA (VRM Animation). One per clip of the metadata, in its order; its hash is the
  clip's `sha256`.

A reader streams the sections, checks each hash, and skips a kind it doesn't know. It refuses a file with more or fewer
`VRMA` sections than clips.

## Metadata

```json
{
  "animations": [
    {
      "uuid": "…",
      "name": "Jumping jacks",
      "description": "You do jumping jacks.",
      "loop": false,
      "steps": [
        {"clip": "<clip uuid>", "loop": false, "speed": 1, "inPlace": true},
        {"clip": "<clip uuid>", "loop": true, "speed": 1.2, "inPlace": true}
      ]
    }
  ],
  "clips": [
    {
      "uuid": "…",
      "name": "Jumping jacks start",
      "description": "…",
      "tags": ["exercise"],
      "loop": false,
      "inPlace": true,
      "speed": 1,
      "fileName": "jumping-jacks-start.vrma",
      "source": "Jumping Jacks.fbx",
      "take": "mixamo.com",
      "duration": 1.4,
      "bones": ["hips", "spine"],
      "expressions": false,
      "size": 120000,
      "sha256": "…"
    }
  ],
  "exportedAt": "2026-11-19T12:00:00Z"
}
```

Every key may be missing, except a clip's `uuid`: no two clips have the same one. A step names its clip by its uuid;
a clip is in the file once, however many steps play it. A clip's `loop`, `inPlace` and `speed` are only what a new step
of it starts from.

## Import

- **A clip** whose uuid the user already has (a clip of their library) is linked: its steps play that clip, and the
  file's copy is skipped, not read.
- **Any other clip** is added to the library as a new clip, keeping its uuid, so importing the file again links it.
  When the uuid is one of the user's deleted clips, the new clip gets a new uuid.
- **A uuid of another user's clip** refuses the whole file before anything is written: a file can't reach another
  user's clips.
- **Animations** are always added as new ones (new uuids), their steps pointing at the linked or added clips. A step
  whose clip could not be added (not a VRMA) is left out.
