package com.github.enerccio.joi.domain.model.impl;

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
 * A set of the user's animation clips ({@link Animation}, from their library), see {@code DESIGN.md} → Bodies,
 * animations: "my idles", "greetings". A clip can be in several packs; deleting a pack keeps its clips. The clips play
 * on any body (every body has a humanoid rig); the pack remembers which body the user previews them on. It has no
 * columns of its own: the name, the description, its clips and the preview body are extended attributes.
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

    /** Its clips, by their uuid, in the pack's order. */
    @ExtendedAttribute(inject = true, injectPrefix = "clips")
    @Transient
    private List<String> clips = new ArrayList<>();

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

    public List<String> getClips() {
        return clips;
    }

    public void setClips(List<String> clips) {
        this.clips = clips == null ? new ArrayList<>() : new ArrayList<>(clips);
    }

    public Long getPreviewBodyId() {
        return previewBodyId;
    }

    public void setPreviewBodyId(Long previewBodyId) {
        this.previewBodyId = previewBodyId;
    }
}
