package com.github.enerccio.joi.domain.bodies;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a model can do, read from the JSON of its VRM or GLB. */
class ModelCapabilitiesTest {

    @TempDir
    Path dir;

    private static File glb(Path dir, String name, String json) throws IOException {
        return UnityFixtures.glb(dir.resolve(name).toFile(), json);
    }

    private static final String NODES = """
            "nodes": [{"name": "Body", "mesh": 0}, {"name": "Headgear", "mesh": 1}, {"name": "Armature"}],
            "meshes": [
              {"name": "m0", "extras": {"targetNames": ["vrc.v_aa", "Eye - Blink L", "Eye - Pupil - Off"]}, "primitives": [{}]},
              {"name": "m1", "weights": [0, 1, 0], "primitives": [{"extras": {"targetNames": ["Hide Halo", "Side Bangs Off", "Halo Glow"]}}]}
            ]""";

    @Test
    void aVrm1HasItsExpressionsTogglesAndBones() throws Exception {
        File file = glb(dir, "a.vrm", """
                {"asset": {"version": "2.0"}, %s,
                 "extensions": {"VRMC_vrm": {
                   "humanoid": {"humanBones": {"hips": {"node": 2}, "head": {"node": 2}}},
                   "expressions": {
                     "preset": {"happy": {"morphTargetBinds": [{}, {}]}, "aa": {"morphTargetBinds": [{}]},
                                "blinkLeft": {"morphTargetBinds": [{}]}, "lookUp": {}, "neutral": {"morphTargetBinds": []}},
                     "custom": {"blush": {"materialColorBinds": [{}]}}}}}}
                """.formatted(NODES));
        ModelCapabilities capabilities = ModelCapabilities.read(file);
        assertThat(capabilities.vrm()).isEqualTo("1.0");
        assertThat(capabilities.expressions()).containsExactly(
                new ModelCapabilities.Expression("happy", "mood", 2), new ModelCapabilities.Expression("aa", "viseme", 1),
                new ModelCapabilities.Expression("blinkLeft", "blink", 1), new ModelCapabilities.Expression("blush", "custom", 1));
        assertThat(capabilities.toggles()).extracting(ModelCapabilities.Toggle::id).containsExactly(
                "mesh:Body", "key:Body/Eye - Pupil - Off", "mesh:Headgear", "key:Headgear/Hide Halo", "key:Headgear/Side Bangs Off");
        // the model comes with its side bangs hidden
        assertThat(capabilities.toggles()).filteredOn(t -> !t.shown()).extracting(ModelCapabilities.Toggle::key).containsExactly("Side Bangs Off");
        assertThat(capabilities.bones()).containsExactly("hips", "head");
        assertThat(capabilities.missing()).contains("spine", "leftHand").doesNotContain("hips", "head");

        assertThat(ModelCapabilities.fromJson(capabilities.toJson())).isEqualTo(capabilities);
    }

    @Test
    void togglesHavePlainDefaultNames() {
        assertThat(new ModelCapabilities.Toggle("Headgear", "Hide Halo", true, true).defaultName()).isEqualTo("halo");
        assertThat(new ModelCapabilities.Toggle("Headgear", "Side Bangs Off", true, true).defaultName()).isEqualTo("side bangs");
        assertThat(new ModelCapabilities.Toggle("Body", "Eye - Pupil - Off", true, true).defaultName()).isEqualTo("eye pupil");
        assertThat(new ModelCapabilities.Toggle("ShadowX Scarf", null, false, true).defaultName()).isEqualTo("shadowx scarf");
        assertThat(new ModelCapabilities.Toggle("Body", "Off", true, true).defaultName()).isEqualTo("off");
    }

    @Test
    void aVrm0HasItsExpressionsInVrm1Names() throws Exception {
        File file = glb(dir, "old.vrm", """
                {%s, "extensions": {"VRM": {
                   "humanoid": {"humanBones": [{"bone": "hips"}, {"bone": "spine"}]},
                   "blendShapeMaster": {"blendShapeGroups": [
                     {"presetName": "joy", "binds": [{}]}, {"presetName": "a", "binds": [{}]},
                     {"presetName": "blink_l", "binds": [{}]}, {"presetName": "unknown", "name": "Wink", "binds": [{}]},
                     {"presetName": "sorrow", "binds": []}]}}}}
                """.formatted(NODES));
        ModelCapabilities capabilities = ModelCapabilities.read(file);
        assertThat(capabilities.vrm()).isEqualTo("0.x");
        assertThat(capabilities.expressions()).extracting(ModelCapabilities.Expression::name)
                .containsExactly("happy", "aa", "blinkLeft", "Wink");
        assertThat(capabilities.bones()).containsExactly("hips", "spine");
    }

    @Test
    void aPlainGlbHasOnlyToggles() throws Exception {
        ModelCapabilities capabilities = ModelCapabilities.read(glb(dir, "a.glb", "{" + NODES + "}"));
        assertThat(capabilities.vrm()).isNull();
        assertThat(capabilities.expressions()).isEmpty();
        assertThat(capabilities.toggles()).hasSize(5);
        assertThat(capabilities.missing()).isEqualTo(ModelCapabilities.REQUIRED_BONES);
    }

    @Test
    void anythingElseIsRefused() throws Exception {
        File text = Files.writeString(dir.resolve("a.vrm"), "glTF-but-not-really").toFile();
        assertThatThrownBy(() -> ModelCapabilities.read(text)).isInstanceOf(IOException.class);
        assertThat(ModelCapabilities.fromJson("{broken")).isNull();
    }
}
