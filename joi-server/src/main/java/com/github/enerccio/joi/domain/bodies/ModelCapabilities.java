package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a model can do, read from its VRM (1.0 or 0.x) or GLB (the glTF JSON chunk only, the binary is not read):
 * <ul>
 *     <li><b>expressions</b>: the VRM's presets that move something (moods, blink, visemes) and its custom ones;</li>
 *     <li><b>toggles</b>: each mesh (shown or hidden) and each shape key that shows or hides a part ("Hide Halo",
 *     "Side Bangs Off", "Nail Remove");</li>
 *     <li>the <b>humanoid bones</b> it maps, and the ones a body needs that it does not.</li>
 * </ul>
 * Each capability has an id that stays the same across conversions of the same avatar (by the names in the model), so
 * the names the user gives them are kept. See {@code DESIGN.md} → Bodies.
 *
 * @param vrm         the VRM version (1.0, 0.x), null for a plain glTF
 * @param expressions the expressions
 * @param toggles     the meshes and the toggle keys
 * @param bones       the humanoid bones it maps (VRM names)
 * @param missing     the bones a body needs that it does not map
 */
public record ModelCapabilities(String vrm, List<Expression> expressions, List<Toggle> toggles, List<String> bones,
                                List<String> missing) {

    private static final Gson GSON = new Gson();

    /** A shape key that shows or hides a part. The same rule as the converter's, which keeps them. */
    public static final Pattern TOGGLE_KEY = Pattern.compile("\\b(hide|remove|off|toggle)\\b", Pattern.CASE_INSENSITIVE);

    /** The bones a body needs to be animated: VRM's required humanoid bones. */
    static final List<String> REQUIRED_BONES = List.of("hips", "spine", "head", "leftUpperArm", "leftLowerArm", "leftHand",
            "rightUpperArm", "rightLowerArm", "rightHand", "leftUpperLeg", "leftLowerLeg", "leftFoot", "rightUpperLeg",
            "rightLowerLeg", "rightFoot");

    private static final Set<String> MOODS = Set.of("happy", "angry", "sad", "relaxed", "surprised", "neutral");
    private static final Set<String> VISEMES = Set.of("aa", "ih", "ou", "ee", "oh");

    /**
     * @param name   the VRM name ({@code happy}, {@code blink}, a custom name)
     * @param kind   mood, blink, viseme, look or custom
     * @param keys   how many shape keys (or material changes) it moves
     */
    public record Expression(String name, String kind, int keys) {

        public String id() {
            return "expression:" + name;
        }
    }

    /**
     * @param mesh  the mesh (its node's name)
     * @param key   the shape key, null when the toggle is the mesh itself
     * @param hides whether the key at full weight hides the part (the part is shown at 0); false for a mesh
     * @param shown whether the part is shown as the model comes (a key's default weight is in the mesh)
     */
    public record Toggle(String mesh, String key, boolean hides, boolean shown) {

        public String id() {
            return key == null ? "mesh:" + mesh : "key:" + mesh + "/" + key;
        }

        /** What the toggle is called until the user names it: "Hide Halo" → "halo", "ShadowX Scarf" → "shadowx scarf". */
        public String defaultName() {
            String source = key == null ? mesh : TOGGLE_KEY.matcher(key).replaceAll(" ");
            String name = source.replaceAll("[-_.]+", " ").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            return name.isEmpty() ? (key == null ? mesh : key).toLowerCase(Locale.ROOT) : name;
        }
    }

    /** The capabilities as stored on the model (JSON). */
    public String toJson() {
        return GSON.toJson(this);
    }

    /** The capabilities in the JSON, null when there are none or they can't be read. */
    public static ModelCapabilities fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return GSON.fromJson(json, ModelCapabilities.class);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /**
     * Reads the capabilities of a binary glTF (a .vrm or a .glb).
     *
     * @throws IOException when the file is not a binary glTF
     */
    public static ModelCapabilities read(File file) throws IOException {
        JsonObject gltf = json(file);
        JsonObject extensions = object(gltf, "extensions");
        if (extensions.has("VRMC_vrm")) {
            return readVrm1(gltf, extensions.getAsJsonObject("VRMC_vrm"));
        }
        if (extensions.has("VRM")) {
            return readVrm0(gltf, extensions.getAsJsonObject("VRM"));
        }
        return new ModelCapabilities(null, List.of(), toggles(gltf), List.of(), REQUIRED_BONES);
    }

    private static ModelCapabilities readVrm1(JsonObject gltf, JsonObject vrm) {
        List<Expression> expressions = new ArrayList<>();
        JsonObject all = object(vrm, "expressions");
        for (String group : List.of("preset", "custom")) {
            JsonObject named = object(all, group);
            for (String name : named.keySet()) {
                JsonObject expression = named.getAsJsonObject(name);
                int keys = size(expression, "morphTargetBinds") + size(expression, "materialColorBinds")
                        + size(expression, "textureTransformBinds");
                if (keys > 0) {
                    expressions.add(new Expression(name, group.equals("custom") ? "custom" : kind(name), keys));
                }
            }
        }
        List<String> bones = new ArrayList<>(object(object(vrm, "humanoid"), "humanBones").keySet());
        return new ModelCapabilities("1.0", expressions, toggles(gltf), bones, missing(bones));
    }

    private static ModelCapabilities readVrm0(JsonObject gltf, JsonObject vrm) {
        List<Expression> expressions = new ArrayList<>();
        for (JsonElement element : array(object(vrm, "blendShapeMaster"), "blendShapeGroups")) {
            JsonObject group = element.getAsJsonObject();
            String preset = string(group, "presetName");
            String name = preset == null || preset.equals("unknown") ? string(group, "name") : vrm0Name(preset);
            int keys = size(group, "binds") + size(group, "materialValues");
            if (name != null && keys > 0) {
                expressions.add(new Expression(name, preset == null || preset.equals("unknown") ? "custom" : kind(name), keys));
            }
        }
        List<String> bones = new ArrayList<>();
        for (JsonElement element : array(object(vrm, "humanoid"), "humanBones")) {
            String bone = string(element.getAsJsonObject(), "bone");
            if (bone != null) {
                bones.add(bone);
            }
        }
        return new ModelCapabilities("0.x", expressions, toggles(gltf), bones, missing(bones));
    }

    /** VRM 0.x preset names in VRM 1.0's ({@code joy} → {@code happy}, {@code a} → {@code aa}). */
    private static String vrm0Name(String preset) {
        return switch (preset) {
            case "joy" -> "happy";
            case "sorrow" -> "sad";
            case "fun" -> "relaxed";
            case "a" -> "aa";
            case "i" -> "ih";
            case "u" -> "ou";
            case "e" -> "ee";
            case "o" -> "oh";
            case "blink_l" -> "blinkLeft";
            case "blink_r" -> "blinkRight";
            case "lookup" -> "lookUp";
            case "lookdown" -> "lookDown";
            case "lookleft" -> "lookLeft";
            case "lookright" -> "lookRight";
            default -> preset;
        };
    }

    private static String kind(String name) {
        if (MOODS.contains(name)) {
            return "mood";
        }
        if (VISEMES.contains(name)) {
            return "viseme";
        }
        if (name.startsWith("blink")) {
            return "blink";
        }
        if (name.startsWith("look")) {
            return "look";
        }
        return "custom";
    }

    /** Each node with a mesh, and the toggle keys of its mesh. */
    private static List<Toggle> toggles(JsonObject gltf) {
        List<Toggle> toggles = new ArrayList<>();
        JsonArray meshes = array(gltf, "meshes");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : array(gltf, "nodes")) {
            JsonObject node = element.getAsJsonObject();
            if (!node.has("mesh")) {
                continue;
            }
            int index = node.get("mesh").getAsInt();
            JsonObject mesh = index >= 0 && index < meshes.size() ? meshes.get(index).getAsJsonObject() : new JsonObject();
            String name = string(node, "name") != null ? string(node, "name") : string(mesh, "name");
            if (name == null || !seen.add(name)) {
                continue;
            }
            toggles.add(new Toggle(name, null, false, true));
            List<String> keys = targetNames(mesh);
            JsonArray weights = array(mesh, "weights");
            for (int i = 0; i < keys.size(); i++) {
                if (TOGGLE_KEY.matcher(keys.get(i)).find()) {
                    double weight = i < weights.size() ? weights.get(i).getAsDouble() : 0;
                    toggles.add(new Toggle(name, keys.get(i), true, weight < 0.5));
                }
            }
        }
        return toggles;
    }

    /** The names of a mesh's morph targets: the mesh's extras (or its first primitive's), as Blender writes them. */
    private static List<String> targetNames(JsonObject mesh) {
        JsonArray names = array(object(mesh, "extras"), "targetNames");
        if (names.isEmpty()) {
            JsonArray primitives = array(mesh, "primitives");
            if (!primitives.isEmpty()) {
                names = array(object(primitives.get(0).getAsJsonObject(), "extras"), "targetNames");
            }
        }
        List<String> list = new ArrayList<>();
        for (JsonElement name : names) {
            list.add(name.getAsString());
        }
        return list;
    }

    private static List<String> missing(List<String> bones) {
        return REQUIRED_BONES.stream().filter(bone -> !bones.contains(bone)).toList();
    }

    /** The JSON chunk of a binary glTF. */
    static JsonObject json(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath()); DataInputStream data = new DataInputStream(in)) {
            byte[] header = data.readNBytes(20);
            if (header.length < 20) {
                throw new IOException("not a binary glTF: " + file.getName());
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buffer.getInt(0);
            int chunkLength = buffer.getInt(12);
            int chunkType = buffer.getInt(16);
            if (magic != 0x46546C67 || chunkType != 0x4E4F534A || chunkLength <= 0 || chunkLength > 256 * 1024 * 1024) {
                throw new IOException("not a binary glTF: " + file.getName());
            }
            byte[] json = data.readNBytes(chunkLength);
            try {
                return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new IOException("the glTF JSON can't be read: " + file.getName(), e);
            }
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static int size(JsonObject parent, String name) {
        return array(parent, name).size();
    }

    private static String string(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
