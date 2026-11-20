package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.model.impl.Model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which toggles of a model are part of which: a shape key toggle ("Hide Panties") is part of the toggle of its mesh
 * (the bodysuit), so while the bodysuit is hidden the panties are too, whatever state they are in. The user can change
 * it (a toggle part of another, or of none). A toggle's own state is kept as it is; what is seen is its
 * {@linkplain #effective effective} state, shown only while every toggle above it is shown, so a parent switched back
 * on shows its parts as they were.
 */
public final class ToggleTree {

    private final Map<String, String> parents;
    private final List<String> order;

    private ToggleTree(Map<String, String> parents, List<String> order) {
        this.parents = parents;
        this.order = order;
    }

    /** The tree of the model's toggles: its inferred parents, then what the user changed. */
    public static ToggleTree of(Model model, ModelCapabilities capabilities) {
        List<ModelCapabilities.Toggle> toggles = capabilities == null ? List.of() : capabilities.toggles();
        Set<String> ids = new HashSet<>();
        toggles.forEach(toggle -> ids.add(toggle.id()));
        Map<String, String> chosen = model == null ? Map.of() : model.toggleParentMap();
        Map<String, String> parents = new LinkedHashMap<>();
        for (ModelCapabilities.Toggle toggle : toggles) {
            String parent = chosen.containsKey(toggle.id()) ? chosen.get(toggle.id()) : inferred(toggle);
            if (parent != null && !parent.isEmpty() && !parent.equals(toggle.id()) && ids.contains(parent)) {
                parents.put(toggle.id(), parent);
            }
        }
        // a loop the user made (a part of its own part) is cut where it is found
        for (String id : new ArrayList<>(parents.keySet())) {
            Set<String> seen = new HashSet<>();
            String at = id;
            while (at != null && parents.containsKey(at)) {
                if (!seen.add(at)) {
                    parents.remove(id);
                    break;
                }
                at = parents.get(at);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (ModelCapabilities.Toggle toggle : toggles) {
            if (!parents.containsKey(toggle.id())) {
                place(toggle.id(), parents, toggles, order, placed);
            }
        }
        return new ToggleTree(parents, order);
    }

    /** Depth first, so a part follows its parent. */
    private static void place(String id, Map<String, String> parents, List<ModelCapabilities.Toggle> toggles, List<String> order, Set<String> placed) {
        if (!placed.add(id)) {
            return;
        }
        order.add(id);
        for (ModelCapabilities.Toggle toggle : toggles) {
            if (id.equals(parents.get(toggle.id()))) {
                place(toggle.id(), parents, toggles, order, placed);
            }
        }
    }

    /** The parent the tree infers: the mesh a shape key is on. */
    public static String inferred(ModelCapabilities.Toggle toggle) {
        return toggle.key() == null ? null : "mesh:" + toggle.mesh();
    }

    /** The id of the toggle this one is part of, null for none. */
    public String parent(String id) {
        return parents.get(id);
    }

    /** The toggles above this one, the nearest first. */
    public List<String> ancestors(String id) {
        List<String> ancestors = new ArrayList<>();
        for (String at = parents.get(id); at != null; at = parents.get(at)) {
            ancestors.add(at);
        }
        return ancestors;
    }

    /** How many toggles are above this one. */
    public int depth(String id) {
        return ancestors(id).size();
    }

    /** Every toggle in depth first order, each after its parent. */
    public List<String> order() {
        return order;
    }

    /** Whether the toggle is above this one (part of its part, and so on). */
    public boolean isAbove(String id, String part) {
        return ancestors(part).contains(id);
    }

    /**
     * What is seen: each toggle shown only while it and every toggle above it is shown.
     *
     * @param shown the toggle's id → its own state; a toggle not in it counts as shown
     */
    public Map<String, Boolean> effective(Map<String, Boolean> shown) {
        Map<String, Boolean> effective = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> toggle : shown.entrySet()) {
            boolean seen = toggle.getValue();
            for (String above : ancestors(toggle.getKey())) {
                seen = seen && shown.getOrDefault(above, true);
            }
            effective.put(toggle.getKey(), seen);
        }
        return effective;
    }
}
