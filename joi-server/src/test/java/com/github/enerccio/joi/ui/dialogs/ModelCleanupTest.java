package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringBonesTestFiles;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.JoimService;
import com.github.enerccio.joi.domain.service.ModelConversionService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cleaning up a model's files: the upload once converted, and the files of older versions. */
class ModelCleanupTest extends PlatformTestBase {

    @Autowired
    private ModelService modelService;
    @Autowired
    private JoimService joimService;
    @Autowired
    private ModelConversionService conversionService;

    /** Held here: the current UI is only weakly referenced. */
    private UI ui;
    private Model model;
    private File upload;
    private File firstVrm;
    private int conversions;

    @BeforeEach
    void setUp() throws Exception {
        ui = new UI();
        UI.setCurrent(ui);
        login();
        upload = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.fbx");
        Files.write(upload.toPath(), new byte[4096]);
        model = modelService.attach(new Model(), upload, "Ryuu.fbx");
        upload = modelService.file(model);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    private void convert() throws Exception {
        File vrm = ModelFiles.uploadFile(modelService.uploadFolder(), "model.vrm");
        // another content every time: the platform shares the file of two resources with the same content, and the first one would stay
        SpringBonesTestFiles.vrm(vrm, 4 + conversions++);
        Model stored = modelService.find(model.getId());
        modelService.attachConverted(stored, vrm);
        model = modelService.save(stored);
    }

    @Test
    void anUnconvertedModelKeepsItsUpload() throws Exception {
        assertThat(modelService.cleanup(model).isEmpty()).isTrue();
        ModelDialog dialog = new ModelDialog(modelService.find(model.getId()));
        dialog.create();
        assertThat(dialog.getCleanupButton()).isNull();
    }

    @Test
    void theUploadAndTheOlderVersionsAreDeletedForGood() throws Exception {
        convert();
        firstVrm = modelService.convertedFile(model);
        convert();
        // the converted VRM a new conversion replaces went with it
        assertThat(firstVrm).doesNotExist();
        model = joimService.export(model, b -> {
        });
        File firstJoim = modelService.exportFile(model);
        Model stored = modelService.find(model.getId());
        stored.setCapabilityNames(Map.of("mesh:Scarf", "shawl"));
        model = joimService.export(modelService.save(stored), b -> {
        });
        File vrm = modelService.convertedFile(model);
        File joim = modelService.exportFile(model);
        // the replaced .joim went with its replacement: it is made again, it is not kept
        assertThat(firstJoim).doesNotExist();

        ModelService.Cleanup cleanup = modelService.cleanup(model);
        assertThat(cleanup.uploadName()).isEqualTo("Ryuu.fbx");
        assertThat(cleanup.uploadBytes()).isEqualTo(4096);
        // only the upload is left to clean up: the older versions are not kept any more
        assertThat(cleanup.oldFiles()).isZero();
        assertThat(cleanup.oldBytes()).isZero();

        ModelDialog dialog = new ModelDialog(modelService.find(model.getId()));
        dialog.create();
        assertThat(dialog.getCleanupButton()).isNotNull();
        dialog.cleanUpForTest();
        assertThat(dialog.getCleanupButton()).isNull();

        assertThat(upload).doesNotExist();
        assertThat(firstVrm).doesNotExist();
        assertThat(vrm).exists();
        assertThat(joim).exists();
        model = modelService.find(model.getId());
        assertThat(model.getUploadRemoved()).isTrue();
        assertThat(model.getResource()).isNull();
        assertThat(model.renderable()).isTrue();
        assertThat(model.fbxToConvert()).isNull();
        assertThat(modelService.cleanup(model).isEmpty()).isTrue();
        // the .joim stays current: what it holds did not change
        assertThat(joimService.needsExport(model)).isFalse();
        assertThatThrownBy(() -> conversionService.convert(model, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upload it again");

        // a new upload brings it back
        File again = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.fbx");
        Files.write(again.toPath(), new byte[64]);
        model = modelService.attach(modelService.find(model.getId()), again, "Ryuu.fbx");
        assertThat(model.getUploadRemoved()).isFalse();
        assertThat(model.fbxToConvert()).isEqualTo("Ryuu.fbx");
    }
}
