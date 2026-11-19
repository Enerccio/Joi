package com.github.enerccio.joi.domain.bodies;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The spring bones of a VRM: guessed from the bones' names, with colliders, written into the file's JSON only. */
class SpringBonesTest {

    @TempDir
    Path dir;

    /** A small avatar: humanoid bones, hair (a strand and a single bang), a tail, a scarf, a body part, a mesh. */
    static final String NODES = """
            "nodes": [
              {"name": "Hips", "translation": [0, 0.9, 0], "children": [1, 2, 5, 8, 12]},
              {"name": "Spine", "translation": [0, 0.1, 0], "children": [3]},
              {"name": "Tail Root", "children": [15]},
              {"name": "Chest", "translation": [0, 0.1, 0], "children": [4, 14]},
              {"name": "Neck", "translation": [0, 0.15, 0], "children": [6]},
              {"name": "Anal Root", "children": [17]},
              {"name": "Head", "translation": [0, 0.1, 0], "rotation": [0, 0, 0.70710678, 0.70710678], "children": [7]},
              {"name": "Hair Root", "children": [9, 10]},
              {"name": "Leg.L", "translation": [0.1, -0.05, 0], "children": [18]},
              {"name": "Hair A.1", "children": [11]},
              {"name": "Bang"},
              {"name": "Hair A.2", "children": [13]},
              {"name": "Body", "mesh": 0},
              {"name": "Hair A.3"},
              {"name": "Scarf Root", "children": [19]},
              {"name": "Tail.1", "children": [16]},
              {"name": "Tail.2"},
              {"name": "Anal L"},
              {"name": "Knee.L", "translation": [0, -0.4, 0]},
              {"name": "scarf.1", "children": [20]},
              {"name": "scarf.2"}
            ],
            "meshes": [{"primitives": [{}]}]""";

    static final String HUMANOID = """
            "humanoid": {"humanBones": {"hips": {"node": 0}, "spine": {"node": 1}, "chest": {"node": 3}, "neck": {"node": 4},
              "head": {"node": 6}, "leftUpperLeg": {"node": 8}, "leftLowerLeg": {"node": 18}}}""";

    static String vrm1Json() {
        return "{\"asset\": {\"version\": \"2.0\"}, " + NODES + ", \"extensionsUsed\": [\"VRMC_vrm\"], \"extensions\": {\"VRMC_vrm\": {"
                + HUMANOID + "}}}";
    }

    private static SpringBones bones() {
        return SpringBones.of(JsonParser.parseString(vrm1Json()).getAsJsonObject());
    }

    /** A binary glTF with the JSON and a binary chunk. */
    static File glb(File file, String json, byte[] bin) throws IOException {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) / 4 * 4;
        ByteBuffer buffer = ByteBuffer.allocate(20 + padded + 8 + bin.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46546C67).putInt(2).putInt(buffer.capacity()).putInt(padded).putInt(0x4E4F534A).put(text);
        for (int i = text.length; i < padded; i++) {
            buffer.put((byte) ' ');
        }
        buffer.putInt(bin.length).putInt(0x004E4942).put(bin);
        Files.write(file.toPath(), buffer.array());
        return file;
    }

    @Test
    void chainsAreGuessedFromTheNamesBelowTheHumanoidBones() {
        SpringBones bones = bones();
        assertThat(bones.isVrm1()).isTrue();
        List<SpringChain> chains = bones.guess();
        assertThat(chains).containsExactly(
                new SpringChain("Tail Root", SpringChain.Preset.TAIL, true),
                new SpringChain("Hair Root", SpringChain.Preset.HAIR, true),
                new SpringChain("Scarf Root", SpringChain.Preset.CLOTH, true));
        // a holder ("Root") doesn't swing; a single bone (the bang) has no tip to swing to
        assertThat(bones.bones(chains.get(1), chains)).isEqualTo(3);
        assertThat(bones.bones(chains.get(0), chains)).isEqualTo(2);
        // body parts are never offered
        assertThat(bones.candidates()).containsExactly("Tail Root", "Hair Root", "Hair A.1", "Hair A.2", "Scarf Root", "Tail.1", "scarf.1");
        assertThat(bones.presetFor("Hair A.2")).isEqualTo(SpringChain.Preset.HAIR);
        assertThat(bones.presetFor("Unknown")).isEqualTo(SpringChain.Preset.CLOTH);
    }

    @Test
    void aChainInsideAnotherTakesItsPart() {
        SpringBones bones = bones();
        SpringChain hair = new SpringChain("Hair Root", SpringChain.Preset.HAIR, true);
        SpringChain tip = new SpringChain("Hair A.2", SpringChain.Preset.STIFF, true);
        List<SpringChain> chains = List.of(hair, tip);
        assertThat(bones.bones(tip, chains)).isEqualTo(2);
        // what is left of the strand above it is a single bone: nothing swings there
        assertThat(bones.bones(hair, chains)).isZero();
        // switched off, the inner chain gives its part back
        assertThat(bones.bones(hair, List.of(hair, tip.withEnabled(false)))).isEqualTo(3);
    }

    @Test
    void theExtensionHasTheJointsOfEachChainAndColliders() {
        SpringBones bones = bones();
        List<SpringChain> chains = List.of(
                new SpringChain("Hair Root", SpringChain.Preset.HAIR, true),
                new SpringChain("Tail Root", SpringChain.Preset.TAIL, false));
        JsonObject extension = bones.extension(chains);
        assertThat(extension.get("specVersion").getAsString()).isEqualTo("1.0");
        JsonArray springs = extension.getAsJsonArray("springs");
        assertThat(springs).hasSize(1);
        JsonObject spring = springs.get(0).getAsJsonObject();
        assertThat(spring.get("name").getAsString()).isEqualTo("Hair Root");
        assertThat(spring.getAsJsonArray("joints")).extracting(j -> j.getAsJsonObject().get("node").getAsInt()).containsExactly(9, 11, 13);
        JsonObject joint = spring.getAsJsonArray("joints").get(0).getAsJsonObject();
        assertThat(joint.get("stiffness").getAsDouble()).isEqualTo(SpringChain.Preset.HAIR.stiffness);
        assertThat(joint.get("dragForce").getAsDouble()).isEqualTo(SpringChain.Preset.HAIR.drag);
        assertThat(spring.getAsJsonArray("colliderGroups")).containsExactly(new com.google.gson.JsonPrimitive(0));

        JsonArray colliders = extension.getAsJsonArray("colliders");
        // head, neck, chest to neck, spine to chest, hips, the left upper leg
        assertThat(colliders).hasSize(6);
        JsonObject head = colliders.get(0).getAsJsonObject();
        assertThat(head.get("node").getAsInt()).isEqualTo(6);
        // up in the world is the head's own x (its bone is turned a quarter about z)
        double[] offset = values(head.getAsJsonObject("shape").getAsJsonObject("sphere").getAsJsonArray("offset"));
        assertThat(offset[0]).isCloseTo(0.07, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(offset[1]).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-4));
        JsonObject leg = colliders.get(5).getAsJsonObject();
        assertThat(leg.get("node").getAsInt()).isEqualTo(8);
        double[] tail = values(leg.getAsJsonObject("shape").getAsJsonObject("capsule").getAsJsonArray("tail"));
        assertThat(tail[1]).isCloseTo(-0.4, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(extension.getAsJsonArray("colliderGroups").get(0).getAsJsonObject().getAsJsonArray("colliders")).hasSize(6);
    }

    @Test
    void writingReplacesTheSpringsInTheJsonAndKeepsTheBinary() throws Exception {
        byte[] bin = {1, 2, 3, 4, 5, 6, 7, 8};
        String json = vrm1Json().replace("\"VRMC_vrm\": {", "\"VRMC_springBone\": {\"specVersion\": \"1.0\", \"springs\": []}, \"VRMC_vrm\": {")
                .replace("[\"VRMC_vrm\"]", "[\"VRMC_vrm\", \"VRMC_springBone\"]");
        File in = glb(dir.resolve("in.vrm").toFile(), json, bin);
        File out = dir.resolve("out.vrm").toFile();

        SpringBones.Result result = SpringBones.write(in, out, null);
        assertThat(result.chains()).extracting(SpringChain::root).containsExactly("Tail Root", "Hair Root", "Scarf Root");
        assertThat(result.springs()).isEqualTo(3);
        JsonObject written = ModelCapabilities.json(out);
        assertThat(written.getAsJsonObject("extensions").getAsJsonObject("VRMC_springBone").getAsJsonArray("springs")).hasSize(3);
        assertThat(written.getAsJsonArray("extensionsUsed")).extracting(com.google.gson.JsonElement::getAsString)
                .containsExactly("VRMC_vrm", "VRMC_springBone");
        // the VRM's capabilities are still read from it
        assertThat(ModelCapabilities.read(out).vrm()).isEqualTo("1.0");

        byte[] bytes = Files.readAllBytes(out.toPath());
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(header.getInt(8)).isEqualTo(bytes.length);
        assertThat(header.getInt(12) % 4).isZero();
        assertThat(Arrays.copyOfRange(bytes, bytes.length - bin.length, bytes.length)).isEqualTo(bin);
        assertThat(header.getInt(20 + header.getInt(12) + 4)).isEqualTo(0x004E4942);

        // no chain on: the extension is gone
        File none = dir.resolve("none.vrm").toFile();
        assertThat(SpringBones.write(out, none, List.of(new SpringChain("Hair Root", SpringChain.Preset.HAIR, false))).springs()).isZero();
        JsonObject bare = ModelCapabilities.json(none);
        assertThat(bare.getAsJsonObject("extensions").has("VRMC_springBone")).isFalse();
        assertThat(bare.getAsJsonArray("extensionsUsed")).extracting(com.google.gson.JsonElement::getAsString).containsExactly("VRMC_vrm");
    }

    @Test
    void onlyAVrm1IsWritten() throws Exception {
        File glb = glb(dir.resolve("plain.glb").toFile(), "{" + NODES + "}", new byte[4]);
        assertThat(SpringBones.of(ModelCapabilities.json(glb)).guess()).isEmpty();
        assertThatThrownBy(() -> SpringBones.write(glb, dir.resolve("x.vrm").toFile(), null)).isInstanceOf(IOException.class)
                .hasMessageContaining("VRM 1.0");
        File text = dir.resolve("text.vrm").toFile();
        Files.writeString(text.toPath(), "glTF-converted");
        assertThatThrownBy(() -> SpringBones.write(text, dir.resolve("y.vrm").toFile(), null)).isInstanceOf(IOException.class);
    }

    @Test
    void chainsAreStoredAsJson() {
        List<SpringChain> chains = List.of(new SpringChain("Hair Root", SpringChain.Preset.HAIR, true),
                new SpringChain("Tail Root", SpringChain.Preset.STIFF, false));
        assertThat(SpringChain.listFromJson(SpringChain.toJson(chains))).isEqualTo(chains);
        assertThat(SpringChain.listFromJson(SpringChain.toJson(List.of()))).isEmpty();
        assertThat(SpringChain.listFromJson(null)).isNull();
        assertThat(SpringChain.listFromJson("not json")).isNull();
    }

    private static double[] values(JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsDouble();
        }
        return values;
    }
}
