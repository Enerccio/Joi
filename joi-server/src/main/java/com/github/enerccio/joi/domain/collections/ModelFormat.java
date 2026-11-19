package com.github.enerccio.joi.domain.collections;

import java.util.Locale;

/**
 * The file format of a {@code Model}, of its main file when it came in a zip. Stored by name, so the names never
 * change.
 */
public enum ModelFormat {

    /** VRM 0.x or 1.0: a glTF binary with the humanoid rig, expressions and MToon materials, all in one file. */
    VRM("vrm", "model/gltf-binary"),
    /** A glTF binary, everything in one file. */
    GLB("glb", "model/gltf-binary"),
    /** A glTF JSON; its buffers and textures are separate files, unless they are embedded. */
    GLTF("gltf", "model/gltf+json"),
    /** An Autodesk FBX (what VRChat packages ship); its textures are separate files. */
    FBX("fbx", "application/octet-stream"),
    /** A Joi model ({@code .joim}): a processed model, imported with no conversion (its VRM is the converted one). */
    JOIM("joim", "application/x-joi-model");

    private final String extension;
    private final String contentType;

    ModelFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** The format of a file by its extension, null when it is none of them. */
    public static ModelFormat ofFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (ModelFormat format : values()) {
            if (lower.endsWith("." + format.extension)) {
                return format;
            }
        }
        return null;
    }
}
