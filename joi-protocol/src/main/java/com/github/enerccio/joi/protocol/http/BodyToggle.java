package com.github.enerccio.joi.protocol.http;

/**
 * A toggle of a body's model (a mesh, or a "Hide X" shape key of a mesh) and whether it is shown (since 1.5), as the
 * renderer sets it.
 *
 * @param mesh  the mesh's name in the model
 * @param key   the shape key, null for the mesh itself
 * @param hides whether the key at full weight hides the part (it is shown at 0); false for a mesh
 * @param shown whether the part is shown
 */
public record BodyToggle(String mesh, String key, boolean hides, boolean shown) {
}
