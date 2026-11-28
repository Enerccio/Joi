package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.domain.animations.AnimationStep;
import com.github.enerccio.joi.domain.animations.AnimationFolder;
import com.github.enerccio.joi.domain.animations.StepRotation;
import com.github.enerccio.joi.domain.animations.StepTranslation;
import com.github.enerccio.joi.domain.animations.VrmaTestFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationFolderService;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.JoiaService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Animations exported with their clips into a {@code .joia} and imported: clips the user has are linked by uuid, others
 * added; a uuid of another user's clip refuses the file.
 */
class JoiaTest extends PlatformTestBase {

    @Autowired
    private AnimationPackService packService;
    @Autowired
    private AnimationFolderService folderService;
    @Autowired
    private AnimationService animationService;
    @Autowired
    private ModelService modelService;
    @Autowired
    private JoiaService joiaService;

    /** Held here: the current UI is only weakly referenced. */
    private UI ui;
    private User owner;
    private Animation wave;
    private Animation bow;
    private File exported;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        owner = login();
        wave = clip("wave.vrma", "Wave", "You wave hello.");
        bow = clip("bow.vrma", "Bow", "You bow.");

        AnimationPack jacks = new AnimationPack();
        jacks.setName("Greeting");
        jacks.setDescription("You greet.");
        jacks.setLoop(true);
        jacks.setEyes("closed");
        jacks.setExpression("happy");
        jacks.setStepList(List.of(new AnimationStep(wave.getUuid(), false, 1, true, false),
                new AnimationStep(bow.getUuid(), true, 1.5, false, true, StepRotation.fromDegrees(0, 90, 0),
                        StepTranslation.of(0.25, 0, -0.5), 0.5),
                new AnimationStep(wave.getUuid(), false, 1, false, false)));
        jacks = packService.save(jacks);
        AnimationPack bowing = new AnimationPack();
        bowing.setName("Bowing");
        bowing = packService.addClips(packService.save(bowing), List.of(bow));

        AnimationPacksPart part = new AnimationPacksPart();
        part.create();
        part.checkForTest(List.of(jacks, bowing));
        exported = new File(modelService.uploadFolder(), "exported.joia");
        try (OutputStream out = Files.newOutputStream(exported.toPath())) {
            part.exportForTest(out);
        }
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private Animation clip(String fileName, String name, String description) throws Exception {
        File file = VrmaTestFiles.wave(ModelFiles.uploadFile(modelService.uploadFolder(), fileName));
        Animation clip = animationService.add(file, fileName);
        clip.setName(name);
        clip.setDescription(description);
        clip.setTags(List.of("greeting"));
        clip.setLoop(true);
        return animationService.save(clip);
    }

    /** A copy of the export to import (an import deletes its file). */
    private File copy() throws Exception {
        File copy = ModelFiles.uploadFile(modelService.uploadFolder(), "import.joia");
        Files.copy(exported.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    private AnimationPack imported(JoiaService.Imported imported, String name) throws Exception {
        return packService.find(imported.animations().stream().filter(pack -> pack.getName().equals(name)).findFirst().orElseThrow().getId());
    }

    @Test
    void theFileHoldsTheAnimationsAndEachClipOnce() throws Exception {
        JoiaFile.Contents contents = JoiaFile.read(exported, (index, clip) -> null);
        JoiaMetadata meta = contents.meta();
        assertThat(meta.animations()).extracting(JoiaMetadata.Animation::name).containsExactly("Greeting", "Bowing");
        assertThat(meta.clips()).extracting(JoiaMetadata.Clip::uuid).containsExactly(wave.getUuid(), bow.getUuid());
        assertThat(meta.clip(wave.getUuid()).description()).isEqualTo("You wave hello.");
        assertThat(meta.clip(wave.getUuid()).size()).isEqualTo(animationService.file(wave).length());
        JoiaMetadata.Animation greeting = meta.animations().getFirst();
        assertThat(greeting.loop()).isTrue();
        assertThat(greeting.eyes()).isEqualTo("closed");
        assertThat(greeting.expression()).isEqualTo("happy");
        assertThat(meta.animations().get(1).eyes()).isNull();
        assertThat(greeting.steps()).extracting(JoiaMetadata.Step::clip).containsExactly(wave.getUuid(), bow.getUuid(), wave.getUuid());
        assertThat(greeting.steps().get(1).speed()).isEqualTo(1.5);
        assertThat(greeting.steps()).extracting(JoiaMetadata.Step::reverse).containsExactly(false, true, false);
        assertThat(greeting.steps().get(1).rotation()).isEqualTo(StepRotation.fromDegrees(0, 90, 0));
        assertThat(greeting.steps().get(1).translation()).containsExactly(0.25, 0.0, -0.5);
        assertThat(greeting.steps().getFirst().translation()).isNull();
        assertThat(greeting.steps().get(1).blend()).isEqualTo(0.5);
    }

    @Test
    void clipsTheUserHasAreLinkedAndOthersAdded() throws Exception {
        // the same library: the clips are linked, the animations added as new ones
        JoiaService.Imported again = joiaService.importFile(copy());
        assertThat(again.clipsAdded()).isZero();
        assertThat(again.clipsLinked()).isEqualTo(2);
        assertThat(animationService.findAllByName()).hasSize(2);
        AnimationPack greeting = imported(again, "Greeting");
        assertThat(greeting.clipUuids()).containsExactly(wave.getUuid(), bow.getUuid(), wave.getUuid());
        assertThat(greeting.loops()).isTrue();
        assertThat(greeting.getEyes()).isEqualTo("closed");
        assertThat(greeting.getExpression()).isEqualTo("happy");
        assertThat(greeting.steps().get(1).speed()).isEqualTo(1.5);
        assertThat(greeting.steps()).extracting(AnimationStep::reverse).containsExactly(false, true, false);
        assertThat(greeting.steps().get(1).rotation()).isEqualTo(StepRotation.fromDegrees(0, 90, 0));
        assertThat(greeting.steps().get(1).blend()).isEqualTo(0.5);
        assertThat(greeting.steps().getFirst().rotation()).isNull();
        assertThat(greeting.steps().get(1).translation()).containsExactly(0.25, 0.0, -0.5);
        assertThat(greeting.steps().getFirst().translation()).isNull();
        assertThat(packService.findAllByName()).hasSize(4);

        // a deleted clip is added again, with a new uuid (the old one stays its deleted clip's)
        animationService.delete(animationService.find(wave.getId()), false);
        JoiaService.Imported afterDelete = joiaService.importFile(copy());
        assertThat(afterDelete.clipsAdded()).isEqualTo(1);
        assertThat(afterDelete.clipsLinked()).isEqualTo(1);
        Animation newWave = animationService.findAllByName().stream().filter(clip -> clip.getName().equals("Wave")).findFirst().orElseThrow();
        assertThat(newWave.getUuid()).isNotEqualTo(wave.getUuid());
        assertThat(newWave.getDescription()).isEqualTo("You wave hello.");
        assertThat(newWave.getTags()).containsExactly("greeting");
        assertThat(newWave.loops()).isTrue();
        assertThat(animationService.file(newWave)).exists();
        assertThat(imported(afterDelete, "Greeting").clipUuids()).containsExactly(newWave.getUuid(), bow.getUuid(), newWave.getUuid());

        // a uuid no clip has any more is kept: importing the file again links the clip
        animationService.delete(animationService.find(bow.getId()), true);
        JoiaService.Imported afterHardDelete = joiaService.importFile(copy());
        assertThat(afterHardDelete.clipsAdded()).isEqualTo(2);
        assertThat(animationService.findForUser(bow.getUuid())).isNotNull();
    }

    @Test
    void anImportGoesIntoTheFolderOpen() throws Exception {
        AnimationFolder imports = folderService.create(AnimationFolder.Kind.ANIMATIONS, "Imports", null);
        AnimationPacksPart part = new AnimationPacksPart();
        part.create();
        part.getFolders().open(imports.getUuid());
        part.importedForTest(copy(), "import.joia");
        assertThat(folderService.animationsIn(imports.getUuid())).extracting(AnimationPack::getName)
                .containsExactlyInAnyOrder("Greeting", "Bowing");
        // the animations exported stay where they were
        assertThat(folderService.animationsIn(null)).extracting(AnimationPack::getName).containsExactlyInAnyOrder("Greeting", "Bowing");
    }

    @Test
    void aClipOfAnotherUserRefusesTheFile() throws Exception {
        File copy = copy();
        File theirs = new File(modelService.uploadFolder(), "theirs.joia");
        Files.copy(copy.toPath(), theirs.toPath());
        loginAs(createUser());
        assertThatThrownBy(() -> joiaService.importFile(theirs)).isInstanceOf(JoiaService.ForeignClipException.class);
        assertThat(animationService.findAllByName()).isEmpty();
        assertThat(packService.findAllByName()).isEmpty();
        assertThat(theirs).doesNotExist();

        // nor can another user export the owner's animations
        loginAs(owner);
        AnimationPack greeting = packService.findAllByName().stream().filter(pack -> pack.getName().equals("Greeting")).findFirst().orElseThrow();
        loginAs(createUser());
        File out = new File(modelService.uploadFolder(), "out.joia");
        try (OutputStream stream = Files.newOutputStream(out.toPath())) {
            joiaService.export(List.of(greeting), stream);
        }
        assertThat(JoiaFile.read(out, (index, clip) -> null).meta().animations()).isEmpty();
    }
}
