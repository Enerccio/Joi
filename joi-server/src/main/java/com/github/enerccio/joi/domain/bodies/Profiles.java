package com.github.enerccio.joi.domain.bodies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The profiles of an assistant (see {@code DESIGN.md} → Outfits → Profiles): where she is (home, work), each with the body
 * she is in there and the state of that body's parts, which node is in which profile, and the profile she was in last.
 * <p>
 * The <b>default profile</b> ({@link #DEFAULT}) is not stored here: it is the state the bodies keep themselves
 * ({@code Body.active}, {@code Body.partStates}), so an assistant with no profiles works as it always did. Its name may be
 * set. A node that is assigned to no profile is in the default one.
 * <p>
 * Stored on the assistant as JSON (an extended attribute); null for nothing set.
 */
public final class Profiles {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** The id of the default profile. */
    public static final String DEFAULT = "default";

    /**
     * One profile besides the default one.
     *
     * @param id     a uuid
     * @param name   what the user calls it
     * @param active the uuid of the body she is in there, null for the one she is in in the default profile
     * @param states the body's uuid → the state of its parts there ({@link BodyState}); a body not in it is as by default
     */
    public static final class Profile {
        private String id;
        private String name;
        private String active;
        private Map<String, Map<String, String>> states;

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String active() {
            return active;
        }

        public Map<String, Map<String, String>> states() {
            if (states == null) {
                states = new LinkedHashMap<>();
            }
            return states;
        }
    }

    private String defaultName;
    private String last;
    private List<Profile> profiles = new ArrayList<>();
    /** The node's stable id → the profile's id. */
    private Map<String, String> nodes = new LinkedHashMap<>();
    /** The nodes that connected: the node's stable id → its name, for the user to assign them by. */
    private Map<String, String> names = new LinkedHashMap<>();

    /** The profiles as stored; none for null or JSON that can't be read. */
    public static Profiles fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new Profiles();
        }
        try {
            Profiles read = GSON.fromJson(json, Profiles.class);
            if (read == null) {
                return new Profiles();
            }
            if (read.profiles == null) {
                read.profiles = new ArrayList<>();
            }
            if (read.nodes == null) {
                read.nodes = new LinkedHashMap<>();
            }
            if (read.names == null) {
                read.names = new LinkedHashMap<>();
            }
            read.profiles.removeIf(profile -> profile == null || profile.id == null);
            return read;
        } catch (JsonParseException e) {
            return new Profiles();
        }
    }

    /** As stored, null for nothing set. */
    public String toJson() {
        boolean empty = defaultName == null && last == null && profiles.isEmpty() && nodes.isEmpty() && names.isEmpty();
        return empty ? null : GSON.toJson(this);
    }

    /** Whether the id is the default profile (or none). */
    public static boolean isDefault(String profile) {
        return StringUtils.isBlank(profile) || DEFAULT.equals(profile);
    }

    /** The id as the stored ones have it: the default one for none. */
    public static String idOf(String profile) {
        return isDefault(profile) ? DEFAULT : profile;
    }

    /** Whether there is more than one profile. */
    public boolean several() {
        return !profiles.isEmpty();
    }

    /** The ids of all of them, the default one first. */
    public List<String> ids() {
        List<String> ids = new ArrayList<>();
        ids.add(DEFAULT);
        profiles.forEach(profile -> ids.add(profile.id));
        return ids;
    }

    /** The profile's name: the default one has its own (or "Default"). Null for an id that is none. */
    public String nameOf(String profile) {
        if (isDefault(profile)) {
            return StringUtils.defaultIfBlank(defaultName, "Default");
        }
        Profile found = find(profile);
        return found == null ? null : StringUtils.defaultIfBlank(found.name, "Unnamed");
    }

    public void nameDefault(String name) {
        this.defaultName = StringUtils.defaultIfBlank(StringUtils.strip(name), null);
    }

    /** The profile besides the default one, null for none. */
    public Profile find(String id) {
        return isDefault(id) ? null : profiles.stream().filter(profile -> profile.id.equals(id)).findFirst().orElse(null);
    }

    /** Whether the id is a profile of hers (the default one is). */
    public boolean exists(String id) {
        return isDefault(id) || find(id) != null;
    }

    /** Whether some profile (the default one too) has the name. */
    public boolean nameTaken(String name, String except) {
        String wanted = StringUtils.strip(name);
        if (StringUtils.isBlank(wanted)) {
            return false;
        }
        for (String id : ids()) {
            if ((except == null || !idOf(except).equals(id)) && wanted.equalsIgnoreCase(nameOf(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a profile that starts as the default one is: the same body, the state of every body as the default has it.
     * Returns its id.
     *
     * @param defaults the default profile's state: the body's uuid → its parts' state
     * @param active   the uuid of the body she is in in the default profile, null for none
     */
    public String add(String name, String active, Map<String, Map<String, String>> defaults) {
        Profile profile = new Profile();
        profile.id = UUID.randomUUID().toString();
        profile.name = StringUtils.strip(name);
        profile.active = active;
        defaults.forEach((body, states) -> profile.states().put(body, new LinkedHashMap<>(states)));
        profiles.add(profile);
        return profile.id;
    }

    public void rename(String id, String name) {
        if (isDefault(id)) {
            nameDefault(name);
            return;
        }
        Profile profile = find(id);
        if (profile != null) {
            profile.name = StringUtils.strip(name);
        }
    }

    /** Removes the profile; the nodes that were in it are in the default one again. */
    public void remove(String id) {
        if (isDefault(id)) {
            return;
        }
        profiles.removeIf(profile -> profile.id.equals(id));
        nodes.values().removeIf(id::equals);
        if (id.equals(last)) {
            last = null;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the nodes
    // ----------------------------------------------------------------------------------------------------------------

    /** The profile the node is in: the one it is assigned to, the default one for none (or one that is gone). */
    public String profileOf(String nodeId) {
        String assigned = nodeId == null ? null : nodes.get(nodeId);
        return assigned == null || !exists(assigned) ? DEFAULT : assigned;
    }

    /** The node is in this profile (the default one: not assigned). */
    public void assign(String nodeId, String profile) {
        if (StringUtils.isBlank(nodeId)) {
            return;
        }
        if (isDefault(profile) || !exists(profile)) {
            nodes.remove(nodeId);
        } else {
            nodes.put(nodeId, profile);
        }
    }

    /** A node connected: remembered by its name; returns whether that is new. */
    public boolean seen(String nodeId, String name) {
        if (StringUtils.isBlank(nodeId)) {
            return false;
        }
        String known = StringUtils.defaultString(name);
        if (known.equals(names.get(nodeId))) {
            return false;
        }
        names.put(nodeId, known);
        return true;
    }

    /** Forgets a node (and its assignment). */
    public void forget(String nodeId) {
        names.remove(nodeId);
        nodes.remove(nodeId);
    }

    /** The nodes that connected or were assigned: the node's stable id → its name (blank when not known). */
    public Map<String, String> knownNodes() {
        Map<String, String> known = new LinkedHashMap<>(names);
        nodes.keySet().forEach(id -> known.putIfAbsent(id, ""));
        return known;
    }

    /** The nodes assigned to a profile besides the default one: the node's id → the profile's id. */
    public Map<String, String> assigned() {
        return new LinkedHashMap<>(nodes);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // what she was doing last
    // ----------------------------------------------------------------------------------------------------------------

    /** The profile her last turn or change was in: the default one before any. */
    public String last() {
        return last == null || !exists(last) ? DEFAULT : last;
    }

    /** Remembers it; returns whether it changed. */
    public boolean used(String profile) {
        String id = idOf(profile);
        if (id.equals(last())) {
            return false;
        }
        this.last = DEFAULT.equals(id) ? null : id;
        return true;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // the state of a profile besides the default one
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * The uuid of the body she is in in the profile, or the default's when the profile has none of its own (a body that
     * was deleted is for the caller to notice: it is not found).
     */
    public String activeIn(String profile, String defaultActive) {
        Profile found = find(profile);
        return found == null || found.active == null ? defaultActive : found.active;
    }

    public void activate(String profile, String bodyUuid) {
        Profile found = find(profile);
        if (found != null) {
            found.active = bodyUuid;
        }
    }

    /** The state of the body's parts in the profile (a copy; empty for a body not in it). */
    public Map<String, String> statesOf(String profile, String bodyUuid) {
        Profile found = find(profile);
        Map<String, String> states = found == null ? null : found.states().get(bodyUuid);
        return states == null ? new LinkedHashMap<>() : new LinkedHashMap<>(states);
    }

    public void putStates(String profile, String bodyUuid, Map<String, String> states) {
        Profile found = find(profile);
        if (found == null) {
            return;
        }
        if (states == null || states.isEmpty()) {
            found.states().remove(bodyUuid);
        } else {
            found.states().put(bodyUuid, new LinkedHashMap<>(states));
        }
    }

    /** The profiles besides the default one, in the order they were made. */
    public List<Profile> others() {
        return List.copyOf(profiles);
    }
}
