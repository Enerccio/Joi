package com.github.enerccio.joi.domain.animations;

import java.util.List;

/**
 * The translation of an {@link AnimationStep}: an offset {@code [x, y, z]} in metres, in the model's space (X to her
 * left side as she faces the camera at rest, Y up, Z towards the camera), added to her hips at every key of the clip
 * after the step's rotation. A clip recorded off the spot she stands on, or a step that must start somewhere else (a
 * jump onto a chair), is moved back with it.
 */
public final class StepTranslation {

    /** The farthest an offset goes on one axis, in metres. */
    public static final double MAX = 10;

    /** Closer to nothing than this on every axis (in metres) is none: a tenth of a millimetre. */
    private static final double NONE = 1e-4;

    private StepTranslation() {
    }

    /** The offset within {@link #MAX} on each axis; null for none, for a wrong one, or for one that does not move. */
    public static List<Double> normalized(List<Double> offset) {
        if (offset == null || offset.size() != 3 || offset.stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
            return null;
        }
        double x = Math.clamp(offset.get(0), -MAX, MAX);
        double y = Math.clamp(offset.get(1), -MAX, MAX);
        double z = Math.clamp(offset.get(2), -MAX, MAX);
        if (Math.abs(x) < NONE && Math.abs(y) < NONE && Math.abs(z) < NONE) {
            return null;
        }
        return List.of(x, y, z);
    }

    /** The offset of the three values in metres; null for none. */
    public static List<Double> of(double x, double y, double z) {
        return normalized(List.of(x, y, z));
    }

    /** The values of the offset in metres; zeros for none. */
    public static double[] toArray(List<Double> translation) {
        List<Double> t = normalized(translation);
        return t == null ? new double[]{0, 0, 0} : new double[]{t.get(0), t.get(1), t.get(2)};
    }
}
