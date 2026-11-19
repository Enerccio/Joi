package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.animations.VrmaTestFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringBonesTestFiles;
import com.github.enerccio.joi.domain.model.impl.*;
import com.github.enerccio.joi.domain.service.*;
import com.github.enerccio.joi.ui.dialogs.AnimationDialog;
import com.github.enerccio.joi.ui.dialogs.AnimationPackDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library of clips (the Animations tab: uploads, the clip dialog) and the packs that list them (the Animation
 * packs tab), previewed on a body.
 */
class AnimationsTest extends PlatformTestBase {

    @Autowired
    private AnimationPackService packService;
    @Autowired
    private AnimationService animationService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiService joiService;
    @Autowired
    private BodyService bodyService;

    /** Held here: the current UI is only weakly referenced. */
    private UI ui;
    private Body body;
    private AnimationsPart part;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        File vrm = SpringBonesTestFiles.vrm(ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.vrm"));
        Model model = modelService.attach(new Model(), vrm, "Ryuu.vrm");
        Joi joi = joiService.save(new Joi());
        body = bodyService.create(joi, "Street");
        body.setModel(model);
        body = bodyService.save(body);
        // a body without a model has nothing to preview
        bodyService.create(joi, "Empty");
        part = new AnimationsPart();
        part.create();
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private Animation upload(String name) throws Exception {
        File file = VrmaTestFiles.wave(ModelFiles.uploadFile(modelService.uploadFolder(), name));
        part.uploadedForTest(file, name);
        return rows().stream().filter(clip -> clip.getFileName().equals(name)).findFirst().orElseThrow();
    }

    private List<Animation> rows() {
        return part.getGrid().getDataProvider().fetch(new Query<>()).toList();
    }

    private static List<String> names(AnimationPackDialog dialog) {
        return dialog.getClips().getDataProvider().fetch(new Query<>()).map(Animation::getName).toList();
    }

    @Test
    void aVrmaJoinsTheLibrary() throws Exception {
        Animation clip = upload("Wave hello.vrma");
        assertThat(clip.getName()).isEqualTo("Wave hello");
        assertThat(clip.getDuration()).isEqualTo(2.0);
        assertThat(clip.getBones()).containsExactly("rightUpperArm", "rightLowerArm");
        assertThat(clip.getSource()).isNull();
        assertThat(animationService.file(clip)).exists();
    }

    @Test
    void anythingElseIsRefused() throws Exception {
        File fake = ModelFiles.uploadFile(modelService.uploadFolder(), "dance.vrma");
        Files.writeString(fake.toPath(), "not a clip");
        part.uploadedForTest(fake, "dance.vrma");
        File glb = ModelFiles.uploadFile(modelService.uploadFolder(), "dance.glb");
        Files.writeString(glb.toPath(), "a model");
        part.uploadedForTest(glb, "dance.glb");
        assertThat(fake).doesNotExist();
        assertThat(glb).doesNotExist();
        assertThat(rows()).isEmpty();
    }

    @Test
    void aClipIsEditedInItsDialog() throws Exception {
        Animation clip = upload("wave.vrma");
        AnimationDialog dialog = new AnimationDialog(clip);
        dialog.create();
        dialog.getNameField().setValue("Wave");
        dialog.getTagsField().setValue("Hello, wave, hello ,");
        dialog.getLoop().setValue(true);
        dialog.getInPlace().setValue(true);

        Animation stored = animationService.find(clip.getId());
        assertThat(stored.getName()).isEqualTo("Wave");
        assertThat(stored.getTags()).containsExactly("hello", "wave");
        assertThat(stored.loops()).isTrue();
        assertThat(stored.playsInPlace()).isTrue();
        assertThat(stored.speedOrDefault()).isEqualTo(1.0);
    }

    @Test
    void aPackListsClipsOfTheLibrary() throws Exception {
        Animation wave = upload("wave.vrma");
        Animation bow = upload("bow.vrma");

        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        assertThat(dialog.getPack().getId()).isNull();
        assertThat(dialog.getPreview().getBodySelect().getListDataView().getItems().toList()).containsExactly(body.getId());
        assertThat(dialog.getChoice().getListDataView().getItems().map(Animation::getName).toList()).containsExactly("bow", "wave");

        // adding the first clips stores a new pack, with the body it is previewed on
        dialog.getChoice().setValue(Set.of(wave));
        dialog.addChosenForTest();
        AnimationPack pack = packService.find(dialog.getPack().getId());
        assertThat(pack.getClips()).containsExactly(wave.getUuid());
        assertThat(pack.getPreviewBodyId()).isEqualTo(body.getId());
        assertThat(names(dialog)).containsExactly("wave");
        assertThat(dialog.getChoice().getListDataView().getItems().map(Animation::getName).toList()).containsExactly("bow");

        dialog.getChoice().setValue(Set.of(bow));
        dialog.addChosenForTest();
        AnimationPackDialog reopened = new AnimationPackDialog(packService.find(pack.getId()));
        reopened.create();
        assertThat(names(reopened)).containsExactly("wave", "bow");
        assertThat(reopened.getPreview().getBodySelect().getValue()).isEqualTo(body.getId());
    }

    @Test
    void aClipCanBeInSeveralPacksAndLeavesThemWhenDeleted() throws Exception {
        Animation wave = upload("wave.vrma");
        Animation bow = upload("bow.vrma");
        AnimationPack greetings = new AnimationPack();
        greetings.setName("Greetings");
        greetings = packService.addClips(packService.save(greetings), List.of(wave, bow));
        AnimationPack happy = new AnimationPack();
        happy.setName("Happy");
        happy = packService.addClips(packService.save(happy), List.of(wave));
        assertThat(packService.packsWith(wave)).extracting(AnimationPack::getName).containsExactly("Greetings", "Happy");

        // deleting a pack keeps its clips
        packService.delete(packService.find(happy.getId()), false);
        assertThat(animationService.findAllByName()).hasSize(2);

        // deleting a clip takes it out of its packs
        animationService.delete(wave, false);
        assertThat(packService.clips(packService.find(greetings.getId()))).extracting(Animation::getName).containsExactly("bow");
        assertThat(packService.find(greetings.getId()).getClips()).containsExactly(bow.getUuid());
    }
}
