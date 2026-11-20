package com.github.enerccio.joi.domain.animations;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The animations an assistant plays in her states, chosen in her dialog (the Animations tab): uuids of the user's
 * animations ({@code AnimationPack}), stored on her as JSON. A node plays them from the {@code .joia} of her body.
 *
 * @param sleeping      while she sleeps (it loops), null for none
 * @param fallingAsleep as she goes to sleep, null for none
 * @param wakingUp      as she wakes up, null for none
 * @param idle          while she waits, one at a time
 * @param talking       while she speaks, one at a time
 */
public record AnimationRoles(String sleeping, String fallingAsleep, String wakingUp, List<String> idle, List<String> talking) {

    private static final Gson GSON = new Gson();

    /** None chosen. */
    public static final AnimationRoles NONE = new AnimationRoles(null, null, null, List.of(), List.of());

    public AnimationRoles {
        idle = idle == null ? List.of() : List.copyOf(idle.stream().filter(id -> id != null && !id.isBlank()).distinct().toList());
        talking = talking == null ? List.of() : List.copyOf(talking.stream().filter(id -> id != null && !id.isBlank()).distinct().toList());
        sleeping = blankToNull(sleeping);
        fallingAsleep = blankToNull(fallingAsleep);
        wakingUp = blankToNull(wakingUp);
    }

    private static String blankToNull(String id) {
        return id == null || id.isBlank() ? null : id;
    }

    public AnimationRoles withSleeping(String sleeping) {
        return new AnimationRoles(sleeping, fallingAsleep, wakingUp, idle, talking);
    }

    public AnimationRoles withFallingAsleep(String fallingAsleep) {
        return new AnimationRoles(sleeping, fallingAsleep, wakingUp, idle, talking);
    }

    public AnimationRoles withWakingUp(String wakingUp) {
        return new AnimationRoles(sleeping, fallingAsleep, wakingUp, idle, talking);
    }

    public AnimationRoles withIdle(List<String> idle) {
        return new AnimationRoles(sleeping, fallingAsleep, wakingUp, idle, talking);
    }

    public AnimationRoles withTalking(List<String> talking) {
        return new AnimationRoles(sleeping, fallingAsleep, wakingUp, idle, talking);
    }

    /** Only the animations that still exist (the uuids given): one deleted is no longer chosen. */
    public AnimationRoles existing(Collection<String> uuids) {
        return new AnimationRoles(keep(sleeping, uuids), keep(fallingAsleep, uuids), keep(wakingUp, uuids),
                idle.stream().filter(uuids::contains).toList(), talking.stream().filter(uuids::contains).toList());
    }

    private static String keep(String id, Collection<String> uuids) {
        return id != null && uuids.contains(id) ? id : null;
    }

    /** Every animation chosen, each once. */
    public List<String> all() {
        List<String> all = new ArrayList<>();
        for (String id : new String[]{sleeping, fallingAsleep, wakingUp}) {
            if (id != null && !all.contains(id)) {
                all.add(id);
            }
        }
        idle.stream().filter(id -> !all.contains(id)).forEach(all::add);
        talking.stream().filter(id -> !all.contains(id)).forEach(all::add);
        return all;
    }

    /** As stored; none chosen for null or JSON that can't be read. */
    public static AnimationRoles fromJson(String json) {
        if (json == null || json.isBlank()) {
            return NONE;
        }
        try {
            AnimationRoles read = GSON.fromJson(json, AnimationRoles.class);
            return read == null ? NONE : read;
        } catch (JsonParseException e) {
            return NONE;
        }
    }

    /** As stored, null for none chosen. */
    public static String toJson(AnimationRoles roles) {
        return roles == null || roles.equals(NONE) ? null : GSON.toJson(roles);
    }
}
