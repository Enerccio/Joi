package com.github.enerccio.joi.domain.animations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A small VRMA for the tests: a two second wave of the right arm (the upper arm raised, the lower arm swinging) and a
 * smile, on the humanoid bones {@code hips}, {@code rightUpperArm}, {@code rightLowerArm} and {@code head} (the head
 * doesn't move).
 */
public final class VrmaTestFiles {

    private VrmaTestFiles() {
    }

    public static File wave(File file) throws IOException {
        float[] times = {0f, 0.5f, 1f, 1.5f, 2f};
        float[][] upper = new float[times.length][];
        float[][] lower = new float[times.length][];
        float[][] smile = new float[times.length][];
        for (int i = 0; i < times.length; i++) {
            // the right arm points along -X: a turn the other way round Z raises it
            upper[i] = aroundZ(-1.2);
            lower[i] = aroundZ(i % 2 == 0 ? -0.9 : -0.3);
            smile[i] = new float[]{i == 0 || i == times.length - 1 ? 0f : 1f, 0f, 0f};
        }
        ByteBuffer data = ByteBuffer.allocate(4 * (times.length * (1 + 4 + 4 + 3))).order(ByteOrder.LITTLE_ENDIAN);
        int timesOffset = data.position();
        for (float t : times) {
            data.putFloat(t);
        }
        int upperOffset = data.position();
        for (float[] q : upper) {
            for (float v : q) {
                data.putFloat(v);
            }
        }
        int lowerOffset = data.position();
        for (float[] q : lower) {
            for (float v : q) {
                data.putFloat(v);
            }
        }
        int smileOffset = data.position();
        for (float[] p : smile) {
            for (float v : p) {
                data.putFloat(v);
            }
        }
        byte[] bin = data.array();

        JsonObject json = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        json.add("asset", asset);
        JsonArray used = new JsonArray();
        used.add("VRMC_vrm_animation");
        json.add("extensionsUsed", used);

        JsonArray nodes = new JsonArray();
        nodes.add(node("hips", new float[]{0, 1, 0}, 1, 3));
        nodes.add(node("rightUpperArm", new float[]{-0.2f, 0.4f, 0}, 2));
        nodes.add(node("rightLowerArm", new float[]{-0.25f, 0, 0}));
        nodes.add(node("head", new float[]{0, 0.6f, 0}));
        nodes.add(node("happy", new float[]{0, 0, 0}));
        json.add("nodes", nodes);
        JsonArray roots = new JsonArray();
        roots.add(0);
        roots.add(4);
        JsonObject scene = new JsonObject();
        scene.add("nodes", roots);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        json.add("scenes", scenes);
        json.addProperty("scene", 0);

        JsonArray buffers = new JsonArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", bin.length);
        buffers.add(buffer);
        json.add("buffers", buffers);
        JsonArray views = new JsonArray();
        views.add(view(timesOffset, upperOffset - timesOffset));
        views.add(view(upperOffset, lowerOffset - upperOffset));
        views.add(view(lowerOffset, smileOffset - lowerOffset));
        views.add(view(smileOffset, bin.length - smileOffset));
        json.add("bufferViews", views);
        JsonArray accessors = new JsonArray();
        JsonObject input = accessor(0, times.length, "SCALAR");
        JsonArray min = new JsonArray();
        min.add(0);
        JsonArray max = new JsonArray();
        max.add(2.0);
        input.add("min", min);
        input.add("max", max);
        accessors.add(input);
        accessors.add(accessor(1, times.length, "VEC4"));
        accessors.add(accessor(2, times.length, "VEC4"));
        accessors.add(accessor(3, times.length, "VEC3"));
        json.add("accessors", accessors);

        JsonObject animation = new JsonObject();
        animation.addProperty("name", "wave");
        JsonArray samplers = new JsonArray();
        samplers.add(sampler(0, 1));
        samplers.add(sampler(0, 2));
        samplers.add(sampler(0, 3));
        animation.add("samplers", samplers);
        JsonArray channels = new JsonArray();
        channels.add(channel(0, 1, "rotation"));
        channels.add(channel(1, 2, "rotation"));
        channels.add(channel(2, 4, "translation"));
        animation.add("channels", channels);
        JsonArray animations = new JsonArray();
        animations.add(animation);
        json.add("animations", animations);

        JsonObject humanBones = new JsonObject();
        humanBones.add("hips", nodeRef(0));
        humanBones.add("rightUpperArm", nodeRef(1));
        humanBones.add("rightLowerArm", nodeRef(2));
        humanBones.add("head", nodeRef(3));
        JsonObject humanoid = new JsonObject();
        humanoid.add("humanBones", humanBones);
        JsonObject preset = new JsonObject();
        preset.add("happy", nodeRef(4));
        JsonObject expressions = new JsonObject();
        expressions.add("preset", preset);
        JsonObject extension = new JsonObject();
        extension.addProperty("specVersion", "1.0");
        extension.add("humanoid", humanoid);
        extension.add("expressions", expressions);
        JsonObject extensions = new JsonObject();
        extensions.add("VRMC_vrm_animation", extension);
        json.add("extensions", extensions);

        byte[] jsonBytes = pad(json.toString().getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] binBytes = pad(bin, (byte) 0);
        int total = 12 + 8 + jsonBytes.length + 8 + binBytes.length;
        ByteBuffer glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(total);
        glb.putInt(jsonBytes.length).putInt(0x4E4F534A).put(jsonBytes);
        glb.putInt(binBytes.length).putInt(0x004E4942).put(binBytes);
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(glb.array());
        }
        return file;
    }

    /** Writes the wave to the path given (for trying it in the preview by hand). */
    public static void main(String[] args) throws IOException {
        wave(new File(args[0]));
    }

    private static float[] aroundZ(double angle) {
        return new float[]{0f, 0f, (float) Math.sin(angle / 2), (float) Math.cos(angle / 2)};
    }

    private static JsonObject node(String name, float[] translation, int... children) {
        JsonObject node = new JsonObject();
        node.addProperty("name", name);
        JsonArray t = new JsonArray();
        for (float v : translation) {
            t.add(v);
        }
        node.add("translation", t);
        if (children.length > 0) {
            JsonArray c = new JsonArray();
            for (int child : children) {
                c.add(child);
            }
            node.add("children", c);
        }
        return node;
    }

    private static JsonObject nodeRef(int node) {
        JsonObject ref = new JsonObject();
        ref.addProperty("node", node);
        return ref;
    }

    private static JsonObject view(int offset, int length) {
        JsonObject view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", offset);
        view.addProperty("byteLength", length);
        return view;
    }

    private static JsonObject accessor(int view, int count, String type) {
        JsonObject accessor = new JsonObject();
        accessor.addProperty("bufferView", view);
        accessor.addProperty("componentType", 5126);
        accessor.addProperty("count", count);
        accessor.addProperty("type", type);
        return accessor;
    }

    private static JsonObject sampler(int input, int output) {
        JsonObject sampler = new JsonObject();
        sampler.addProperty("input", input);
        sampler.addProperty("output", output);
        sampler.addProperty("interpolation", "LINEAR");
        return sampler;
    }

    private static JsonObject channel(int sampler, int node, String path) {
        JsonObject target = new JsonObject();
        target.addProperty("node", node);
        target.addProperty("path", path);
        JsonObject channel = new JsonObject();
        channel.addProperty("sampler", sampler);
        channel.add("target", target);
        return channel;
    }

    private static byte[] pad(byte[] bytes, byte with) {
        int length = (bytes.length + 3) & ~3;
        byte[] padded = new byte[length];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        for (int i = bytes.length; i < length; i++) {
            padded[i] = with;
        }
        return padded;
    }
}
