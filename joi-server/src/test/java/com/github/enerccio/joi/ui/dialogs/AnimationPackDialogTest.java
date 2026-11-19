package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.animations.VrmaTestFiles;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringBonesTestFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ModelService;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Animation packs: made in their dialog, clips added by upload and edited, previewed on a chosen body. */
class AnimationPackDialogTest extends PlatformTestBase {

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
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private File clip(String name) throws Exception {
        return VrmaTestFiles.wave(ModelFiles.uploadFile(modelService.uploadFolder(), name));
    }

    private List<Animation> rows(AnimationPackDialog dialog) {
        return dialog.getClips().getDataProvider().fetch(new Query<>()).toList();
    }

    @Test
    void aNewPackIsStoredWithItsFirstClip() throws Exception {
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        assertThat(dialog.getPack().getId()).isNull();
        assertThat(dialog.getBodySelect().getListDataView().getItems().toList()).containsExactly(body.getId());
        assertThat(dialog.getBodySelect().getValue()).isEqualTo(body.getId());

        dialog.uploadedForTest(clip("Wave hello.vrma"), "Wave hello.vrma");
        AnimationPack pack = packService.find(dialog.getPack().getId());
        assertThat(pack.getPreviewBodyId()).isEqualTo(body.getId());
        List<Animation> clips = rows(dialog);
        assertThat(clips).hasSize(1);
        Animation clip = clips.getFirst();
        assertThat(clip.getName()).isEqualTo("Wave hello");
        assertThat(clip.getDuration()).isEqualTo(2.0);
        assertThat(clip.getBones()).containsExactly("rightUpperArm", "rightLowerArm");
        assertThat(animationService.file(clip)).exists();
        assertThat(packService.findAllByName()).extracting(AnimationPack::getId).containsExactly(pack.getId());
    }

    @Test
    void theChosenClipIsEditedInPlace() throws Exception {
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        dialog.getNameField().setValue("Greetings");
        dialog.uploadedForTest(clip("wave.vrma"), "wave.vrma");
        // the clip just added is the one chosen
        dialog.getClipName().setValue("Wave");
        dialog.getClipTags().setValue("Hello, wave, hello ,");
        dialog.getClipLoop().setValue(true);

        Animation stored = animationService.findByPack(dialog.getPack()).getFirst();
        assertThat(stored.getName()).isEqualTo("Wave");
        assertThat(stored.getTags()).containsExactly("hello", "wave");
        assertThat(stored.loops()).isTrue();
        assertThat(stored.speedOrDefault()).isEqualTo(1.0);

        AnimationPackDialog reopened = new AnimationPackDialog(packService.find(dialog.getPack().getId()));
        reopened.create();
        assertThat(rows(reopened)).extracting(Animation::getName).containsExactly("Wave");
        assertThat(reopened.getBodySelect().getValue()).isEqualTo(body.getId());
    }

    @Test
    void anythingButAVrmaIsRefused() throws Exception {
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        File fake = ModelFiles.uploadFile(modelService.uploadFolder(), "dance.vrma");
        Files.writeString(fake.toPath(), "not a clip");
        dialog.uploadedForTest(fake, "dance.vrma");
        File fbx = ModelFiles.uploadFile(modelService.uploadFolder(), "dance.fbx");
        Files.writeString(fbx.toPath(), "an fbx");
        dialog.uploadedForTest(fbx, "dance.fbx");
        assertThat(fake).doesNotExist();
        assertThat(fbx).doesNotExist();
        assertThat(rows(dialog)).isEmpty();
    }

    @Test
    void deletingAPackDeletesItsClips() throws Exception {
        AnimationPackDialog dialog = new AnimationPackDialog(new AnimationPack());
        dialog.create();
        dialog.getNameField().setValue("Idles");
        dialog.uploadedForTest(clip("a.vrma"), "a.vrma");
        dialog.uploadedForTest(clip("b.vrma"), "b.vrma");
        AnimationPack pack = packService.find(dialog.getPack().getId());
        assertThat(animationService.countByPack(pack)).isEqualTo(2);

        packService.deleteWithClips(pack);
        assertThat(packService.findAllByName()).isEmpty();
        assertThat(animationService.findByPack(pack)).isEmpty();
    }
}
