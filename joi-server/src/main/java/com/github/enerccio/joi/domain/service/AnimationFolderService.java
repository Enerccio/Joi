package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.animations.AnimationFolder;
import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import com.github.enerccio.joi.domain.animations.AnimationFolders;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;

import java.util.Collection;
import java.util.List;

/**
 * The folders of the user's animation library ({@link AnimationFolder}, see {@code DESIGN.md} → Bodies, animations →
 * Folders), as Marginalia's book folders: nested, the names of one parent unique (case insensitive), a folder deleted
 * with its content moved up or with all of it. Clips and animations have folders of their own kind.
 */
public interface AnimationFolderService {

    /** The user's folders of the kind as a tree. */
    AnimationFolders tree(Kind kind) throws Exception;

    /**
     * Makes a folder in the parent (null: the top level).
     *
     * @throws IllegalArgumentException when the name can't be a folder's ({@link AnimationFolders#validName})
     * @throws IllegalStateException    when a folder of the parent has the name
     */
    AnimationFolder create(Kind kind, String name, String parent) throws Exception;

    /**
     * Renames the folder; null when it is gone.
     *
     * @throws IllegalArgumentException when the name can't be a folder's
     * @throws IllegalStateException    when another folder of its parent has the name
     */
    AnimationFolder rename(Kind kind, String uuid, String name) throws Exception;

    /**
     * Moves the folder, with what is in it, into another (null: the top level); null when it is gone.
     *
     * @throws IllegalArgumentException when the target is the folder or a folder below it
     * @throws IllegalStateException    when a folder of the target has its name
     */
    AnimationFolder move(Kind kind, String uuid, String parent) throws Exception;

    /**
     * What is in a folder, at any depth.
     *
     * @param items   the clips or animations
     * @param folders the folders below it
     */
    record Content(int items, int folders) {
    }

    /** What is in the folder and the folders below it. */
    Content describeContent(Kind kind, String uuid) throws Exception;

    /**
     * Deletes the folder; what is in it (its folders, its clips or animations) moves to the folder above.
     *
     * @throws IllegalStateException when a folder in it has the name of one of the folder above (nothing is changed)
     */
    void delete(Kind kind, String uuid) throws Exception;

    /**
     * Deletes the folder, the folders below it and the clips or animations in them (a clip leaves its animations, an
     * animation keeps its clips).
     */
    void deleteWithContent(Kind kind, String uuid) throws Exception;

    /** The clips in the folder (null: the top level, with those of a folder that is gone), by name. */
    List<Animation> clipsIn(String folder) throws Exception;

    /** The animations in the folder (null: the top level, with those of a folder that is gone), by name. */
    List<AnimationPack> animationsIn(String folder) throws Exception;

    /** Puts the clips into the folder (null: the top level). */
    void moveClips(Collection<Animation> clips, String folder) throws Exception;

    /** Puts the animations into the folder (null: the top level). */
    void moveAnimations(Collection<AnimationPack> animations, String folder) throws Exception;

    /** All the user's clips sorted by their labels: the path of the folder before the name. */
    List<Animation> clipsByPath() throws Exception;

    /** All the user's animations sorted by their labels. */
    List<AnimationPack> animationsByPath() throws Exception;
}
