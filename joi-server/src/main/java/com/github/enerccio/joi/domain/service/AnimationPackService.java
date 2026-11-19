package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.repository.AnimationPackRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.Collection;
import java.util.List;

/**
 * The animations of the user ({@link AnimationPack}, see {@code DESIGN.md} → Bodies, animations): steps, each a clip of
 * their library and how it plays there. A clip can be in several animations and steps; deleting an animation keeps its
 * clips.
 */
public interface AnimationPackService extends ExtendableService<AnimationPack, AnimationPackRepository> {

    /** The user's packs, by name. */
    List<AnimationPack> findAllByName() throws Exception;

    /**
     * A step of an animation with its clip.
     *
     * @param index where it is in the animation's steps, from 0
     */
    record Step(int index, AnimationStep step, Animation clip) {
    }

    /** The steps of the animation with their clips, in order (steps of clips deleted since are left out). */
    List<Step> steps(AnimationPack pack) throws Exception;

    /** The clips of the animation, in the order of its steps (a clip in several steps is there several times). */
    List<Animation> clips(AnimationPack pack) throws Exception;

    /**
     * Adds a step per clip to the end of the animation, set as the clip was ({@link AnimationStep#of}); the
     * description of each clip that was not in it yet is appended to the animation's, on a line of its own. Returns
     * the saved animation.
     */
    AnimationPack addClips(AnimationPack pack, Collection<Animation> clips) throws Exception;

    /** A new animation of one clip: named after it, with its description; returns the saved animation. */
    AnimationPack createFrom(Animation clip) throws Exception;

    /** Replaces the step at the index; returns the saved animation. */
    AnimationPack updateStep(AnimationPack pack, int index, AnimationStep step) throws Exception;

    /** Moves the step at the index by delta places (-1 up, 1 down), when it can; returns the saved animation. */
    AnimationPack moveStep(AnimationPack pack, int index, int delta) throws Exception;

    /** Takes the step at the index out; returns the saved animation. */
    AnimationPack removeStep(AnimationPack pack, int index) throws Exception;

    /** Takes every step of the clip out of the animation (it stays in the library); returns the saved animation. */
    AnimationPack removeClip(AnimationPack pack, Animation clip) throws Exception;

    /** The user's animations the clip is in, by name. */
    List<AnimationPack> packsWith(Animation clip) throws Exception;

    /**
     * The bodies of the user's assistants clips can be previewed on (those with a model to show), by her name and then
     * the body's.
     */
    List<Body> previewBodies() throws Exception;

    /** The body the pack is previewed on, null when none was chosen or it is gone. */
    Body previewBody(AnimationPack pack) throws Exception;
}
