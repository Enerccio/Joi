package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.animations.AnimationFolder;
import com.github.enerccio.joi.domain.animations.AnimationFolder.Kind;
import com.github.enerccio.joi.domain.animations.VrmaTestFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.joi.ui.animations.FolderRow;
import com.github.enerccio.joi.ui.dialogs.AnimationPackDialog;
import com.github.enerccio.joi.ui.dialogs.BodyDialog;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The folders of the animation library, as Marginalia's book folders: nested, opened in the tabs (their folders first,
 * the path above the table), items moved into them, deleted with their content moved up or with all of it; and every
 * choice of a clip or an animation shows the path of its folder before its name.
 */
class LibraryFoldersTest extends PlatformTestBase {

    @Autowired
    private AnimationFolderService folderService;
    @Autowired
    private AnimationService animationService;
    @Autowired
    private AnimationPackService packService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiService joiService;

    private UI ui;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private Animation clip(String name) throws Exception {
        File file = VrmaTestFiles.wave(ModelFiles.uploadFile(modelService.uploadFolder(), name + ".vrma"));
        Animation clip = animationService.add(file, name + ".vrma");
        clip.setName(name);
        return animationService.save(clip);
    }

    private AnimationPack pack(String name, String folder) throws Exception {
        AnimationPack pack = new AnimationPack();
        pack.setName(name);
        pack.setFolder(folder);
        return packService.save(pack);
    }

    private static <T> List<String> names(List<FolderRow<T>> rows, java.util.function.Function<T, String> name) {
        return rows.stream().map(row -> row.isFolder() ? "[" + row.folder().getName() + "]" : name.apply(row.item())).toList();
    }

    private static List<FolderRow<Animation>> rows(AnimationsPart part) {
        return part.getGrid().getDataProvider().fetch(new Query<>()).toList();
    }

    private static List<String> breadcrumb(AnimationsPart part) {
        return part.getFolders().getBreadcrumb().getChildren()
                .map(c -> c instanceof Button button ? button.getText() : ((Span) c).getText()).toList();
    }

    @Test
    void foldersAreNestedAndOpenedInTheTab() throws Exception {
        AnimationFolder idle = folderService.create(Kind.CLIPS, "Idle", null);
        AnimationFolder standing = folderService.create(Kind.CLIPS, " Standing ", idle.getUuid());
        folderService.create(Kind.CLIPS, "Dance", null);
        // the folders of the animations are their own
        folderService.create(Kind.ANIMATIONS, "Idle", null);
        assertThat(standing.getName()).isEqualTo("Standing");
        Animation wave = clip("Wave");
        Animation breathe = clip("Breathe");
        Animation yawn = clip("Yawn");
        folderService.moveClips(List.of(breathe), standing.getUuid());
        folderService.moveClips(List.of(yawn), idle.getUuid());

        AnimationsPart part = new AnimationsPart();
        part.create();
        part.refresh();
        assertThat(names(rows(part), Animation::getName)).containsExactly("[Dance]", "[Idle]", "Wave");
        assertThat(breadcrumb(part)).containsExactly("Clips");

        part.getFolders().open(idle.getUuid());
        assertThat(names(rows(part), Animation::getName)).containsExactly("[Standing]", "Yawn");
        part.getFolders().open(standing.getUuid());
        assertThat(names(rows(part), Animation::getName)).containsExactly("Breathe");
        assertThat(breadcrumb(part)).containsExactly("Clips", "/", "Idle", "/", "Standing");

        // an upload goes into the folder open
        File file = VrmaTestFiles.wave(ModelFiles.uploadFile(modelService.uploadFolder(), "Sigh.vrma"));
        part.uploadedForTest(file, "Sigh.vrma");
        assertThat(names(rows(part), Animation::getName)).containsExactly("Breathe", "Sigh");
        // so do the clips of an FBX or a BVH: into the folder open when the file came, wherever the user is when it is done
        Animation jump = clip("Jump");
        Animation land = clip("Land");
        part.getFolders().open(null);
        part.convertedForTest("Jump.fbx", List.of(jump, land), standing.getUuid());
        assertThat(folderService.clipsIn(standing.getUuid())).extracting(Animation::getName).containsExactly("Breathe", "Jump", "Land", "Sigh");
        part.getFolders().open(standing.getUuid());

        // moved to the top level
        part.moveForTest(List.of(breathe), null);
        assertThat(names(rows(part), Animation::getName)).containsExactly("Jump", "Land", "Sigh");
        assertThat(animationService.find(breathe.getId()).getFolder()).isNull();
        assertThat(folderService.clipsIn(null)).extracting(Animation::getName).containsExactly("Breathe", "Wave");
    }

    @Test
    void namesAreUniqueInAParentAndAFolderCantGoIntoItself() throws Exception {
        AnimationFolder idle = folderService.create(Kind.CLIPS, "Idle", null);
        AnimationFolder standing = folderService.create(Kind.CLIPS, "Standing", idle.getUuid());
        AnimationFolder deep = folderService.create(Kind.CLIPS, "Deep", standing.getUuid());
        AnimationFolder other = folderService.create(Kind.CLIPS, "Standing", null);
        assertThatThrownBy(() -> folderService.create(Kind.CLIPS, "idle", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> folderService.create(Kind.CLIPS, "a/b", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> folderService.rename(Kind.CLIPS, other.getUuid(), "IDLE")).isInstanceOf(IllegalStateException.class);
        assertThat(folderService.rename(Kind.CLIPS, idle.getUuid(), "Rest").getName()).isEqualTo("Rest");

        assertThatThrownBy(() -> folderService.move(Kind.CLIPS, idle.getUuid(), deep.getUuid())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> folderService.move(Kind.CLIPS, idle.getUuid(), idle.getUuid())).isInstanceOf(IllegalArgumentException.class);
        // a Standing is at the top level already
        assertThatThrownBy(() -> folderService.move(Kind.CLIPS, standing.getUuid(), null)).isInstanceOf(IllegalStateException.class);
        folderService.move(Kind.CLIPS, deep.getUuid(), null);
        assertThat(folderService.tree(Kind.CLIPS).path(deep.getUuid())).isEqualTo("Deep");
        folderService.move(Kind.CLIPS, deep.getUuid(), other.getUuid());
        assertThat(folderService.tree(Kind.CLIPS).path(deep.getUuid())).isEqualTo("Standing / Deep");

        // where a folder can go: not into itself nor below it, nor where it is
        AnimationsPart part = new AnimationsPart();
        part.create();
        assertThat(part.getFolders().folderTargetsForTest(folderService.tree(Kind.CLIPS).find(idle.getUuid())))
                .containsExactly("Standing", "Standing / Deep");
        assertThat(part.getFolders().folderTargetsForTest(folderService.tree(Kind.CLIPS).find(deep.getUuid())))
                .containsExactly("(top level)", "Rest", "Rest / Standing");
    }

    @Test
    void aFolderDeletedMovesWhatIsInItUpOrDeletesAllOfIt() throws Exception {
        AnimationFolder idle = folderService.create(Kind.CLIPS, "Idle", null);
        AnimationFolder standing = folderService.create(Kind.CLIPS, "Standing", idle.getUuid());
        AnimationFolder deep = folderService.create(Kind.CLIPS, "Deep", standing.getUuid());
        Animation breathe = clip("Breathe");
        Animation sigh = clip("Sigh");
        folderService.moveClips(List.of(breathe), standing.getUuid());
        folderService.moveClips(List.of(sigh), deep.getUuid());
        AnimationPack calm = pack("Calm", null);
        calm = packService.addClips(calm, List.of(breathe, sigh));
        assertThat(folderService.describeContent(Kind.CLIPS, idle.getUuid())).isEqualTo(new AnimationFolderService.Content(2, 2));

        // Standing's content goes up into Idle
        folderService.delete(Kind.CLIPS, standing.getUuid());
        assertThat(folderService.tree(Kind.CLIPS).find(standing.getUuid())).isNull();
        assertThat(folderService.tree(Kind.CLIPS).path(deep.getUuid())).isEqualTo("Idle / Deep");
        assertThat(animationService.find(breathe.getId()).getFolder()).isEqualTo(idle.getUuid());

        // a folder in it with the name of one above: refused, nothing changed
        AnimationFolder topDeep = folderService.create(Kind.CLIPS, "Deep", null);
        assertThatThrownBy(() -> folderService.delete(Kind.CLIPS, idle.getUuid())).isInstanceOf(IllegalStateException.class);
        assertThat(folderService.tree(Kind.CLIPS).find(idle.getUuid())).isNotNull();
        assertThat(animationService.find(breathe.getId()).getFolder()).isEqualTo(idle.getUuid());
        folderService.delete(Kind.CLIPS, topDeep.getUuid());

        // all of it: the folders and the clips, which leave their animations
        folderService.deleteWithContent(Kind.CLIPS, idle.getUuid());
        assertThat(folderService.tree(Kind.CLIPS).all()).isEmpty();
        assertThat(animationService.findAllByName()).isEmpty();
        assertThat(packService.find(calm.getId()).steps()).isEmpty();

        // the animations: their clips stay
        Animation wave = clip("Wave");
        AnimationFolder greetings = folderService.create(Kind.ANIMATIONS, "Greetings", null);
        AnimationPack hello = packService.addClips(pack("Hello", greetings.getUuid()), List.of(wave));
        pack("Rest", null);
        assertThat(folderService.animationsIn(greetings.getUuid())).extracting(AnimationPack::getName).containsExactly("Hello");
        folderService.deleteWithContent(Kind.ANIMATIONS, greetings.getUuid());
        assertThat(packService.find(hello.getId()) == null || packService.find(hello.getId()).isDeleted()).isTrue();
        assertThat(packService.findAllByName()).extracting(AnimationPack::getName).containsExactly("Calm", "Rest");
        assertThat(animationService.findAllByName()).extracting(Animation::getName).containsExactly("Wave");
    }

    @Test
    void theAnimationsTabHasFoldersTooAndANewAnimationGoesIntoTheFolderOpen() throws Exception {
        AnimationFolder greetings = folderService.create(Kind.ANIMATIONS, "Greetings", null);
        pack("Hello", greetings.getUuid());
        pack("Rest", null);
        AnimationPacksPart part = new AnimationPacksPart();
        part.create();
        part.refresh();
        List<FolderRow<AnimationPack>> rows = part.getGrid().getDataProvider().fetch(new Query<>()).toList();
        assertThat(names(rows, AnimationPack::getName)).containsExactly("[Greetings]", "Rest");
        part.getFolders().open(greetings.getUuid());
        rows = part.getGrid().getDataProvider().fetch(new Query<>()).toList();
        assertThat(names(rows, AnimationPack::getName)).containsExactly("Hello");
        assertThat(part.newPackForTest().getFolder()).isEqualTo(greetings.getUuid());
    }

    @Test
    void everyChoiceShowsThePathBeforeTheNameSortedByIt() throws Exception {
        AnimationFolder idle = folderService.create(Kind.CLIPS, "Idle", null);
        AnimationFolder standing = folderService.create(Kind.CLIPS, "Standing", idle.getUuid());
        Animation breathe = clip("Breathe");
        Animation wave = clip("Wave");
        Animation applause = clip("Applause");
        folderService.moveClips(List.of(breathe), standing.getUuid());

        // the clip of a step
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        List<Animation> choices = dialog.getChoice().getListDataView().getItems().toList();
        assertThat(choices).extracting(clip -> dialog.getChoice().getItemLabelGenerator().apply(clip))
                .containsExactly("Applause", "Idle / Standing / Breathe", "Wave");

        // her roles and the animations of a body
        AnimationFolder greetings = folderService.create(Kind.ANIMATIONS, "Greetings", null);
        AnimationFolder formal = folderService.create(Kind.ANIMATIONS, "Formal", greetings.getUuid());
        AnimationPack bow = pack("Bow", formal.getUuid());
        AnimationPack nap = pack("Nap", null);
        AnimationPack hello = pack("Hello", greetings.getUuid());
        Joi joi = joiService.create("Mira");
        JoiDialog joiDialog = new JoiDialog(joiService.find(joi.getId()));
        joiDialog.create();
        var sleeping = joiDialog.getAnimationsPart().getSingleFields().getFirst();
        assertThat(sleeping.getListDataView().getItems().toList()).containsExactly(bow.getUuid(), hello.getUuid(), nap.getUuid());
        assertThat(sleeping.getItemLabelGenerator().apply(bow.getUuid())).isEqualTo("Greetings / Formal / Bow");
        var idleField = joiDialog.getAnimationsPart().getListFields().getFirst();
        assertThat(idleField.getItemLabelGenerator().apply(hello.getUuid())).isEqualTo("Greetings / Hello");

        BodyDialog bodyDialog = new BodyDialog(joiService.find(joi.getId()), null);
        bodyDialog.create();
        var select = bodyDialog.getAnimationSelect();
        assertThat(select.getListDataView().getItems().toList()).containsExactly(bow.getUuid(), hello.getUuid(), nap.getUuid());
        assertThat(select.getItemLabelGenerator().apply(bow.getUuid())).isEqualTo("Greetings / Formal / Bow");
        assertThat(select.getItemLabelGenerator().apply(nap.getUuid())).isEqualTo("Nap");
    }
}
