package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.repository.AnimationRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.io.File;
import java.util.List;

/** The animation clips of the user's packs: VRMA files (see {@code DESIGN.md} → Bodies, animations). */
public interface AnimationService extends ExtendableService<Animation, AnimationRepository> {

    /** The clips of the pack, oldest first. */
    List<Animation> findByPack(AnimationPack pack) throws Exception;

    /** How many clips the pack has. */
    long countByPack(AnimationPack pack) throws Exception;

    /**
     * Adds an uploaded VRMA to the pack as a new clip (named after the file), a resource of the user. The file must be in
     * the user's folder of resources ({@code ModelService.uploadFolder()}); it is deleted when it is no VRMA.
     *
     * @throws IllegalArgumentException when the file is no VRMA
     */
    Animation add(AnimationPack pack, File file, String fileName) throws Exception;

    /** The clip's VRMA, null when it has none. */
    File file(Animation animation) throws Exception;
}
