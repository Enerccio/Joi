package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.animations.StepRotation;
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
import static org.assertj.core.api.Assertions.within;

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
        return part.getGrid().getDataProvider().fetch(new Query<>()).filter(row -> !row.isFolder()).map(row -> row.item()).toList();
    }

    private static List<String> names(AnimationPackDialog dialog) {
        return dialog.getSteps().getDataProvider().fetch(new Query<>()).map(step -> step.clip().getName()).toList();
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

        Animation stored = animationService.find(clip.getId());
        assertThat(stored.getName()).isEqualTo("Wave");
        assertThat(stored.getTags()).containsExactly("hello", "wave");
    }

    @Test
    void anAnimationIsStepsOfClips() throws Exception {
        Animation wave = upload("wave.vrma");
        wave.setLoop(true);
        wave = animationService.save(wave);
        Animation bow = upload("bow.vrma");

        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        assertThat(dialog.getPack().getId()).isNull();
        assertThat(dialog.getPreview().getBodySelect().getListDataView().getItems().toList()).containsExactly(body.getId());
        assertThat(dialog.getChoice().getListDataView().getItems().map(Animation::getName).toList()).containsExactly("bow", "wave");

        // adding the first clips stores a new animation, with the body it is previewed on; a step starts as its clip was set
        dialog.getChoice().setValue(Set.of(wave));
        dialog.addChosenForTest();
        AnimationPack pack = packService.find(dialog.getPack().getId());
        assertThat(pack.clipUuids()).containsExactly(wave.getUuid());
        assertThat(pack.steps().getFirst().loop()).isTrue();
        assertThat(pack.getPreviewBodyId()).isEqualTo(body.getId());
        assertThat(names(dialog)).containsExactly("wave");

        // a clip can be in several steps: the whole library stays to choose from
        assertThat(dialog.getChoice().getListDataView().getItems().map(Animation::getName).toList()).containsExactly("bow", "wave");
        dialog.getChoice().setValue(Set.of(bow, wave));
        dialog.addChosenForTest();
        assertThat(names(dialog)).containsExactly("wave", "bow", "wave");

        // a step is set in the table and moved; the whole animation loops
        dialog.updateStepForTest(1, step -> step.withSpeed(1.5).withInPlace(true));
        dialog.moveStepForTest(1, -1);
        dialog.getLoopField().setValue(true);
        // the face she makes while it plays: stored, and "her own" (empty) is none
        dialog.getEyesField().setValue("closed");
        dialog.getExpressionField().setValue("sad");
        AnimationPackDialog reopened = new AnimationPackDialog(packService.find(pack.getId()));
        reopened.create();
        assertThat(names(reopened)).containsExactly("bow", "wave", "wave");
        AnimationStep first = reopened.getPack().steps().getFirst();
        assertThat(first.speed()).isEqualTo(1.5);
        assertThat(first.inPlace()).isTrue();
        assertThat(first.loop()).isFalse();
        assertThat(reopened.getPack().loops()).isTrue();
        assertThat(reopened.getPack().getEyes()).isEqualTo("closed");
        assertThat(reopened.getPack().getExpression()).isEqualTo("sad");
        assertThat(reopened.getEyesField().getValue()).isEqualTo("closed");
        reopened.getEyesField().setValue("");
        assertThat(packService.find(reopened.getPack().getId()).getEyes()).isNull();
        assertThat(packService.find(reopened.getPack().getId()).getExpression()).isEqualTo("sad");
        assertThat(reopened.getPreview().getBodySelect().getValue()).isEqualTo(body.getId());
        // nothing is chosen in the table: the whole animation plays
        assertThat(reopened.getSteps().getSelectedItems()).isEmpty();
    }

    @Test
    void aStepIsTurnedAndBlendedInTheAnimationNotTheClip() throws Exception {
        Animation wave = upload("wave.vrma");
        Animation bow = upload("bow.vrma");
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        dialog.getChoice().setValue(Set.of(bow, wave));
        dialog.addChosenForTest();
        assertThat(dialog.getRotationPanel().isVisible()).isFalse();

        // a step chosen shows its rotation: typed in degrees, or turned with the rings
        dialog.getSteps().select(dialog.getSteps().getListDataView().getItems().toList().get(1));
        assertThat(dialog.getRotationPanel().isVisible()).isTrue();
        assertThat(dialog.getRotationFields()[1].getValue()).isZero();
        dialog.rotateForTest(0, 90, 0);
        AnimationStep turned = packService.find(dialog.getPack().getId()).steps().get(1);
        assertThat(turned.rotation()).hasSize(4);
        assertThat(turned.rotation().get(1)).isCloseTo(Math.sqrt(0.5), within(1e-9));
        assertThat(turned.rotation().get(3)).isCloseTo(Math.sqrt(0.5), within(1e-9));
        assertThat(dialog.getRotationFields()[1].getValue()).isEqualTo(90);

        dialog.rotateWithRingsForTest(StepRotation.fromDegrees(-30, 45, 10));
        assertThat(dialog.getRotationFields()[0].getValue()).isEqualTo(-30);
        assertThat(dialog.getRotationFields()[1].getValue()).isEqualTo(45);
        assertThat(dialog.getRotationFields()[2].getValue()).isEqualTo(10);
        assertThat(dialog.getSteps().getSelectedItems()).hasSize(1);

        dialog.updateStepForTest(1, step -> step.withBlend(0.75));
        AnimationStep stored = packService.find(dialog.getPack().getId()).steps().get(1);
        assertThat(stored.blend()).isEqualTo(0.75);
        assertThat(StepRotation.toDegrees(stored.rotation())[1]).isCloseTo(45, within(1e-6));
        // the step before is as it was (the clips have no rotation of their own)
        assertThat(packService.find(dialog.getPack().getId()).steps().getFirst().rotation()).isNull();

        // none chosen: the whole animation plays, the rotation is hidden
        dialog.getSteps().deselectAll();
        assertThat(dialog.getRotationPanel().isVisible()).isFalse();

        // Reset: no rotation
        dialog.getSteps().select(dialog.getSteps().getListDataView().getItems().toList().get(1));
        dialog.updateStepForTest(1, step -> step.withRotation(null));
        assertThat(packService.find(dialog.getPack().getId()).steps().get(1).rotation()).isNull();
        assertThat(dialog.getRotationFields()[1].getValue()).isZero();
    }

    @Test
    void aStepIsMovedInTheAnimationNotTheClip() throws Exception {
        Animation wave = upload("wave.vrma");
        Animation bow = upload("bow.vrma");
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        dialog.getChoice().setValue(Set.of(bow, wave));
        dialog.addChosenForTest();

        // a step chosen shows its translation: typed in metres, or moved with the arrows
        dialog.getSteps().select(dialog.getSteps().getListDataView().getItems().toList().get(1));
        assertThat(dialog.getTranslationFields()[0].getValue()).isZero();
        dialog.rotateForTest(0, 90, 0);
        dialog.translateForTest(0.3, 0, -0.45);
        AnimationStep moved = packService.find(dialog.getPack().getId()).steps().get(1);
        assertThat(moved.translation()).containsExactly(0.3, 0.0, -0.45);
        // the rotation stays as it was
        assertThat(StepRotation.toDegrees(moved.rotation())[1]).isCloseTo(90, within(1e-6));
        assertThat(dialog.getTranslationFields()[2].getValue()).isEqualTo(-0.45);

        // the arrows give any distance: the fields show it to a millimetre, the step keeps it
        dialog.moveWithArrowsForTest(List.of(-0.12345, 1.0, 0.0));
        assertThat(packService.find(dialog.getPack().getId()).steps().get(1).translation()).containsExactly(-0.12345, 1.0, 0.0);
        assertThat(dialog.getTranslationFields()[0].getValue()).isEqualTo(-0.123);
        assertThat(dialog.getTranslationFields()[1].getValue()).isEqualTo(1);
        assertThat(packService.find(dialog.getPack().getId()).steps().getFirst().translation()).isNull();

        // Reset: no translation, the rotation stays
        dialog.updateStepForTest(1, step -> step.withTranslation(null));
        AnimationStep reset = packService.find(dialog.getPack().getId()).steps().get(1);
        assertThat(reset.translation()).isNull();
        assertThat(reset.rotation()).isNotNull();
        assertThat(dialog.getTranslationFields()[1].getValue()).isZero();
    }

    @Test
    void addedClipsAppendTheirDescriptionsAndAPackIsMadeFromAClip() throws Exception {
        Animation wave = upload("wave.vrma");
        wave.setDescription("You wave hello. ");
        wave = animationService.save(wave);
        Animation bow = upload("bow.vrma");
        bow.setDescription("You bow.");
        bow = animationService.save(bow);
        Animation nod = upload("nod.vrma");

        AnimationPackDialog made = part.createPackForTest(wave);
        AnimationPack pack = packService.find(made.getPack().getId());
        assertThat(pack.getName()).isEqualTo("wave");
        assertThat(pack.getDescription()).isEqualTo("You wave hello.");
        assertThat(pack.clipUuids()).containsExactly(wave.getUuid());
        assertThat(made.getDescriptionField().getValue()).isEqualTo("You wave hello.");

        // a clip without a description adds nothing; a clip already in it adds a step, not its text again
        made.getChoice().setValue(Set.of(bow, nod));
        made.addChosenForTest();
        assertThat(made.getDescriptionField().getValue()).isEqualTo("You wave hello.\nYou bow.");
        packService.addClips(made.getPack(), List.of(bow));
        AnimationPack stored = packService.find(pack.getId());
        assertThat(stored.getDescription()).isEqualTo("You wave hello.\nYou bow.");
        assertThat(stored.steps()).hasSize(4);
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
        assertThat(packService.find(greetings.getId()).clipUuids()).containsExactly(bow.getUuid());
    }
}
