package com.github.enerccio.joi.protocol.http;

/**
 * A toggle of a body's model (a mesh, or a "Hide X" shape key of a mesh) and whether it is shown (since 1.5), as the
 * renderer sets it.
 *
 * @param mesh  the mesh's name in the model
 * @param key   the shape key, null for the mesh itself
 * @param hides whether the key at full weight hides the part (it is shown at 0); false for a mesh
 * @param shown whether the part is shown: its own state, it is seen only while the toggle it is part of is shown
 * @param parent the id of the toggle this one is part of, {@code mesh:<mesh>} or {@code key:<mesh>/<key>} (as in
 *               {@code BodyPart.id}), null for none. A shape key toggle is part of the toggle of its mesh unless the user
 *               said otherwise: the bodysuit hidden hides its panties, whatever state they are in, and shows them as
 *               they were when it is shown again. The renderer applies it; the server keeps each toggle's own state.
 */
public record BodyToggle(String mesh, String key, boolean hides, boolean shown, String parent) {

    public BodyToggle(String mesh, String key, boolean hides, boolean shown) {
        this(mesh, key, hides, shown, null);
    }
}
