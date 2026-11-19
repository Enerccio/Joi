package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * An animation clip of a {@link AnimationPack pack} (see {@code DESIGN.md} → Bodies, animations): a VRMA file (VRM
 * Animation, on the humanoid bones, so it plays on any body), a resource of the user. What the user decides on it is
 * kept on the clip, never in the file: the name, the description, the tags, whether it loops and its speed. What was
 * read from the file (the duration, the bones it moves) is kept too, so a list needs no file.
 */
@Entity
@Table(name = "animations")
public class Animation extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pack_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private AnimationPack pack;

    /** The VRMA file. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id")
    @CleanupReference(Policy.OWNS)
    private Resource resource;

    @ExtendedAttribute
    @Transient
    private String name;

    /** What the clip shows and when it fits, written for her: she picks a clip by what it says. */
    @ExtendedAttribute
    @Transient
    private String description;

    @ExtendedAttribute(inject = true, injectPrefix = "tags")
    @Transient
    private List<String> tags = new ArrayList<>();

    /** Whether it plays in a loop (an idle) or once (a wave); null for once. */
    @ExtendedAttribute
    @Transient
    private Boolean loop;

    /** How fast it plays, 1 for as made; null for 1. */
    @ExtendedAttribute
    @Transient
    private Double speed;

    /** The name of the uploaded file. */
    @ExtendedAttribute
    @Transient
    private String fileName;

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

    public AnimationPack getPack() {
        return pack;
    }

    public void setPack(AnimationPack pack) {
        this.pack = pack;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

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
