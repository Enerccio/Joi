package com.github.enerccio.joi.ui.animations;

import com.github.enerccio.joi.domain.animations.AnimationFolder;

/**
 * A row of a table of the animation library: a folder (listed first, opened from its name) or an item, a clip or an
 * animation.
 *
 * @param folder the folder, null for an item
 * @param item   the item, null for a folder
 */
public record FolderRow<T>(AnimationFolder folder, T item) {

    public static <T> FolderRow<T> ofFolder(AnimationFolder folder) {
        return new FolderRow<>(folder, null);
    }

    public static <T> FolderRow<T> ofItem(T item) {
        return new FolderRow<>(null, item);
    }

    public boolean isFolder() {
        return folder != null;
    }
}
