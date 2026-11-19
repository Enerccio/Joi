package com.github.enerccio.joi.domain.animations;

import com.github.enerccio.joi.domain.bodies.ModelFiles;
import com.github.enerccio.joi.domain.model.impl.Animation;
import com.github.enerccio.joi.domain.service.AnimationConversionService;
import com.github.enerccio.joi.domain.service.AnimationService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.domain.service.impl.AnimationConversionServiceImpl;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Converting an FBX or a BVH into clips: with a stand-in for Blender, and with the real one when it is installed. */
class AnimationConversionTest extends PlatformTestBase {

    private static final String MAC_BLENDER = "/Applications/Blender.app/Contents/MacOS/Blender";

    @Autowired
    private AnimationConversionService conversionService;
    @Autowired
    private AnimationService animationService;
    @Autowired
    private ModelService modelService;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        login();
    }

    @AfterEach
    void tearDown() {
        ((AnimationConversionServiceImpl) conversionService).setBlender(null);
    }

    /** A Blender that writes two clips (the test's wave) and a report naming their takes, or fails as told. */
    private void fakeBlender(boolean fail) throws Exception {
        File clip = VrmaTestFiles.wave(dir.resolve("wave.vrma").toFile());
        Path script = dir.resolve("blender.sh");
        Files.writeString(script, """
                #!/bin/sh
                for last; do :; done
                out=$(sed -E 's/.*"outputDir":"([^"]*)".*/\\1/' "$last")
                report=$(sed -E 's/.*"report":"([^"]*)".*/\\1/' "$last")
                echo "[convert] step import"
                if [ "%s" = "true" ]; then
                  echo '{"clips":[],"error":"the humanoid bones were not all found: hips"}' > "$report"
                  exit 1
                fi
                mkdir -p "$out"
                cp '%s' "$out/0.vrma"
                cp '%s' "$out/1.vrma"
                echo '{"clips":[{"file":"0.vrma","take":"Wave","frames":61,"fps":30},{"file":"1.vrma","take":"Idle","frames":61,"fps":30}],"humanoidMissing":[],"warnings":[]}' > "$report"
                echo "[convert] done"
                """.formatted(fail, clip, clip));
        script.toFile().setExecutable(true);
        ((AnimationConversionServiceImpl) conversionService).setBlender(script.toString());
    }

    private File upload(String name, byte[] content) throws Exception {
        File file = ModelFiles.uploadFile(modelService.uploadFolder(), name);
        Files.write(file.toPath(), content);
        return file;
    }

    private record Result(List<Animation> added, String error) {
    }

    private Result convertAndWait(File file, String name) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Result> result = new AtomicReference<>();
        conversionService.convert(file, name, (added, error) -> {
            result.set(new Result(new ArrayList<>(added), error));
            done.countDown();
        });
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        return result.get();
    }

    @Test
    void eachTakeBecomesAClip() throws Exception {
        fakeBlender(false);
        File upload = upload("dance.fbx", new byte[64]);
        Result result = convertAndWait(upload, "dance.fbx");
        assertThat(result.error()).isNull();
        assertThat(result.added()).extracting(Animation::getName).containsExactly("dance – Wave", "dance – Idle");
        Animation first = animationService.find(result.added().getFirst().getId());
        assertThat(first.getSource()).isEqualTo("dance.fbx");
        assertThat(first.getTake()).isEqualTo("Wave");
        assertThat(first.getDuration()).isEqualTo(2.0);
        assertThat(animationService.file(first)).exists();
        // the upload is gone once converted
        assertThat(upload).doesNotExist();
    }

    @Test
    void aFailureSaysWhyAndAddsNothing() throws Exception {
        fakeBlender(true);
        File upload = upload("dance.bvh", new byte[64]);
        Result result = convertAndWait(upload, "dance.bvh");
        assertThat(result.error()).contains("hips");
        assertThat(result.added()).isEmpty();
        assertThat(animationService.findAllByName()).isEmpty();
        assertThat(upload).doesNotExist();
    }

    @Test
    void onlyAnFbxOrABvhIsConverted() throws Exception {
        File upload = upload("dance.glb", new byte[64]);
        assertThatThrownBy(() -> conversionService.convert(upload, "dance.glb", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(upload).doesNotExist();
    }

    /** The real converter on a BVH of the tests (a wave): skipped where Blender with the VRM add-on is not installed. */
    @Test
    void blenderConvertsABvh() throws Exception {
        assumeTrue(new File(MAC_BLENDER).canExecute(), "Blender is not installed");
        assumeTrue(Files.isDirectory(Path.of(System.getProperty("user.home"),
                "Library/Application Support/Blender")), "Blender has no add-ons here");
        File upload = ModelFiles.uploadFile(modelService.uploadFolder(), "wave.bvh");
        try (InputStream in = getClass().getResourceAsStream("wave.bvh")) {
            Files.copy(in, upload.toPath());
        }
        Result result = convertAndWait(upload, "wave.bvh");
        assertThat(result.error()).isNull();
        assertThat(result.added()).hasSize(1);
        Animation clip = result.added().getFirst();
        assertThat(clip.getName()).isEqualTo("wave");
        assertThat(clip.getDuration()).isBetween(1.9, 2.1);
        assertThat(clip.getBones()).contains("hips", "rightUpperArm", "rightLowerArm", "head");
        // the BVH's hips rest at the floor: the clip's rest is where they are in the first frame
        com.google.gson.JsonObject json;
        try (InputStream in = Files.newInputStream(animationService.file(clip).toPath())) {
            json = VrmaFiles.json(in);
        }
        int hips = json.getAsJsonObject("extensions").getAsJsonObject("VRMC_vrm_animation").getAsJsonObject("humanoid")
                .getAsJsonObject("humanBones").getAsJsonObject("hips").get("node").getAsInt();
        com.google.gson.JsonArray rest = json.getAsJsonArray("nodes").get(hips).getAsJsonObject().getAsJsonArray("translation");
        double length = 0;
        for (int i = 0; i < 3; i++) {
            length += rest.get(i).getAsDouble() * rest.get(i).getAsDouble();
        }
        assertThat(Math.sqrt(length)).isGreaterThan(1);
    }
}
