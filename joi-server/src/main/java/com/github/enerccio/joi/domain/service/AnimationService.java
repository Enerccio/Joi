package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.repository.AnimationRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.io.File;
import java.util.List;

/**
 * The user's library of animation clips: VRMA files (see {@code DESIGN.md} → Bodies, animations). Deleting a clip
 * takes it out of the packs it is in.
 */
public interface AnimationService extends ExtendableService<Animation, AnimationRepository> {

    /** The user's clips, by name. */
    List<Animation> findAllByName() throws Exception;

    /**
     * Adds an uploaded VRMA to the library as a new clip (named after the file), a resource of the user. The file must be
     * in the user's folder of resources ({@code ModelService.uploadFolder()}); it is deleted when it is no VRMA.
     *
     * @throws IllegalArgumentException when the file is no VRMA
     */
    Animation add(File file, String fileName) throws Exception;

    /**
     * As {@link #add(File, String)}, with the uuid the clip keeps (an imported clip keeps its own), null for a new one.
     *
     * @throws IllegalArgumentException when the file is no VRMA, or the uuid is taken
     */
    Animation add(File file, String fileName, String uuid) throws Exception;

    /** Whose a clip uuid is, if anyone's. */
    enum UuidUse {
        /** No clip has it. */
        FREE,
        /** A clip of the user's library. */
        MINE,
        /** A clip the user deleted. */
        MINE_DELETED,
        /** A clip of another user. */
        OTHER_USER
    }

    /** Whose clip has the uuid, if anyone's. */
    UuidUse uuidUse(String uuid) throws Exception;

    /** The clip's VRMA, null when it has none. */
    File file(Animation animation) throws Exception;
}
