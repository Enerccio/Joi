package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.animations.AnimationFace;
import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * An animation of the user (called an animation in the UI; see {@code DESIGN.md} → Bodies, animations): what she plays,
 * made of steps, each a clip of the library ({@link Animation}) and how it plays there ({@link AnimationStep}: whether it
 * loops, its speed, in place). "Jumping jacks" is the start once, the jumps looping, the stop once. The animation as a
 * whole can loop too (a wave and a nod, again and again). A clip can be in several animations and several steps;
 * deleting an animation keeps its clips. The clips play on any body (every body has a humanoid rig); the animation
 * remembers which body the user previews it on. It has no columns of its own: everything is an extended attribute.
 */
@Entity
@Table(name = "animation_packs")
public class AnimationPack extends ExtendableEntity {

    @ExtendedAttribute
    @Transient
    private String name;

    /** What the pack is for, in the user's words. */
    @ExtendedAttribute
    @Transient
    private String description;

    /** Its steps (JSON: {@link AnimationStep}s), in order. */
    @ExtendedAttribute
    @Transient
    private String steps;

    /** Whether it starts again after its last step until it is finished; null for no. */
    @ExtendedAttribute
    @Transient
    private Boolean loop;

    /** How her eyes are while it plays (one of {@code AnimationFace.EYES}); null for her own. */
    @ExtendedAttribute
    @Transient
    private String eyes;

    /** The expression it shows (one of {@code AnimationFace.EXPRESSIONS}); null for her mood's. */
    @ExtendedAttribute
    @Transient
    private String expression;

    /** The body the clips are previewed on, null for none chosen yet. */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = Body.class)
    private Long previewBodyId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }

    /** Its steps, in order. */
    public List<AnimationStep> steps() {
        return AnimationStep.listFromJson(steps);
    }

    public void setStepList(List<AnimationStep> steps) {
        this.steps = AnimationStep.toJson(steps);
    }

    /** The uuids of its clips, in the order of the steps (a clip in several steps is there several times). */
    public List<String> clipUuids() {
        List<String> uuids = new ArrayList<>();
        for (AnimationStep step : steps()) {
            uuids.add(step.clip());
        }
        return uuids;
    }

    public Boolean getLoop() {
        return loop;
    }

    public void setLoop(Boolean loop) {
        this.loop = loop;
    }

    /** Whether it starts again after its last step. */
    public boolean loops() {
        return Boolean.TRUE.equals(loop);
    }

    public String getEyes() {
        return eyes;
    }

    public void setEyes(String eyes) {
        this.eyes = AnimationFace.eyes(eyes);
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = AnimationFace.expression(expression);
    }

    public Long getPreviewBodyId() {
        return previewBodyId;
    }

    public void setPreviewBodyId(Long previewBodyId) {
        this.previewBodyId = previewBodyId;
    }
}
