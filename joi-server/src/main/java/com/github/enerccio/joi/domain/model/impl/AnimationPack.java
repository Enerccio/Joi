package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A set of animation clips of a user ({@link Animation}), see {@code DESIGN.md} → Bodies, animations: "my idles",
 * "greetings". The clips play on any body (every body has a humanoid rig); the pack remembers which body the user
 * previews them on. It has no columns of its own: the name, the description and the preview body are extended
 * attributes.
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

    public Long getPreviewBodyId() {
        return previewBodyId;
    }

    public void setPreviewBodyId(Long previewBodyId) {
        this.previewBodyId = previewBodyId;
    }
}
