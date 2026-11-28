package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

/**
 * An animation clip of the user's library (see {@code DESIGN.md} → Bodies, animations): a VRMA file (VRM Animation, on
 * the humanoid bones, so it plays on any body), a resource of the user, uploaded as it is or converted from an FBX or a
 * BVH. {@link AnimationPack Packs} list clips of the library; a clip can be in several. What the user decides on it is
 * kept on the clip, never in the file: the name, the description, the tags, whether it loops and its speed. What was
 * read from the file (the duration, the bones it moves) is kept too, so a list needs no file.
 */
@Entity
@Table(name = "animations")
public class Animation extends ExtendableEntity {

    /** The VRMA file. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id")
    @CleanupReference(Policy.OWNS)
    private Resource resource;

    @ExtendedAttribute
    @Transient
    private String name;

    /** The uuid of the folder of clips it is in ({@code AnimationFolder}), null for the top level. */
    @ExtendedAttribute
    @Transient
    private String folder;

    /** What the clip shows and when it fits, written for her: she picks a clip by what it says. */
    @ExtendedAttribute
    @Transient
    private String description;

    @ExtendedAttribute(inject = true, injectPrefix = "tags")
    @Transient
    private List<String> tags = new ArrayList<>();

    /**
     * Whether it plays in a loop (an idle) or once (a wave); null for once. Animations decide how a clip plays (their
     * {@code AnimationStep}s): this is only what a new step of the clip starts from, set before steps had it.
     */
    @ExtendedAttribute
    @Transient
    private Boolean loop;

    /** Whether her hips keep their spot on the floor (the clip's moving about is left out); null for no. As the loop. */
    @ExtendedAttribute
    @Transient
    private Boolean inPlace;

    /** How fast it plays, 1 for as made; null for 1. As the loop. */
    @ExtendedAttribute
    @Transient
    private Double speed;

    /** The name of the uploaded file (the VRMA, or the one made from an FBX or a BVH). */
    @ExtendedAttribute
    @Transient
    private String fileName;

    /** The FBX or BVH it was converted from, null for a VRMA uploaded as it is. */
    @ExtendedAttribute
    @Transient
    private String source;

    /** The take of the source it is (an FBX can hold several), null for none. */
    @ExtendedAttribute
    @Transient
    private String take;

    @ExtendedAttribute
    @Transient
    private Long fileSize;

    /** How long it is in seconds, read from the file. */
    @ExtendedAttribute
    @Transient
    private Double duration;

    /** The humanoid bones it moves (VRM names: {@code hips}, {@code rightUpperArm}), read from the file. */
    @ExtendedAttribute(inject = true, injectPrefix = "bones")
    @Transient
    private List<String> bones = new ArrayList<>();

    /** Whether it moves her expressions too (a smile while she waves). */
    @ExtendedAttribute
    @Transient
    private Boolean expressions;

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public String getName() {
        return name;
    }

    /** The uuid of its folder, null for the top level (a folder that is gone is the top level too). */
    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
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

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public Boolean getLoop() {
        return loop;
    }

    public void setLoop(Boolean loop) {
        this.loop = loop;
    }

    /** Whether it loops. */
    public boolean loops() {
        return Boolean.TRUE.equals(loop);
    }

    public Boolean getInPlace() {
        return inPlace;
    }

    public void setInPlace(Boolean inPlace) {
        this.inPlace = inPlace;
    }

    /** Whether it plays in place. */
    public boolean playsInPlace() {
        return Boolean.TRUE.equals(inPlace);
    }

    public Double getSpeed() {
        return speed;
    }

    public void setSpeed(Double speed) {
        this.speed = speed;
    }

    /** Its speed, 1 when none was set. */
    public double speedOrDefault() {
        return speed == null || speed <= 0 ? 1 : speed;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTake() {
        return take;
    }

    public void setTake(String take) {
        this.take = take;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Double getDuration() {
        return duration;
    }

    public void setDuration(Double duration) {
        this.duration = duration;
    }

    public List<String> getBones() {
        return bones;
    }

    public void setBones(List<String> bones) {
        this.bones = bones == null ? new ArrayList<>() : new ArrayList<>(bones);
    }

    public Boolean getExpressions() {
        return expressions;
    }

    public void setExpressions(Boolean expressions) {
        this.expressions = expressions;
    }
}
