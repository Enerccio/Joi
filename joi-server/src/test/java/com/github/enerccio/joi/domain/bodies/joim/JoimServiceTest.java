package com.github.enerccio.joi.domain.bodies.joim;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.SpringBonesTestFiles;
import com.github.enerccio.joi.domain.bodies.SpringChain;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.JoimService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.protocol.joim.JoimFile;
import com.github.enerccio.joi.ui.dialogs.ModelDialog;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A model's .joim: written when it changed, kept as its resource, and imported as a ready model. */
class JoimServiceTest extends PlatformTestBase {

    @Autowired
    private ModelService modelService;
    @Autowired
    private JoimService joimService;

    private Model model;
    /** Held here: the current UI is only weakly referenced. */
    private UI ui;

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
        model.setAppearance("You are a dragon girl.");
        model.setCapabilityNames(Map.of("mesh:Scarf", "scarf"));
        model.setPartShown(Map.of("mesh:Scarf", "A red scarf."));
        model.setSpringChains(SpringChain.toJson(List.of(new SpringChain("Hair Root", SpringChain.Preset.HAIR, true))));
        model.setDropShapeKeys(List.of("nsfw"));
        model.setMaxTextureSize(4096);
        model = modelService.save(model);
        model.setDescribedHash(modelService.bodyHash(model));
        model = modelService.save(model);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        ui = null;
    }

    @Test
    void theJoimIsWrittenWhenTheModelChangedAndReplacesTheOneBefore() throws Exception {
        assertThat(joimService.needsExport(model)).isTrue();
        AtomicLong bytes = new AtomicLong();
        model = joimService.export(model, bytes::addAndGet);
        File first = modelService.exportFile(model);
        assertThat(first).exists();
        assertThat(bytes.get()).isEqualTo(modelService.bodyFile(model).length());
        assertThat(joimService.needsExport(model)).isFalse();
        assertThat(new ModelDialogProbe().download(model)).isTrue();

        JoimFile.Contents contents = JoimFile.read(first, null, b -> {
        });
        assertThat(contents.meta().getAsJsonObject("model").get("appearance").getAsString()).isEqualTo("You are a dragon girl.");
        assertThat(contents.meta().getAsJsonObject("conversion").get("maxTextureSize").getAsInt()).isEqualTo(4096);
        assertThat(contents.meta().has("exportedAt")).isTrue();

        // a change: written again, in place of the first one
        Model stored = modelService.find(model.getId());
        stored.getCapabilityNames().put("mesh:Scarf", "shawl");
        model = modelService.save(stored);
        assertThat(joimService.needsExport(model)).isTrue();
        Long before = model.getExportResourceId();
        model = joimService.export(model, b -> {
        });
        assertThat(model.getExportResourceId()).isNotEqualTo(before);
        assertThat(modelService.exportFile(model)).exists().isNotEqualTo(first);
        // the one before is gone from the disk, not kept in the trash: a save is not 60 MB more for ever
        assertThat(first).doesNotExist();
        assertThat(joimService.needsExport(model)).isFalse();
    }

    @Test
    void anImportedJoimIsAReadyModel() throws Exception {
        model = joimService.export(model, b -> {
        });
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.joim");
        Files.copy(modelService.exportFile(model).toPath(), upload.toPath());

        Model imported = joimService.importModel(new Model(), upload, "Ryuu.joim", b -> {
        });
        imported = modelService.find(imported.getId());
        assertThat(imported.getId()).isNotEqualTo(model.getId());
        assertThat(imported.getFormat()).isEqualTo(ModelFormat.JOIM);
        assertThat(imported.getName()).isEqualTo("Ryuu");
        assertThat(imported.renderable()).isTrue();
        assertThat(imported.getResource()).isNull();
        assertThat(modelService.bodyHash(imported)).isEqualTo(modelService.bodyHash(model));
        assertThat(imported.getAppearance()).isEqualTo("You are a dragon girl.");
        assertThat(imported.getCapabilityNames()).containsEntry("mesh:Scarf", "scarf");
        assertThat(imported.getPartShown()).containsEntry("mesh:Scarf", "A red scarf.");
        assertThat(SpringChain.listFromJson(imported.getSpringChains())).isEqualTo(SpringChain.listFromJson(model.getSpringChains()));
        assertThat(imported.getDropShapeKeys()).containsExactly("nsfw");
        assertThat(imported.getCapabilities()).isNotNull();
        // the same VRM: the description stays current, and the uploaded file is its export
        assertThat(imported.getDescribedHash()).isEqualTo(modelService.bodyHash(imported));
        assertThat(modelService.exportFile(imported)).isEqualTo(upload);
        assertThat(joimService.needsExport(imported)).isFalse();
        assertThat(modelService.convertedFile(imported)).exists();
    }

    @Test
    void aFileThatIsNoJoimIsRefusedAndDeleted() throws Exception {
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "fake.joim");
        Files.write(upload.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThatThrownBy(() -> joimService.importModel(new Model(), upload, "fake.joim", b -> {
        })).isInstanceOf(IOException.class);
        assertThat(upload).doesNotExist();
        // nor is it attached as a model
        assertThat(ModelFiles.inspect(upload, "fake.joim")).isNull();
    }

    /** The dialog of a model shows the download of its .joim. */
    private static class ModelDialogProbe {
        boolean download(Model model) throws Exception {
            ModelDialog dialog = new ModelDialog(model);
            dialog.create();
            return dialog.getExportDownload() != null;
        }
    }
}
