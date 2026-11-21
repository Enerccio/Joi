package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The spring bones of a VRM 1.0 ({@code VRMC_springBone}): the bones that swing as she moves. The chains are guessed
 * from the names of the bones (hair, tail, ears, cloth; never the humanoid bones, nor body parts that jiggle), the user
 * changes them in the model dialog ({@link SpringChain}), and they are written into the VRM with colliders on her head,
 * body and limbs, so hair falls around the head and a scarf over the chest. Only the JSON of the file is rewritten; its
 * binary (the meshes, the textures) is copied as it is.
 */
public final class SpringBones {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int GLTF = 0x46546C67;
    private static final int JSON = 0x4E4F534A;
    private static final String EXTENSION = "VRMC_springBone";

    /** Bones never guessed nor offered: body parts and the face, which aren't made to swing (or only on VRChat). */
    private static final Pattern EXCLUDED = Pattern.compile(
            "anal|butt|boob|breast|bust|nipple|vagina|penis|pussy|genital|womb|clit|jiggle|tummy|belly|hip.?dip|twist|tongue|mouth"
                    + "|cheek|toe|finger|eye|teeth|jaw|brow");
    private static final Pattern HAIR = Pattern.compile("hair|bang|fringe|twin|pony|braid|ahoge|side.?burn");
    private static final Pattern TAIL = Pattern.compile("tail");
    private static final Pattern EARS = Pattern.compile("(^|[^a-z])ears?([^a-z]|$)");
    private static final Pattern CLOTH = Pattern.compile(
            "scarf|ribbon|skirt|cloth|cape|coat|sleeve|tie([^a-z]|$)|strap|bow([^a-z]|$)|tassel|dress|hood|frill|cord|rope|earring"
                    + "|pendant|necklace|belt|apron|robe|veil|cloak");
    /** A bone that only holds others ("Hair Root", "String Base"): its children start the springs, it doesn't swing. */
    private static final Pattern HOLDER = Pattern.compile("(^|[^a-z])(root|base)([^a-z]|$)");

    private final JsonObject gltf;
    private final List<JsonObject> nodes = new ArrayList<>();
    private final int[] parent;
    private final Set<Integer> humanoid = new HashSet<>();
    private final Map<String, Integer> humanBones = new HashMap<>();
    private final Map<String, Integer> byName = new LinkedHashMap<>();
    private final boolean vrm1;
    private final Map<Integer, double[]> worlds = new HashMap<>();

    private SpringBones(JsonObject gltf) {
        this.gltf = gltf;
        for (JsonElement node : array(gltf, "nodes")) {
            nodes.add(node.isJsonObject() ? node.getAsJsonObject() : new JsonObject());
        }
        parent = new int[nodes.size()];
        java.util.Arrays.fill(parent, -1);
        for (int i = 0; i < nodes.size(); i++) {
            for (JsonElement child : array(nodes.get(i), "children")) {
                int c = child.getAsInt();
                if (c >= 0 && c < nodes.size() && !above(c, i)) {
                    parent[c] = i;
                }
            }
            String name = string(nodes.get(i), "name");
            if (name != null) {
                byName.putIfAbsent(name, i);
            }
        }
        JsonObject vrm = object(object(gltf, "extensions"), "VRMC_vrm");
        vrm1 = object(gltf, "extensions").has("VRMC_vrm");
        JsonObject bones = object(object(vrm, "humanoid"), "humanBones");
        for (String bone : bones.keySet()) {
            JsonElement node = object(bones, bone).get("node");
            if (node != null && node.isJsonPrimitive()) {
                humanoid.add(node.getAsInt());
                humanBones.put(bone, node.getAsInt());
            }
        }
    }

    /** The bones of the VRM (only its JSON is read). */
    public static SpringBones read(File file) throws IOException {
        return new SpringBones(ModelCapabilities.json(file));
    }

    /** The bones of a glTF JSON. */
    public static SpringBones of(JsonObject gltf) {
        return new SpringBones(gltf);
    }

    /** Whether the file is a VRM 1.0, the only kind spring bones are written into. */
    public boolean isVrm1() {
        return vrm1;
    }

    /** The chains guessed from the names of the bones, in the order of the bone tree. */
    public List<SpringChain> guess() {
        List<SpringChain> chains = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            SpringChain.Preset preset = ownPreset(i);
            if (preset == null || !candidate(i)) {
                continue;
            }
            int p = parent[i];
            boolean top = p < 0 || humanoid.contains(p) || presetOf(p) == null;
            if (top && !springs(i, Set.of()).isEmpty()) {
                chains.add(new SpringChain(name(i), preset, true));
            }
        }
        return chains;
    }

    /** The bones a chain can start from: below the humanoid bones, with bones under them, not excluded. */
    public List<String> candidates() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (candidate(i) && !kids(i, Set.of()).isEmpty() && name(i) != null) {
                names.add(name(i));
            }
        }
        return names;
    }

    /** The preset a chain from the bone would have by its name (or a bone above it), cloth when nothing says. */
    public SpringChain.Preset presetFor(String bone) {
        Integer node = byName.get(bone);
        SpringChain.Preset preset = node == null ? null : presetOf(node);
        return preset == null ? SpringChain.Preset.CLOTH : preset;
    }

    /** How many bones swing in the chain, given the other chains (a chain inside it takes its own part). */
    public int bones(SpringChain chain, List<SpringChain> all) {
        Integer root = byName.get(chain.root());
        if (root == null) {
            return 0;
        }
        return springs(root, stops(all, chain)).stream().mapToInt(List::size).sum();
    }

    /** The {@code VRMC_springBone} extension for the chains: the enabled ones with their joints, and the colliders. */
    public JsonObject extension(List<SpringChain> chains) {
        JsonObject extension = new JsonObject();
        extension.addProperty("specVersion", "1.0");
        JsonArray colliders = colliders();
        JsonArray groups = new JsonArray();
        if (!colliders.isEmpty()) {
            JsonObject group = new JsonObject();
            group.addProperty("name", "body");
            JsonArray members = new JsonArray();
            for (int i = 0; i < colliders.size(); i++) {
                members.add(i);
            }
            group.add("colliders", members);
            groups.add(group);
            extension.add("colliders", colliders);
            extension.add("colliderGroups", groups);
        }
        JsonArray springs = new JsonArray();
        for (SpringChain chain : chains) {
            Integer root = byName.get(chain.root());
            if (!chain.enabled() || root == null) {
                continue;
            }
            for (List<Integer> joints : springs(root, stops(chains, chain))) {
                JsonObject spring = new JsonObject();
                spring.addProperty("name", chain.root());
                JsonArray list = new JsonArray();
                for (int node : joints) {
                    JsonObject joint = new JsonObject();
                    joint.addProperty("node", node);
                    joint.addProperty("hitRadius", chain.preset().radius * scale());
                    joint.addProperty("stiffness", chain.preset().stiffness);
                    joint.addProperty("gravityPower", chain.preset().gravity);
                    JsonArray down = new JsonArray();
                    down.add(0);
                    down.add(-1);
                    down.add(0);
                    joint.add("gravityDir", down);
                    joint.addProperty("dragForce", chain.preset().drag);
                    list.add(joint);
                }
                spring.add("joints", list);
                if (!groups.isEmpty()) {
                    JsonArray used = new JsonArray();
                    used.add(0);
                    spring.add("colliderGroups", used);
                }
                springs.add(spring);
            }
        }
        extension.add("springs", springs);
        return extension;
    }

    /** What was written: the chains and the number of springs (one per straight run of bones). */
    public record Result(List<SpringChain> chains, int springs) {
    }

    /**
     * Copies the VRM with the chains as its spring bones (the ones it had are replaced; none when no chain is on).
     *
     * @param chains the chains, null to guess them
     * @throws IOException when the file is not a VRM 1.0
     */
    public static Result write(File in, File out, List<SpringChain> chains) throws IOException {
        try (InputStream raw = Files.newInputStream(in.toPath()); DataInputStream data = new DataInputStream(new BufferedInputStream(raw))) {
            byte[] header = data.readNBytes(20);
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (header.length < 20 || buffer.getInt(0) != GLTF || buffer.getInt(16) != JSON) {
                throw new IOException("not a binary glTF: " + in.getName());
            }
            int version = buffer.getInt(4);
            int jsonLength = buffer.getInt(12);
            if (jsonLength <= 0 || jsonLength > 256 * 1024 * 1024) {
                throw new IOException("not a binary glTF: " + in.getName());
            }
            JsonObject gltf;
            try {
                gltf = JsonParser.parseString(new String(data.readNBytes(jsonLength), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new IOException("the glTF JSON can't be read: " + in.getName(), e);
            }
            SpringBones bones = new SpringBones(gltf);
            if (!bones.isVrm1()) {
                throw new IOException("not a VRM 1.0: " + in.getName());
            }
            List<SpringChain> used = chains == null ? bones.guess() : chains;
            JsonObject extension = bones.extension(used);
            int springs = extension.getAsJsonArray("springs").size();
            JsonObject extensions = object(gltf, "extensions");
            JsonArray usedExtensions = gltf.has("extensionsUsed") && gltf.get("extensionsUsed").isJsonArray()
                    ? gltf.getAsJsonArray("extensionsUsed") : new JsonArray();
            usedExtensions.remove(new com.google.gson.JsonPrimitive(EXTENSION));
            if (springs > 0) {
                extensions.add(EXTENSION, extension);
                usedExtensions.add(EXTENSION);
            } else {
                extensions.remove(EXTENSION);
            }
            gltf.add("extensions", extensions);
            gltf.add("extensionsUsed", usedExtensions);

            byte[] json = GSON.toJson(gltf).getBytes(StandardCharsets.UTF_8);
            int padded = (json.length + 3) / 4 * 4;
            long rest = in.length() - 20 - jsonLength;
            if (rest < 0) {
                throw new IOException("the glTF is cut short: " + in.getName());
            }
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(out.toPath()))) {
                ByteBuffer head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
                head.putInt(GLTF).putInt(version).putInt((int) (20 + padded + rest)).putInt(padded).putInt(JSON);
                file.write(head.array());
                file.write(json);
                for (int i = json.length; i < padded; i++) {
                    file.write(' ');
                }
                data.transferTo(file);
            }
            return new Result(used, springs);
        }
    }

    // --- the chains

    /**
     * Whether the node is the other one or above it, by the parents known so far: a child that is would make the nodes
     * go round in a circle (a broken file, an imported one), and every walk up the parents would never end.
     */
    private boolean above(int node, int other) {
        for (int n = other, steps = 0; n >= 0 && steps <= parent.length; n = parent[n], steps++) {
            if (n == node) {
                return true;
            }
        }
        return false;
    }

    /** The straight runs of bones from the node down, each a spring (at least two bones: the last one is its tip). */
    private List<List<Integer>> springs(int node, Set<Integer> stops) {
        List<List<Integer>> springs = new ArrayList<>();
        build(node, stops, springs, new HashSet<>());
        return springs;
    }

    /** @param seen the nodes walked already: a file whose nodes go round in a circle (an imported one) must not hang */
    private void build(int node, Set<Integer> stops, List<List<Integer>> springs, Set<Integer> seen) {
        if (!seen.add(node)) {
            return;
        }
        List<Integer> children = kids(node, stops);
        if (holder(node)) {
            for (int child : children) {
                build(child, stops, springs, seen);
            }
            return;
        }
        List<Integer> joints = new ArrayList<>();
        int current = node;
        while (true) {
            joints.add(current);
            List<Integer> next = kids(current, stops);
            if (next.size() == 1 && !holder(next.get(0)) && seen.add(next.get(0))) {
                current = next.get(0);
                continue;
            }
            for (int child : next) {
                build(child, stops, springs, seen);
            }
            break;
        }
        if (joints.size() >= 2) {
            springs.add(joints);
        }
    }

    /** The roots of the other enabled chains: a chain stops there, the other one takes that part. */
    private Set<Integer> stops(List<SpringChain> all, SpringChain chain) {
        Set<Integer> stops = new HashSet<>();
        for (SpringChain other : all) {
            Integer root = byName.get(other.root());
            if (root != null && !other.root().equals(chain.root()) && other.enabled()) {
                stops.add(root);
            }
        }
        return stops;
    }

    /** The children that can swing: not humanoid, not a mesh, not excluded, not the start of another chain. */
    private List<Integer> kids(int node, Set<Integer> stops) {
        List<Integer> kids = new ArrayList<>();
        for (JsonElement child : array(nodes.get(node), "children")) {
            int c = child.getAsInt();
            if (c >= 0 && c < nodes.size() && candidate(c) && !stops.contains(c)) {
                kids.add(c);
            }
        }
        return kids;
    }

    /** A bone that may swing: below the humanoid ones, not a mesh, not excluded (nor under an excluded bone). */
    private boolean candidate(int node) {
        if (humanoid.contains(node) || nodes.get(node).has("mesh") || humanoid.isEmpty()) {
            return false;
        }
        boolean underHumanoid = false;
        for (int n = node; n >= 0; n = parent[n]) {
            if (humanoid.contains(n)) {
                underHumanoid = true;
                break;
            }
            if (EXCLUDED.matcher(lower(n)).find()) {
                return false;
            }
        }
        return underHumanoid;
    }

    private boolean holder(int node) {
        return HOLDER.matcher(lower(node)).find();
    }

    /** The preset its own name says, null when it says none. */
    private SpringChain.Preset ownPreset(int node) {
        String name = lower(node);
        if (HAIR.matcher(name).find()) {
            return SpringChain.Preset.HAIR;
        }
        if (TAIL.matcher(name).find()) {
            return SpringChain.Preset.TAIL;
        }
        if (EARS.matcher(name).find()) {
            return SpringChain.Preset.EARS;
        }
        if (CLOTH.matcher(name).find()) {
            return SpringChain.Preset.CLOTH;
        }
        return null;
    }

    /** The preset of the bone or of the nearest bone above it that says one (up to the humanoid bones). */
    private SpringChain.Preset presetOf(int node) {
        for (int n = node; n >= 0 && !humanoid.contains(n); n = parent[n]) {
            SpringChain.Preset preset = ownPreset(n);
            if (preset != null) {
                return preset;
            }
        }
        return null;
    }

    private String name(int node) {
        return string(nodes.get(node), "name");
    }

    private String lower(int node) {
        String name = name(node);
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // --- the colliders

    /** Her height compared to an adult's (from the height of the hips), for the sizes of the colliders and joints. */
    private double scale() {
        Integer hips = humanBones.get("hips");
        if (hips == null) {
            return 1;
        }
        double height = world(hips)[13];
        return height > 0.1 ? height / 0.9 : 1;
    }

    private JsonArray colliders() {
        JsonArray colliders = new JsonArray();
        double s = scale();
        sphere(colliders, "head", 0, 0.07 * s, 0, 0.09 * s);
        sphere(colliders, "neck", 0, 0, 0, 0.05 * s);
        String chest = humanBones.containsKey("upperChest") ? "upperChest" : "chest";
        capsule(colliders, chest, "neck", 0.1 * s);
        capsule(colliders, "spine", chest, 0.1 * s);
        sphere(colliders, "hips", 0, 0.02 * s, 0, 0.11 * s);
        for (String side : List.of("left", "right")) {
            capsule(colliders, side + "UpperLeg", side + "LowerLeg", 0.07 * s);
            capsule(colliders, side + "UpperArm", side + "LowerArm", 0.045 * s);
        }
        return colliders;
    }

    /** A sphere on the bone, its center moved by the offset in the world (up is up whatever the bone's axes). */
    private void sphere(JsonArray colliders, String bone, double x, double y, double z, double radius) {
        Integer node = humanBones.get(bone);
        if (node == null) {
            return;
        }
        double[] world = world(node);
        double[] center = local(node, world[12] + x, world[13] + y, world[14] + z);
        JsonObject sphere = new JsonObject();
        sphere.add("offset", vector(center));
        sphere.addProperty("radius", radius);
        colliders.add(collider(node, "sphere", sphere));
    }

    /** A capsule from the bone to the next one (the upper leg to the knee). */
    private void capsule(JsonArray colliders, String bone, String to, double radius) {
        Integer node = humanBones.get(bone);
        Integer end = humanBones.get(to);
        if (node == null || end == null) {
            return;
        }
        double[] tip = world(end);
        JsonObject capsule = new JsonObject();
        capsule.add("offset", vector(new double[]{0, 0, 0}));
        capsule.addProperty("radius", radius);
        capsule.add("tail", vector(local(node, tip[12], tip[13], tip[14])));
        colliders.add(collider(node, "capsule", capsule));
    }

    private static JsonObject collider(int node, String kind, JsonObject shape) {
        JsonObject collider = new JsonObject();
        collider.addProperty("node", node);
        JsonObject shapes = new JsonObject();
        shapes.add(kind, shape);
        collider.add("shape", shapes);
        return collider;
    }

    private static JsonArray vector(double[] v) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < 3; i++) {
            array.add(Math.round(v[i] * 1e5) / 1e5);
        }
        return array;
    }

    /** A point of the world in the node's own space. */
    private double[] local(int node, double x, double y, double z) {
        double[] inverse = Matrices.invert(world(node));
        return Matrices.apply(inverse, x, y, z);
    }

    /** The node's matrix in the world (column-major, as glTF). */
    private double[] world(int node) {
        double[] cached = worlds.get(node);
        if (cached != null) {
            return cached;
        }
        double[] own = Matrices.of(nodes.get(node));
        double[] world = parent[node] < 0 ? own : Matrices.multiply(world(parent[node]), own);
        worlds.put(node, world);
        return world;
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** 4×4 matrices, column-major as in glTF. */
    static final class Matrices {

        private Matrices() {
        }

        /** The node's own matrix: its {@code matrix}, else its translation, rotation and scale. */
        static double[] of(JsonObject node) {
            JsonArray matrix = array(node, "matrix");
            if (matrix.size() == 16) {
                double[] m = new double[16];
                for (int i = 0; i < 16; i++) {
                    m[i] = matrix.get(i).getAsDouble();
                }
                return m;
            }
            double[] t = values(array(node, "translation"), new double[]{0, 0, 0});
            double[] r = values(array(node, "rotation"), new double[]{0, 0, 0, 1});
            double[] s = values(array(node, "scale"), new double[]{1, 1, 1});
            double x = r[0], y = r[1], z = r[2], w = r[3];
            return new double[]{
                    (1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0,
                    2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0,
                    2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
                    t[0], t[1], t[2], 1};
        }

        static double[] multiply(double[] a, double[] b) {
            double[] m = new double[16];
            for (int col = 0; col < 4; col++) {
                for (int row = 0; row < 4; row++) {
                    double sum = 0;
                    for (int k = 0; k < 4; k++) {
                        sum += a[k * 4 + row] * b[col * 4 + k];
                    }
                    m[col * 4 + row] = sum;
                }
            }
            return m;
        }

        static double[] apply(double[] m, double x, double y, double z) {
            return new double[]{
                    m[0] * x + m[4] * y + m[8] * z + m[12],
                    m[1] * x + m[5] * y + m[9] * z + m[13],
                    m[2] * x + m[6] * y + m[10] * z + m[14]};
        }

        /** The inverse of an affine matrix (identity when it has none). */
        static double[] invert(double[] m) {
            double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
            double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
            if (Math.abs(det) < 1e-12) {
                return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            }
            double[] r = {
                    (e * i - f * h) / det, -(d * i - f * g) / det, (d * h - e * g) / det,
                    -(b * i - c * h) / det, (a * i - c * g) / det, -(a * h - b * g) / det,
                    (b * f - c * e) / det, -(a * f - c * d) / det, (a * e - b * d) / det};
            // r is the inverse of the 3×3 part, column-major: r[col * 3 + row]
            double tx = m[12], ty = m[13], tz = m[14];
            return new double[]{
                    r[0], r[1], r[2], 0,
                    r[3], r[4], r[5], 0,
                    r[6], r[7], r[8], 0,
                    -(r[0] * tx + r[3] * ty + r[6] * tz), -(r[1] * tx + r[4] * ty + r[7] * tz), -(r[2] * tx + r[5] * ty + r[8] * tz), 1};
        }

        private static double[] values(JsonArray array, double[] fallback) {
            if (array.size() != fallback.length) {
                return fallback;
            }
            double[] values = new double[fallback.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = array.get(i).getAsDouble();
            }
            return values;
        }
    }
}
