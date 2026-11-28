package com.github.enerccio.joi.domain.animations;

import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The folders of the library as a tree: what is in a folder, the paths, the labels of the items and their order. */
class AnimationFoldersTest {

    private static AnimationFolder folder(String uuid, String name, String parent) {
        return new AnimationFolder(uuid, name, parent, Kind.CLIPS);
    }

    private final AnimationFolders tree = new AnimationFolders(List.of(
            folder("idle", "Idle", null),
            folder("standing", "Standing", "idle"),
            folder("sitting", "sitting", "idle"),
            folder("deep", "Deep", "standing"),
            folder("dance", "Dance", null)));

    private record Item(String folder, String name) {
    }

    @Test
    void foldersAreNestedAndListedByName() {
        assertThat(tree.children(null)).extracting(AnimationFolder::getName).containsExactly("Dance", "Idle");
        assertThat(tree.children("idle")).extracting(AnimationFolder::getName).containsExactly("sitting", "Standing");
        assertThat(tree.children("deep")).isEmpty();
        assertThat(tree.ancestors("deep")).extracting(AnimationFolder::getName).containsExactly("Idle", "Standing");
        assertThat(tree.ancestors("idle")).isEmpty();
        assertThat(tree.withDescendants("idle")).containsExactlyInAnyOrder("idle", "standing", "sitting", "deep");
        assertThat(tree.withDescendants("gone")).isEmpty();
        // a folder whose parent is gone is at the top level, as an item of a folder that is gone
        AnimationFolders orphaned = new AnimationFolders(List.of(folder("a", "A", "gone")));
        assertThat(orphaned.children(null)).extracting(AnimationFolder::getUuid).containsExactly("a");
        assertThat(orphaned.known("gone")).isNull();
    }

    @Test
    void anItemIsLabelledWithThePathOfItsFolder() {
        assertThat(tree.path("deep")).isEqualTo("Idle / Standing / Deep");
        assertThat(tree.path(null)).isEmpty();
        assertThat(tree.label("standing", "Breathe")).isEqualTo("Idle / Standing / Breathe");
        assertThat(tree.label(null, "Wave")).isEqualTo("Wave");
        assertThat(tree.label("gone", "Wave")).isEqualTo("Wave");

        List<Item> items = List.of(new Item(null, "Wave"), new Item("standing", "Breathe"), new Item("idle", "Yawn"),
                new Item(null, "applause"), new Item("deep", "Sigh"), new Item("dance", "Spin"));
        assertThat(tree.sortedByLabel(items, Item::folder, Item::name)).extracting(item -> tree.label(item.folder(), item.name()))
                .containsExactly("applause", "Dance / Spin", "Idle / Standing / Breathe", "Idle / Standing / Deep / Sigh",
                        "Idle / Yawn", "Wave");
    }

    @Test
    void aNameIsCheckedAndTakenCaseInsensitivelyInItsParent() {
        assertThat(AnimationFolders.validName("  Idle ")).isEqualTo("Idle");
        assertThatThrownBy(() -> AnimationFolders.validName(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnimationFolders.validName("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(tree.taken("idle", "STANDING", null)).isTrue();
        assertThat(tree.taken("idle", "standing", "standing")).isFalse();
        assertThat(tree.taken(null, "Standing", null)).isFalse();
    }

    @Test
    void aLoopEndsThePath() {
        AnimationFolders loop = new AnimationFolders(List.of(folder("a", "A", "b"), folder("b", "B", "a")));
        assertThat(loop.path("a")).isEqualTo("B / A");
        assertThat(loop.withDescendants("a")).containsExactlyInAnyOrder("a", "b");
    }
}
