package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * The animations a body plays: the uuids of the user's animations ({@code AnimationPack}), stored on the body as JSON.
 * None stored (null) means all of the user's animations, also those added later; an empty list means none.
 */
public final class BodyAnimations {

    private static final Gson GSON = new Gson();

    private BodyAnimations() {
    }

    /** The uuids, null for all; null too for JSON that can't be read. */
    public static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> read = GSON.fromJson(json, new TypeToken<List<String>>() {
            }.getType());
            return read == null ? null : new ArrayList<>(read);
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** As stored, null for all. */
    public static String toJson(List<String> uuids) {
        return uuids == null ? null : GSON.toJson(uuids);
    }
}
