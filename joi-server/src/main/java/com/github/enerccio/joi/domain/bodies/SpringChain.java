package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A chain of bones that swing as she moves (hair, a tail, ears, cloth): the bone it starts from (by name, so it survives
 * a new conversion), how it moves and whether it is on. The bones below the root become the joints of the VRM's spring
 * bones ({@link SpringBones}); a chain that starts inside another one takes that part of it.
 *
 * @param root    the name of the bone (node) the chain starts from
 * @param preset  how it moves
 * @param enabled whether it is written into the VRM
 */
public record SpringChain(String root, Preset preset, boolean enabled) {

    private static final Gson GSON = new Gson();

    /** How a chain moves: the values of each of its joints in the VRM. */
    public enum Preset {
        HAIR(0.8, 0.1, 0.4, 0.02),
        TAIL(1.5, 0.02, 0.4, 0.03),
        EARS(2.5, 0, 0.6, 0.01),
        CLOTH(0.5, 0.4, 0.4, 0.02),
        STIFF(4.0, 0, 0.8, 0.01);

        /** The force back to the rest pose. */
        public final double stiffness;
        /** How strongly it hangs down. */
        public final double gravity;
        /** How fast a swing dies down (0 to 1). */
        public final double drag;
        /** The radius of a joint against the colliders, in meters. */
        public final double radius;

        Preset(double stiffness, double gravity, double drag, double radius) {
            this.stiffness = stiffness;
            this.gravity = gravity;
            this.drag = drag;
            this.radius = radius;
        }
    }

    public SpringChain {
        Objects.requireNonNull(root, "root");
        preset = preset == null ? Preset.CLOTH : preset;
    }

    public SpringChain withPreset(Preset preset) {
        return new SpringChain(root, preset, enabled);
    }

    public SpringChain withEnabled(boolean enabled) {
        return new SpringChain(root, preset, enabled);
    }

    /** The chains as stored, null when they were never set (then they are guessed). */
    public static List<SpringChain> listFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<SpringChain> read = GSON.fromJson(json, new TypeToken<List<SpringChain>>() {
            }.getType());
            List<SpringChain> chains = new ArrayList<>();
            if (read != null) {
                read.stream().filter(chain -> chain != null && chain.root() != null).forEach(chains::add);
            }
            return chains;
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** As stored; an empty list stays (the user switched every chain off), null stays null. */
    public static String toJson(List<SpringChain> chains) {
        return chains == null ? null : GSON.toJson(chains);
    }
}
