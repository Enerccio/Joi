package com.github.enerccio.joi.domain.animations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a VRMA file (VRM Animation: a binary glTF with the {@code VRMC_vrm_animation} extension, see
 * {@code DESIGN.md} → Bodies, animations): only its JSON chunk, never the motion, so a large clip is not read into
 * memory.
 */
public final class VrmaFiles {

    /** The file extension of a VRM Animation. */
    public static final String EXTENSION = "vrma";

    /** What a VRMA file is served as. */
    public static final String CONTENT_TYPE = "model/gltf-binary";

    private static final int GLB_MAGIC = 0x46546C67;   // "glTF"
    private static final int JSON_CHUNK = 0x4E4F534A;  // "JSON"
    private static final int MAX_JSON = 16 * 1024 * 1024;
    private static final String EXTENSION_NAME = "VRMC_vrm_animation";

    private VrmaFiles() {
    }

    /**
     * What was read from a clip.
     *
     * @param duration    its length in seconds (the last key of any of its animations)
     * @param bones       the humanoid bones it moves (VRM names), in the order of the extension
     * @param expressions whether it moves expressions too
     */
    public record Inspection(double duration, List<String> bones, boolean expressions) {
    }

    /** Whether the file name is a VRMA's. */
    public static boolean isVrma(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith("." + EXTENSION);
    }

    /**
     * Reads the clip.
     *
     * @return what it has, null when it is no VRMA (not a binary glTF, or without the extension)
     */
    public static Inspection inspect(File file) throws IOException {
        JsonObject json;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            json = json(in);
        }
        if (json == null) {
            return null;
        }
        try {
            return inspect(json);
        } catch (RuntimeException e) {
            // a value of the wrong kind (a node that is no number): not a clip that can be played
            return null;
        }
    }

    /** The JSON chunk of a binary glTF, null when the stream is not one. */
    static JsonObject json(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        try {
            if (Integer.reverseBytes(in.readInt()) != GLB_MAGIC) {
                return null;
            }
            in.readInt(); // version
            in.readInt(); // total length
            int length = Integer.reverseBytes(in.readInt());
            int type = Integer.reverseBytes(in.readInt());
            if (type != JSON_CHUNK || length <= 0 || length > MAX_JSON) {
                return null;
            }
            byte[] chunk = new byte[length];
            in.readFully(chunk);
            JsonElement element = JsonParser.parseString(new String(chunk, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (EOFException | RuntimeException e) {
            return null;
        }
    }

    static Inspection inspect(JsonObject json) {
        JsonObject extension = object(object(json, "extensions"), EXTENSION_NAME);
        if (extension == null) {
            return null;
        }
        // the humanoid bones by the node they are on
        Map<Integer, String> boneByNode = new HashMap<>();
        JsonObject humanBones = object(object(extension, "humanoid"), "humanBones");
        List<String> order = new ArrayList<>();
        if (humanBones != null) {
            humanBones.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("node")) {
                    boneByNode.put(entry.getValue().getAsJsonObject().get("node").getAsInt(), entry.getKey());
                    order.add(entry.getKey());
                }
            });
        }
        JsonObject expressionsObject = object(extension, "expressions");
        boolean expressions = expressionsObject != null
                && (nonEmpty(object(expressionsObject, "preset")) || nonEmpty(object(expressionsObject, "custom")));

        JsonArray accessors = array(json, "accessors");
        double duration = 0;
        Set<String> moved = new LinkedHashSet<>();
        JsonArray animations = array(json, "animations");
        if (animations != null) {
            for (JsonElement animationElement : animations) {
                if (!animationElement.isJsonObject()) {
                    continue;
                }
                JsonObject animation = animationElement.getAsJsonObject();
                JsonArray samplers = array(animation, "samplers");
                if (samplers != null && accessors != null) {
                    for (JsonElement sampler : samplers) {
                        duration = Math.max(duration, lastKey(accessors, sampler));
                    }
                }
                JsonArray channels = array(animation, "channels");
                if (channels != null) {
                    for (JsonElement channel : channels) {
                        JsonObject target = channel.isJsonObject() ? object(channel.getAsJsonObject(), "target") : null;
                        if (target != null && target.has("node")) {
                            String bone = boneByNode.get(target.get("node").getAsInt());
                            if (bone != null) {
                                moved.add(bone);
                            }
                        }
                    }
                }
            }
        }
        List<String> bones = new ArrayList<>();
        for (String bone : order) {
            if (moved.contains(bone)) {
                bones.add(bone);
            }
        }
        return new Inspection(duration, bones, expressions);
    }

    /** The time of the last key of a sampler: the max of its input accessor. */
    private static double lastKey(JsonArray accessors, JsonElement sampler) {
        if (!sampler.isJsonObject() || !sampler.getAsJsonObject().has("input")) {
            return 0;
        }
        int input = sampler.getAsJsonObject().get("input").getAsInt();
        if (input < 0 || input >= accessors.size() || !accessors.get(input).isJsonObject()) {
            return 0;
        }
        JsonArray max = array(accessors.get(input).getAsJsonObject(), "max");
        return max == null || max.isEmpty() ? 0 : max.get(0).getAsDouble();
    }

    private static boolean nonEmpty(JsonObject object) {
        return object != null && !object.isEmpty();
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }
}
