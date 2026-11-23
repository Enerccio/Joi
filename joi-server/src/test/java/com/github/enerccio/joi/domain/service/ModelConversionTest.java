package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.bodies.ConversionReport;
import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.SpringBonesTestFiles;
import com.github.enerccio.joi.domain.bodies.SpringChain;
import com.github.enerccio.joi.domain.bodies.UnityFixtures;
import com.github.enerccio.joi.domain.collections.ConversionStatus;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.impl.ModelConversionServiceImpl;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conversion of a model with a stand-in for Blender (a shell script that writes what the converter would): the VRM
 * becomes the model's converted file with the report; a failure keeps the converter's error; the user's choices reach
 * the converter's config.
 */
class ModelConversionTest extends PlatformTestBase {

    private static final String FBX = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb1";
    private static final String MAT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2";
    private static final String TEX = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb3";

    @Autowired
    private ModelService modelService;
    @Autowired
    private ModelConversionService conversionService;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        login();
    }

    @AfterEach
    void tearDown() {
        ((ModelConversionServiceImpl) conversionService).setBlender(null);
    }

    /**
     * A Blender that copies its config next to the test (to look at), writes a report and, unless told to fail, the VRM.
     */
    private void fakeBlender(boolean fail) throws Exception {
        fakeBlender(fail, null);
    }

    /** @param vrm what the converter writes; a text that is not a VRM when null */
    private void fakeBlender(boolean fail, File vrm) throws Exception {
        Path script = dir.resolve(fail ? "blender-fail.sh" : "blender.sh");
        String config = dir.resolve("seen-config.json").toString();
        Files.writeString(script, """
                #!/bin/sh
                for last; do :; done
                cp "$last" '%s'
                out=$(sed -E 's/.*"output":"([^"]*)".*/\\1/' "$last")
                report=$(sed -E 's/.*"report":"([^"]*)".*/\\1/' "$last")
                echo "[convert] step import"
                if [ "%s" = "true" ]; then
                  echo '{"meshes":[{"name":"Body","triangles":100,"shapeKeys":2}],"error":"the FBX has no armature: it is not an avatar"}' > "$report"
                  exit 1
                fi
                echo '{"meshes":[{"name":"Body","triangles":100,"shapeKeys":2},{"name":"Gun","triangles":50,"shapeKeys":0,"removed":true}],"materials":[{"name":"Body","unity":"M_Body","by":"meta","textured":true}],"expressions":[{"name":"happy","keys":["Smile"]}],"humanoidMissing":[],"warnings":[],"triangles":100,"springs":3}' > "$report"
                %s
                echo "[convert] done"
                """.formatted(config, fail, vrm == null ? "printf 'glTF-converted' > \"$out\"" : "cp '" + vrm + "' \"$out\""));
        script.toFile().setExecutable(true);
        ((ModelConversionServiceImpl) conversionService).setBlender(script.toString());
    }

    private Model uploadedPackage() throws Exception {
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "Ryuu.unitypackage");
        UnityFixtures.unityPackage(upload,
                new UnityFixtures.Asset(FBX, "Assets/Ryuu/Ryuu.fbx", new byte[64], UnityFixtures.humanoidMeta(FBX, 20, Map.of("Body", MAT))),
                new UnityFixtures.Asset(MAT, "Assets/Ryuu/M_Body.mat", UnityFixtures.material("M_Body", TEX, TEX).getBytes(StandardCharsets.UTF_8),
                        UnityFixtures.meta(MAT)),
                new UnityFixtures.Asset(TEX, "Assets/Ryuu/T_Body.png", new byte[]{1}, UnityFixtures.meta(TEX)));
        return modelService.attach(new Model(), upload, "Ryuu.unitypackage");
    }

    private final List<Boolean> runningAtEnd = new CopyOnWriteArrayList<>();

    private List<String> convertAndWait(Model model) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        conversionService.convert(model, new ModelConversionService.Listener() {
            @Override
            public void line(String line) {
                lines.add(line);
            }

            @Override
            public void finished(Model finished) {
                // the watcher hears of the end when the model is no longer running
                runningAtEnd.add(conversionService.isRunning(finished));
                done.countDown();
            }
        });
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(runningAtEnd).containsOnly(false);
        return new ArrayList<>(lines);
    }

    @Test
    void aConvertedVrmBecomesTheModelsWithTheReport() throws Exception {
        fakeBlender(false);
        Model model = uploadedPackage();
        model.setRemoveMeshes(List.of("Gun"));
        model.setDropShapeKeys(List.of("Some Key"));
        model.setBakeShapeKeys(List.of("Body / Hide Part"));
        model.setMaxTextureSize(1024);
        model = modelService.save(model);

        List<String> lines = convertAndWait(model);
        assertThat(lines).contains("[convert] step import", "[convert] done");

        model = modelService.find(model.getId());
        assertThat(model.getConversionStatus()).isEqualTo(ConversionStatus.DONE);
        assertThat(model.getConversionError()).isNull();
        assertThat(modelService.convertedFile(model)).hasContent("glTF-converted");
        assertThat(model.getConvertedFileSize()).isEqualTo(14L);
        assertThat(model.getConversionLog()).contains("[convert] done");
        ConversionReport report = ConversionReport.parse(model.getConversionReport());
        assertThat(report.meshes()).extracting(ConversionReport.Mesh::name).containsExactly("Body", "Gun");
        assertThat(report.springs()).isEqualTo(3);
        assertThat(conversionService.isRunning(model)).isFalse();

        String config = Files.readString(dir.resolve("seen-config.json"));
        assertThat(config).contains("\"removeMeshes\":[\"Gun\"]", "\"dropShapeKeyParts\":[\"Some Key\"]",
                "\"bakeShapeKeys\":{\"Body\":[\"Hide Part\"]}", "\"maxTextureSize\":1024", "\"materialMap\":{\"Body\":\"Assets/Ryuu/M_Body.mat\"}",
                "Assets/Ryuu/Ryuu.fbx.meta", "\"name\":\"Ryuu\"");

        // a second conversion replaces the VRM
        Long first = modelService.find(model.getId()).getConvertedResourceId();
        convertAndWait(model);
        model = modelService.find(model.getId());
        assertThat(model.getConvertedResourceId()).isNotEqualTo(first);
        assertThat(modelService.convertedFile(model)).hasContent("glTF-converted");
    }

    @Test
    void theSpringBonesAreGuessedAtTheFirstConversionAndKeptAtTheNext() throws Exception {
        fakeBlender(false, SpringBonesTestFiles.vrm(dir.resolve("converted.vrm").toFile()));
        Model model = uploadedPackage();
        List<String> lines = convertAndWait(model);
        assertThat(lines).contains("Spring bones: 3 chains, 3 springs.");
        model = modelService.find(model.getId());
        List<SpringChain> guessed = SpringChain.listFromJson(model.getSpringChains());
        assertThat(guessed).extracting(SpringChain::root).containsExactly("Tail Root", "Hair Root", "Scarf Root");
        assertThat(model.getAppliedSpringChains()).isEqualTo(model.getSpringChains());
        assertThat(ConversionReport.parse(model.getConversionReport()).springs()).isEqualTo(3);
        assertThat(ModelCapabilities.json(modelService.convertedFile(model)).getAsJsonObject("extensions").has("VRMC_springBone")).isTrue();

        // the user's chains survive a new conversion
        model.setSpringChains(SpringChain.toJson(List.of(guessed.get(0).withEnabled(false))));
        model = modelService.save(model);
        lines = convertAndWait(model);
        assertThat(lines).contains("Spring bones: 0 chains, 0 springs.");
        model = modelService.find(model.getId());
        assertThat(SpringChain.listFromJson(model.getSpringChains())).containsExactly(guessed.get(0).withEnabled(false));
        assertThat(ModelCapabilities.json(modelService.convertedFile(model)).getAsJsonObject("extensions").has("VRMC_springBone")).isFalse();
    }

    @Test
    void aFailedConversionKeepsTheConvertersError() throws Exception {
        fakeBlender(true);
        Model model = uploadedPackage();
        convertAndWait(model);
        model = modelService.find(model.getId());
        assertThat(model.getConversionStatus()).isEqualTo(ConversionStatus.FAILED);
        assertThat(model.getConversionError()).isEqualTo("the FBX has no armature: it is not an avatar");
        assertThat(model.getConvertedResourceId()).isNull();
        assertThat(ConversionReport.parse(model.getConversionReport()).meshes()).hasSize(1);
    }

    @Test
    void aMissingBlenderIsSaid() throws Exception {
        ((ModelConversionServiceImpl) conversionService).setBlender(dir.resolve("no-such-blender").toString());
        Model model = uploadedPackage();
        convertAndWait(model);
        assertThat(modelService.find(model.getId()).getConversionError()).startsWith("Blender was not found");
    }

    @Test
    void aModelWithoutAnFbxIsNotConverted() throws Exception {
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "a.vrm");
        Files.writeString(upload.toPath(), "glTF");
        Model model = modelService.attach(new Model(), upload, "a.vrm");
        assertThatThrownBy(() -> conversionService.convert(model, null)).isInstanceOf(IllegalStateException.class);
        assertThat(Set.of()).isEmpty();
    }
}
