package com.github.enerccio.joi.domain.animations;

/**
 * A folder of the animation library (see {@code DESIGN.md} → Bodies, animations → Folders): of clips or of animations
 * ({@link Kind}), nested in another folder of the same kind (the parent's uuid) or at the top level (null). The names of
 * the folders of one parent are unique, case insensitive. A clip or an animation names its folder by uuid
 * ({@code Animation.getFolder()}, {@code AnimationPack.getFolder()}); an unknown one is the top level. Kept in the user
 * setting ({@code JoiUserSetting.getAnimationFolders()}), as Marginalia keeps its book folders in a table.
 */
public class AnimationFolder {

    /** What a folder holds: the clips of the Clips tab or the animations of the Animations tab. */
    public enum Kind {
        CLIPS,
        ANIMATIONS
    }

    private String uuid;
    private String name;
    private String parent;
    private Kind kind;

    public AnimationFolder() {
    }

    public AnimationFolder(String uuid, String name, String parent, Kind kind) {
        this.uuid = uuid;
        this.name = name;
        this.parent = parent;
        this.kind = kind;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The uuid of the folder it is in, null for the top level. */
    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AnimationFolder other && uuid != null && uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid == null ? 0 : uuid.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
