package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringChain;
import com.github.enerccio.joi.domain.bodies.SpringBonesTestFiles;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.bodies.SpringBonesPanel;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.google.gson.JsonObject;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The spring bones in the model dialog: the guessed chains, switched, changed, added and applied to the VRM. */
class SpringBonesDialogTest extends PlatformTestBase {

    @Autowired
    private ModelService modelService;
    @Autowired
    private Localization loc;

    private UI ui;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.fbx");
        Files.write(upload.toPath(), new byte[64]);
        model = modelService.attach(new Model(), upload, "Ryuu.fbx");
        File vrm = ModelFiles.uploadFile(modelService.uploadFolder(), "model.vrm");
        SpringBonesTestFiles.vrm(vrm);
        modelService.attachConverted(model, vrm);
        model = modelService.save(model);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private SpringBonesPanel panel() throws Exception {
        ModelDialog dialog = new ModelDialog(modelService.find(model.getId()));
        dialog.create();
        return dialog.getSpringsPanel();
    }

    @Test
    void theGuessedChainsAreSwitchedChangedAddedAndApplied() throws Exception {
        // a model converted before spring bones were written: its chains are guessed, not yet applied
        SpringBonesPanel panel = panel();
        assertThat(panel.isShown()).isTrue();
        assertThat(panel.getChains()).extracting(SpringChain::root).containsExactly("Tail Root", "Hair Root", "Scarf Root");
        assertThat(panel.getStatus().getText()).isEqualTo(loc.getValue(J_L.MSG_SPRING_NOT_APPLIED));
        assertThat(panel.getAddBox().getListDataView().getItems()).contains("Hair A.2").doesNotContain("Hair Root", "Anal Root");

        panel.getChainBox("Tail Root").setValue(false);
        panel.getPresetSelect("Scarf Root").setValue(SpringChain.Preset.STIFF);
        panel.getAddBox().setValue("Hair A.2");
        ComponentUtil.fireEvent(panel.getAddBox(), new AbstractField.ComponentValueChangeEvent<>(panel.getAddBox(), panel.getAddBox(), null, true));
        List<SpringChain> expected = List.of(
                new SpringChain("Tail Root", SpringChain.Preset.TAIL, false),
                new SpringChain("Hair Root", SpringChain.Preset.HAIR, true),
                new SpringChain("Scarf Root", SpringChain.Preset.STIFF, true),
                new SpringChain("Hair A.2", SpringChain.Preset.HAIR, true));
        assertThat(panel.getChains()).isEqualTo(expected);
        // kept on the model before it is applied
        assertThat(SpringChain.listFromJson(modelService.find(model.getId()).getSpringChains())).isEqualTo(expected);

        // snapshots of the VRM before stay current: springs don't change the look
        Model stored = modelService.find(model.getId());
        String before = modelService.bodyHash(stored);
        stored.setSnapshotsHash(before);
        modelService.save(stored);

        panel.getApplyButton().click();
        stored = modelService.find(model.getId());
        assertThat(panel.getStatus().getText()).isEqualTo(loc.getValue(J_L.MSG_SPRING_APPLIED));
        assertThat(stored.getAppliedSpringChains()).isEqualTo(SpringChain.toJson(expected));
        String after = modelService.bodyHash(stored);
        assertThat(after).isNotEqualTo(before);
        assertThat(stored.getSnapshotsHash()).isEqualTo(after);

        JsonObject springs = ModelCapabilities.json(modelService.convertedFile(stored)).getAsJsonObject("extensions")
                .getAsJsonObject("VRMC_springBone");
        // the tail is off, the scarf and the two parts of the hair swing
        assertThat(springs.getAsJsonArray("springs")).extracting(s -> s.getAsJsonObject().get("name").getAsString())
                .containsExactly("Scarf Root", "Hair A.2");

        // guessed again: back to the three chains, to apply
        panel.getGuessButton().click();
        assertThat(panel.getChains()).extracting(SpringChain::enabled).containsOnly(true);
        assertThat(panel.getStatus().getText()).isEqualTo(loc.getValue(J_L.MSG_SPRING_NOT_APPLIED));

        // a new dialog shows what was stored
        assertThat(panel().getChains()).extracting(SpringChain::root).containsExactly("Tail Root", "Hair Root", "Scarf Root");
    }

    @Test
    void aModelWithoutAConvertedVrmHasNoSpringBones() throws Exception {
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "Plain.fbx");
        Files.write(upload.toPath(), new byte[64]);
        Model plain = modelService.attach(new Model(), upload, "Plain.fbx");
        ModelDialog dialog = new ModelDialog(modelService.find(plain.getId()));
        dialog.create();
        assertThat(dialog.getSpringsPanel().isShown()).isFalse();
    }
}
