package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.repository.AnimationPackRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/** The animation packs of the user (see {@code DESIGN.md} → Bodies, animations). */
public interface AnimationPackService extends ExtendableService<AnimationPack, AnimationPackRepository> {

    /** The user's packs, by name. */
    List<AnimationPack> findAllByName() throws Exception;

    /** Deletes the pack and its clips (their files with them). */
    void deleteWithClips(AnimationPack pack) throws Exception;

    /**
     * The bodies of the user's assistants a pack can be previewed on (those with a model to show), by her name and then
     * the body's.
     */
    List<Body> previewBodies() throws Exception;

    /** The body the pack is previewed on, null when none was chosen or it is gone. */
    Body previewBody(AnimationPack pack) throws Exception;
}
