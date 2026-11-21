---
label: Spring bones
icon: pulse
order: 750
---

# Spring bones

Spring bones are the bones that swing as she moves: hair, a tail, ears, cloth. Joi writes them into the converted VRM; Blender
does not make them.

They are set in the **Spring bones** panel of the model's dialog. Only a converted VRM (1.0) gets them; an uploaded VRM keeps its
own.

## What is guessed

At the first conversion Joi guesses the chains from the names of the bones. A bone named hair, bang, twin or braid, tail, ears, or
scarf, ribbon, skirt, cape or strap starts a chain. Body parts and the face are never guessed nor offered. A bone named *root* or
*base* only holds others: its children start the springs. Each straight run of bones is one spring; a single bone has no tip to
swing to and is left out.

Each chain has a preset for how it moves: **Hair**, **Tail**, **Ears**, **Cloth** or **Stiff** (stiffness, gravity, drag and hit
radius).

Colliders are put on her head, neck, chest, spine, hips, upper legs and upper arms, sized by the height of her hips, so cloth and
hair do not go through her.

## The panel

- A box switches a chain off.
- **Hair**, **Tail**... chooses how the chain moves.
- The chain's bones are listed (with their count) and a chain can be removed.
- **Start a chain from a bone** starts one from another bone; a chain inside another takes that part of it.
- **Guess again** guesses from the names again.
- **Apply to the model** writes a new VRM in place of the old one. It says **Changed, not applied yet** until you do. The look does
  not change, so snapshots and a description that were current stay current.
- **Sway her** turns the preview from side to side so you can see them move.

The chains are stored on the model by the bones' names, so a new conversion keeps yours.
