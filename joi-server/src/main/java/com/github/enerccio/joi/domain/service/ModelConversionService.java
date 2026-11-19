package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Model;

/**
 * Converts the FBX of a {@link Model} (alone, in a zip or in a Unity package) to a VRM 1.0 with Blender and the VRM
 * add-on, on the server: the uploaded file is unpacked into a work folder (only the FBX, its {@code .meta}, the
 * materials and the textures), the Unity materials are read and matched to the FBX's, the converter script runs, and
 * the VRM becomes the model's converted file. One conversion runs at a time (it is heavy); the others wait.
 * <p>
 * Blender is {@code joi.blender} ({@code JOI_BLENDER}), else Blender.app on macOS, else {@code blender} on the PATH. It
 * needs the "VRM format" add-on installed (extensions.blender.org).
 */
public interface ModelConversionService {

    /** What a conversion tells the one who watches it. Called from the thread of the conversion. */
    interface Listener {

        /** A line of the converter's output. */
        void line(String line);

        /** The conversion ended; the model as stored then (status, report, error). */
        void finished(Model model);
    }

    /**
     * Starts converting the model of the current user, with its settings (the FBX, the meshes left out, the texture
     * size). Its status becomes RUNNING at once.
     *
     * @param listener told of the progress, may be null
     * @throws IllegalStateException when the model has no FBX, or it is converting already
     */
    void convert(Model model, Listener listener) throws Exception;

    /** Whether the model is converting (or waiting for its turn). */
    boolean isRunning(Model model);

    /** Also tells this listener of the model's conversion, while it runs; nothing happens when it does not. */
    Runnable listen(Model model, Listener listener);
}
