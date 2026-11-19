package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.bodies.BodyAnimations;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.Date;
import java.util.List;

/**
 * A body of an assistant (see {@code DESIGN.md} → Bodies): what she looks like on a display. It is made of the user's
 * 3D {@link Model models}, the full one and, optionally, a lighter one for the Looking Glass, and has a name. What it
 * can do (expressions, toggles) and how she looks in it (the description) are the model's.
 * <p>
 * One body of an assistant is {@link #isActive() active}: the one she is in. Switching is an event for her.
 * <p>
 * It has the user's animations it plays (all of them by default) and their {@code .joia}, written when they changed.
 */
@Entity
@Table(name = "bodies")
public class Body extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    /** The full model, null until one is chosen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    @CleanupReference(Policy.WEAK)
    private Model model;

    /** The lighter model for the Looking Glass, null for the full one there too. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lkg_model_id")
    @CleanupReference(Policy.WEAK)
    private Model lookingGlassModel;

    /** Whether she is in this body now (one of hers at most). */
    @Column(nullable = false)
    private boolean active;

    @ExtendedAttribute
    @Transient
    private String name;

    /** The state of its model's parts that were switched, JSON ({@code BodyState}); null for all by default. */
    @ExtendedAttribute
    @Transient
    private String partStates;

    /** The uuids of the animations it plays, JSON ({@code BodyAnimations}); null for all of the user's. */
    @ExtendedAttribute
    @Transient
    private String animations;

    /** The {@code .joia} of its animations (see {@code JoiaFile}), a resource of the user; null until one was written. */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.OWNS, target = Resource.class)
    private Long animationsResourceId;

    /** What the {@code .joia} holds, to tell whether it is current. */
    @ExtendedAttribute
    @Transient
    private String animationsFingerprint;

    @ExtendedAttribute
    @Transient
    private Date animationsExportedAt;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Model getLookingGlassModel() {
        return lookingGlassModel;
    }

    public void setLookingGlassModel(Model lookingGlassModel) {
        this.lookingGlassModel = lookingGlassModel;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPartStates() {
        return partStates;
    }

    public void setPartStates(String partStates) {
        this.partStates = partStates;
    }

    /** The part's id → the id of the state it was switched to ({@code BodyState}). */
    public java.util.Map<String, String> partStateMap() {
        return com.github.enerccio.joi.domain.bodies.BodyState.fromJson(partStates);
    }

    public void setPartStateMap(java.util.Map<String, String> states) {
        this.partStates = com.github.enerccio.joi.domain.bodies.BodyState.toJson(states);
    }

    public String getAnimations() {
        return animations;
    }

    public void setAnimations(String animations) {
        this.animations = animations;
    }

    /** The uuids of the animations it plays, null for all of the user's ({@code BodyAnimations}). */
    public List<String> animationUuids() {
        return BodyAnimations.fromJson(animations);
    }

    public void setAnimationUuids(List<String> uuids) {
        this.animations = BodyAnimations.toJson(uuids);
    }

    public Long getAnimationsResourceId() {
        return animationsResourceId;
    }

    public void setAnimationsResourceId(Long animationsResourceId) {
        this.animationsResourceId = animationsResourceId;
    }

    public String getAnimationsFingerprint() {
        return animationsFingerprint;
    }

    public void setAnimationsFingerprint(String animationsFingerprint) {
        this.animationsFingerprint = animationsFingerprint;
    }

    public Date getAnimationsExportedAt() {
        return animationsExportedAt;
    }

    public void setAnimationsExportedAt(Date animationsExportedAt) {
        this.animationsExportedAt = animationsExportedAt;
    }
}
