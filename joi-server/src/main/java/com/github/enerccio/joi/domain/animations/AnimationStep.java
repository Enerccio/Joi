package com.github.enerccio.joi.domain.animations;

import com.github.enerccio.joi.domain.model.impl.Animation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * A step of an animation ({@link com.github.enerccio.joi.domain.model.impl.AnimationPack}, see {@code DESIGN.md} →
 * Bodies, animations): a clip of the library and how it plays there. A looping step repeats until the animation is
 * finished (her state changes, she or the user stops it), then the steps after it play. Stored on the animation as
 * JSON, in its order; a clip can be in several steps.
 *
 * @param clip    the clip's uuid
 * @param loop    whether it repeats until the animation is finished
 * @param speed   how fast it plays, 1 for as made
 * @param inPlace whether her hips keep their spot on the floor (the clip's moving about is left out)
 */
public record AnimationStep(String clip, boolean loop, double speed, boolean inPlace) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A step of the clip, as the clip was set before steps had their own settings (else once, at speed 1). */
    public static AnimationStep of(Animation clip) {
        return new AnimationStep(clip.getUuid(), clip.loops(), clip.speedOrDefault(), clip.playsInPlace());
    }

    /** Its speed, 1 when none fits. */
    public double speedOrDefault() {
        return speed > 0 ? speed : 1;
    }

    public AnimationStep withLoop(boolean loop) {
        return new AnimationStep(clip, loop, speed, inPlace);
    }

    public AnimationStep withSpeed(double speed) {
        return new AnimationStep(clip, loop, speed > 0 ? speed : 1, inPlace);
    }

    public AnimationStep withInPlace(boolean inPlace) {
        return new AnimationStep(clip, loop, speed, inPlace);
    }

    /** The steps in the JSON; empty for none or for JSON that can't be read. */
    public static List<AnimationStep> listFromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<AnimationStep> read = GSON.fromJson(json, new TypeToken<List<AnimationStep>>() {
            }.getType());
            List<AnimationStep> steps = new ArrayList<>();
            for (AnimationStep step : read == null ? List.<AnimationStep>of() : read) {
                if (step != null && step.clip() != null) {
                    steps.add(new AnimationStep(step.clip(), step.loop(), step.speedOrDefault(), step.inPlace()));
                }
            }
            return steps;
        } catch (JsonParseException e) {
            return new ArrayList<>();
        }
    }

    /** The steps as stored, null for none. */
    public static String toJson(List<AnimationStep> steps) {
        return steps == null || steps.isEmpty() ? null : GSON.toJson(steps);
    }
}
