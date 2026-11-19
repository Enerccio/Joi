package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A part of a model described apart, a sentence for each of its states: a group of toggles the user made
 * ({@link ToggleGroup}, its named states), or a toggle marked to be described that is in no group (its states shown
 * and hidden). Only these can be switched: she can't change what she has no words for.
 *
 * @param id      the toggle's id, or {@code group:<the group's id>}
 * @param name    what she calls it
 * @param source  for the model: the mesh / the key, or the group's toggles
 * @param toggles the toggles it switches
 * @param states  its states, in order
 * @param groupId the group's id, null for a single toggle
 * @param defaultStateId the state she is in by default (the shots show it and the description of what never changes
 *                       has it, so its sentence is not added to her look); null when no state is the default
 */
public record DescribedPart(String id, String name, String source, List<ModelCapabilities.Toggle> toggles, List<State> states,
                            String groupId, String defaultStateId) {

    public static final String SHOWN = "shown";
    public static final String HIDDEN = "hidden";
    public static final String GROUP = "group:";
    /** The prefix of the close-ups: {@code part:<the part's id>:<the state's id>}. */
    public static final String SHOT = "part:";

    /**
     * @param shown the toggle's id → whether it is shown, for the toggles of the part this state sets
     */
    public record State(String id, String name, Map<String, Boolean> shown) {
    }

    public boolean group() {
        return groupId != null;
    }

    /** The name of the close-up of a state. */
    public String shot(String stateId) {
        return SHOT + id + ":" + stateId;
    }

    /** The parts of the model described apart: its groups, then the described toggles in no group. */
    public static List<DescribedPart> of(Model model, ModelCapabilities capabilities) {
        if (capabilities == null) {
            return List.of();
        }
        Map<String, ModelCapabilities.Toggle> toggles = new LinkedHashMap<>();
        for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
            toggles.put(toggle.id(), toggle);
        }
        List<DescribedPart> parts = new ArrayList<>();
        Set<String> grouped = new HashSet<>();
        for (ToggleGroup group : model.toggleGroups()) {
            List<ModelCapabilities.Toggle> members = new ArrayList<>();
            for (String id : group.toggles()) {
                ModelCapabilities.Toggle toggle = toggles.get(id);
                if (toggle != null && grouped.add(id)) {
                    members.add(toggle);
                }
            }
            if (members.isEmpty() || group.states().isEmpty()) {
                continue;
            }
            List<State> states = new ArrayList<>();
            for (ToggleGroup.State state : group.states()) {
                Map<String, Boolean> shown = new LinkedHashMap<>();
                for (ModelCapabilities.Toggle member : members) {
                    Boolean value = state.shown().get(member.id());
                    shown.put(member.id(), value == null ? model.shownByDefault(member) : value);
                }
                states.add(new State(state.id(), StringUtils.defaultIfBlank(state.name(), state.id()), shown));
            }
            List<String> names = members.stream().map(model::toggleName).toList();
            // the user marks it: the shots and the description are of that state ("bare", so "blindfold" is news)
            String byDefault = group.defaultStateOf() != null ? group.defaultState() : null;
            parts.add(new DescribedPart(GROUP + group.id(), StringUtils.defaultIfBlank(group.name(), String.join(", ", names)),
                    String.join(", ", names), members, states, group.id(), byDefault));
        }
        List<String> described = model.describedToggles(capabilities);
        for (ModelCapabilities.Toggle toggle : capabilities.toggles()) {
            if (described.contains(toggle.id()) && !grouped.contains(toggle.id())) {
                parts.add(new DescribedPart(toggle.id(), model.toggleName(toggle),
                        toggle.key() == null ? toggle.mesh() : toggle.mesh() + " / " + toggle.key(), List.of(toggle),
                        List.of(new State(SHOWN, SHOWN, Map.of(toggle.id(), true)), new State(HIDDEN, HIDDEN, Map.of(toggle.id(), false))),
                        null, model.shownByDefault(toggle) ? SHOWN : HIDDEN));
            }
        }
        return parts;
    }

    /** Whether the state is the one she is in by default. */
    public boolean isDefault(State state) {
        return state != null && state.id().equals(defaultStateId);
    }

    /** The ids of the toggles in a part described apart: the only ones that can be switched. */
    public static Set<String> switchable(Model model, ModelCapabilities capabilities) {
        Set<String> ids = new HashSet<>();
        for (DescribedPart part : of(model, capabilities)) {
            part.toggles().forEach(toggle -> ids.add(toggle.id()));
        }
        return ids;
    }

    /**
     * The state the part is in: the one that matches each of its toggles, else the nearest (the fewest toggles off).
     *
     * @param state the toggle's id → whether it is shown, for every toggle of the model
     */
    public State stateIn(Map<String, Boolean> state) {
        State nearest = null;
        int fewest = Integer.MAX_VALUE;
        for (State candidate : states) {
            int off = 0;
            for (ModelCapabilities.Toggle toggle : toggles) {
                if (!candidate.shown().getOrDefault(toggle.id(), toggle.shown()).equals(state.getOrDefault(toggle.id(), toggle.shown()))) {
                    off++;
                }
            }
            if (off < fewest) {
                fewest = off;
                nearest = candidate;
            }
        }
        return nearest;
    }

    /** The sentence of a state, null for none. */
    public String sentence(Model model, String stateId) {
        String sentence;
        if (group()) {
            sentence = model.getGroupSentences().get(groupId + ":" + stateId);
        } else {
            sentence = (SHOWN.equals(stateId) ? model.getPartShown() : model.getPartHidden()).get(id);
        }
        return StringUtils.defaultIfBlank(StringUtils.strip(sentence), null);
    }

    /** Stores (or, when blank, removes) the sentence of a state on the model. */
    public void setSentence(Model model, String stateId, String sentence) {
        Map<String, String> sentences;
        String key;
        if (group()) {
            sentences = model.getGroupSentences();
            key = groupId + ":" + stateId;
        } else {
            sentences = SHOWN.equals(stateId) ? model.getPartShown() : model.getPartHidden();
            key = id;
        }
        if (StringUtils.isBlank(sentence)) {
            sentences.remove(key);
        } else {
            sentences.put(key, sentence.strip());
        }
    }

    /** What a state shows, for the model: "shown", or "bare: blindfold hidden, iris shown". */
    public String stateLine(Model model, State state) {
        if (!group()) {
            return SHOWN.equals(state.id()) ? "the part shown" : "the part hidden";
        }
        List<String> toggleStates = new ArrayList<>();
        for (ModelCapabilities.Toggle toggle : toggles) {
            toggleStates.add(model.toggleName(toggle) + (state.shown().getOrDefault(toggle.id(), true) ? " shown" : " hidden"));
        }
        return state.name() + ": " + String.join(", ", toggleStates);
    }
}
