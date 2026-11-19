package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.service.ResourceService;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model dialog: a new model is stored once it has a name or a file; an upload becomes a resource of the user linked
 * to the model, and a new upload replaces it; a file that is no model is refused and removed.
 */
class ModelDialogTest extends PlatformTestBase {

    @Autowired
    private ModelService modelService;
    @Autowired
    private ResourceService resourceService;

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

    private ModelDialog dialog(Model model) throws Exception {
        ModelDialog dialog = new ModelDialog(model);
        dialog.create();
        return dialog;
    }

    private File upload(String fileName, byte[] content) throws Exception {
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), fileName);
        Files.write(file.toPath(), content);
        return file;
    }

    @Test
    void aNewModelIsStoredOnlyOnceItHasANameOrAFile() throws Exception {
        ModelDialog dialog = dialog(new Model());
        dialog.getNameField().setValue("  ");
        assertThat(modelService.findAllByName()).isEmpty();

        dialog.getNameField().setValue("Ryuu");
        assertThat(modelService.findAllByName()).extracting(Model::getName).containsExactly("Ryuu");
        assertThat(dialog.getModel().getFormat()).isNull();
    }

    @Test
    void anUploadBecomesTheModelsResourceAndANewOneReplacesIt() throws Exception {
        ModelDialog dialog = dialog(new Model());
        File first = upload("Ryuu.vrm", "glTF-first".getBytes());
        dialog.uploadedForTest(first, "Ryuu.vrm");

        Model model = modelService.find(dialog.getModel().getId());
        assertThat(model.getName()).isEqualTo("Ryuu");
        assertThat(model.getFormat()).isEqualTo(ModelFormat.VRM);
        assertThat(model.getMainFile()).isNull();
        assertThat(model.getFileName()).isEqualTo("Ryuu.vrm");
        assertThat(model.getFileSize()).isEqualTo(10L);
        assertThat(modelService.file(model)).hasContent("glTF-first");
        Resource firstResource = resourceService.find(model.getResource().getId());
        assertThat(firstResource.getClazz()).isEqualTo(Model.class.getName());
        assertThat(firstResource.getObjectId()).isEqualTo(model.getId());
        assertThat(firstResource.getMimeType()).isEqualTo("model/gltf-binary");

        File zip = ModelFiles.uploadFile(modelService.uploadFolder(), "pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : new String[]{"pack/body.fbx", "pack/textures/T_Body.png"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write(1);
                out.closeEntry();
            }
        }
        dialog.uploadedForTest(zip, "pack.zip");
        model = modelService.find(model.getId());
        assertThat(model.getName()).isEqualTo("Ryuu");
        assertThat(model.getFormat()).isEqualTo(ModelFormat.FBX);
        assertThat(model.getMainFile()).isEqualTo("pack/body.fbx");
        assertThat(model.getFileCount()).isEqualTo(2);
        assertThat(resourceService.find(firstResource.getId()).isDeleted()).isTrue();
    }

    @Test
    void anUploadedVrmShowsWhatItCanDoAndItsTogglesAreNamed() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("vrm");
        File source = com.github.enerccio.joi.domain.bodies.UnityFixtures.glb(dir.resolve("m.vrm").toFile(), """
                {"nodes": [{"name": "Headgear", "mesh": 0}],
                 "meshes": [{"extras": {"targetNames": ["Hide Halo"]}, "primitives": [{}]}],
                 "extensions": {"VRMC_vrm": {"humanoid": {"humanBones": {"hips": {}}},
                   "expressions": {"preset": {"happy": {"morphTargetBinds": [{}]}}}}}}
                """);
        File file = upload("Mira.vrm", Files.readAllBytes(source.toPath()));
        ModelDialog dialog = dialog(new Model());
        dialog.uploadedForTest(file, "Mira.vrm");

        Model model = modelService.find(dialog.getModel().getId());
        assertThat(model.getCapabilities()).contains("happy", "Hide Halo");
        assertThat(dialog.getToggleRows().getComponentCount()).isEqualTo(2);

        // the second row is the halo: renamed, it is stored; set back to its default name, it is forgotten
        com.vaadin.flow.component.textfield.TextField halo = (com.vaadin.flow.component.textfield.TextField)
                ((com.vaadin.flow.component.orderedlayout.HorizontalLayout) dialog.getToggleRows().getComponentAt(1)).getComponentAt(1);
        assertThat(halo.getValue()).isEqualTo("halo");
        halo.setValue("angel ring");
        assertThat(modelService.find(model.getId()).getCapabilityNames()).isEqualTo(java.util.Map.of("key:Headgear/Hide Halo", "angel ring"));
        halo.setValue("halo");
        assertThat(modelService.find(model.getId()).getCapabilityNames()).isEmpty();
    }

    @Test
    void aFileThatIsNoModelIsRefusedAndRemoved() throws Exception {
        ModelDialog dialog = dialog(new Model());
        File file = upload("notes.txt", "hello".getBytes());
        dialog.uploadedForTest(file, "notes.txt");
        assertThat(file).doesNotExist();
        assertThat(modelService.findAllByName()).isEmpty();
    }
}
