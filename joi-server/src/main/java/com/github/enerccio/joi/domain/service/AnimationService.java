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

    /** The clip's VRMA, null when it has none. */
    File file(Animation animation) throws Exception;
}
