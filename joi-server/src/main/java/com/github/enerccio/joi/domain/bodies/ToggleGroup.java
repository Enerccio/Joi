package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Toggles of a model the user grouped, with named states of the group ("eyes": "blindfold" with the band shown,
 * "bare" with the band hidden and the iris shown). A group is described as a whole, a sentence per state from a
 * close-up of the group in it, so parts that only make sense together (the eyes under a blindfold) are described
 * together and the user says what covers what. Stored on the model as JSON.
 *
 * @param id      stable, so the sentences and close-ups of a renamed group are kept
 * @param name    what she calls it ("eyes")
 * @param toggles the ids of its toggles, in order
 * @param states       its states
 * @param defaultState the id of the state she is in by default (its toggles' defaults come from it; the shots are
 *                     taken in it and the description of what never changes has it); null for none chosen, then
 *                     the toggles' own defaults count
 */
public record ToggleGroup(String id, String name, List<String> toggles, List<State> states, String defaultState) {

    public ToggleGroup(String id, String name, List<String> toggles, List<State> states) {
        this(id, name, toggles, states, null);
    }

    public ToggleGroup withName(String name) {
        return new ToggleGroup(id, name, toggles, states, defaultState);
    }

    public ToggleGroup withToggles(List<String> toggles) {
        return new ToggleGroup(id, name, toggles, states, defaultState);
    }

    /** The same group with these states; the default is dropped when its state is gone. */
    public ToggleGroup withStates(List<State> states) {
        boolean kept = states.stream().anyMatch(state -> state.id().equals(defaultState));
        return new ToggleGroup(id, name, toggles, states, kept ? defaultState : null);
    }

    public ToggleGroup withDefaultState(String defaultState) {
        return new ToggleGroup(id, name, toggles, states, defaultState);
    }

    /** The state she is in by default, null for none chosen. */
    public State defaultStateOf() {
        return states.stream().filter(state -> state.id().equals(defaultState)).findFirst().orElse(null);
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * @param id    stable within the group
     * @param name  what she calls the state ("bare")
     * @param shown the toggle's id → whether it is shown in this state; a toggle of the group not in it is as by default
     */
    public record State(String id, String name, Map<String, Boolean> shown) {
    }

    /** A new id for a group or a state. */
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** The groups in the JSON; empty for none or for JSON that can't be read. */
    public static List<ToggleGroup> listFromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ToggleGroup> read = GSON.fromJson(json, new TypeToken<List<ToggleGroup>>() {
            }.getType());
            List<ToggleGroup> groups = new ArrayList<>();
            for (ToggleGroup group : read == null ? List.<ToggleGroup>of() : read) {
                if (group == null || group.id() == null) {
                    continue;
                }
                List<State> states = new ArrayList<>();
                for (State state : group.states() == null ? List.<State>of() : group.states()) {
                    if (state != null && state.id() != null) {
                        states.add(new State(state.id(), state.name(), state.shown() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.shown())));
                    }
                }
                ToggleGroup read1 = new ToggleGroup(group.id(), group.name(), group.toggles() == null ? new ArrayList<>() : new ArrayList<>(group.toggles()),
                        states, group.defaultState());
                groups.add(read1.withStates(states));
            }
            return groups;
        } catch (JsonParseException e) {
            return List.of();
        }
    }

    /** The groups as stored, null for none. */
    public static String toJson(List<ToggleGroup> groups) {
        return groups == null || groups.isEmpty() ? null : GSON.toJson(groups);
    }
}
