package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.AnimationPack;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * The {@code .joia} of animations ({@code JoiaFile}, see {@code DESIGN.md} → Bodies, animations): the animations
 * checked in the Animations tab with the clips they play, exported into one file and imported from one. Files go
 * through the disk: clips are streamed, never held in memory.
 */
public interface JoiaService {

    /** The file's name for the animations: the animation's name for one, else {@code animations.joia}. */
    String fileName(List<AnimationPack> animations);

    /**
     * Writes the user's animations and the clips they play into the stream (not closed). A step whose clip is gone is
     * left out.
     */
    void export(List<AnimationPack> animations, OutputStream out) throws Exception;

    /**
     * What an import added.
     *
     * @param animations  the new animations
     * @param clipsAdded  the clips added to the library
     * @param clipsLinked the clips the user already had (by uuid), linked instead
     * @param clipsFailed the clips that were no VRMA, left out with their steps
     */
    record Imported(List<AnimationPack> animations, int clipsAdded, int clipsLinked, int clipsFailed) {
    }

    /**
     * Imports an uploaded {@code .joia}: its clips into the library (a clip of the same uuid the user has is linked,
     * not added again), its animations as new ones. The file is deleted.
     *
     * @param joia the uploaded file, in the model service's upload folder
     * @throws ForeignClipException when a clip's uuid is another user's clip (nothing is imported)
     * @throws IOException          when it is not a {@code .joia}, is of a newer format or is damaged
     */
    Imported importFile(File joia) throws Exception;

    /** A {@code .joia} holds a clip of another user's uuid: it is refused as a whole. */
    class ForeignClipException extends IOException {

        public ForeignClipException(String clipName) {
            super("a clip of the file has the uuid of another user's clip: " + clipName);
        }
    }
}
