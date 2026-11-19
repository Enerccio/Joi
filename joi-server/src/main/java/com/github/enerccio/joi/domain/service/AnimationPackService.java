package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.repository.AnimationPackRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Collection;
import java.util.List;

/**
 * The animation packs of the user (see {@code DESIGN.md} → Bodies, animations): sets of clips of their library. A clip
 * can be in several packs; deleting a pack keeps its clips.
 */
public interface AnimationPackService extends ExtendableService<AnimationPack, AnimationPackRepository> {

    /** The user's packs, by name. */
    List<AnimationPack> findAllByName() throws Exception;

    /** The clips of the pack, in its order (those deleted since are left out). */
    List<Animation> clips(AnimationPack pack) throws Exception;

    /** Adds clips to the end of the pack (those in it already stay where they are); returns the saved pack. */
    AnimationPack addClips(AnimationPack pack, Collection<Animation> clips) throws Exception;

    /** Takes a clip out of the pack (it stays in the library); returns the saved pack. */
    AnimationPack removeClip(AnimationPack pack, Animation clip) throws Exception;

    /** The user's packs the clip is in, by name. */
    List<AnimationPack> packsWith(Animation clip) throws Exception;

    /**
     * The bodies of the user's assistants clips can be previewed on (those with a model to show), by her name and then
     * the body's.
     */
    List<Body> previewBodies() throws Exception;

    /** The body the pack is previewed on, null when none was chosen or it is gone. */
    Body previewBody(AnimationPack pack) throws Exception;
}
