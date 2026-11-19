package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Animation;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Converts the animation of an FBX (Mixamo and the like) or a BVH into VRM Animation clips of the user's library, with Blender and
 * the VRM add-on, on the server ({@code convert_animation.py}): the bones are mapped to VRM's humanoid and each take of
 * the file becomes a clip. The upload is deleted once converted; the clips keep its name ({@link Animation#getSource()}).
 * Blender runs one at a time with the conversions of models.
 */
public interface AnimationConversionService {

    /** What a conversion tells the one who started it, from the thread of the conversion. */
    interface Listener {

        /**
         * The conversion ended.
         *
         * @param added the clips added to the library (empty when it failed)
         * @param error why it failed, null when it did not
         */
        void finished(List<Animation> added, String error);
    }

    /** Whether a file of that name is converted (an FBX or a BVH) rather than taken as it is. */
    static boolean isConvertible(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".fbx") || name.endsWith(".bvh");
    }

    /**
     * Starts converting an uploaded FBX or BVH into clips of the current user's library.
     *
     * @param upload the file, in the user's folder of resources ({@code ModelService.uploadFolder()}); deleted at the end
     * @throws IllegalArgumentException when the file is not an FBX or a BVH, or not an upload
     */
    void convert(File upload, String fileName, Listener listener) throws Exception;
}
