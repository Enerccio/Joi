package com.github.enerccio.joi.domain.animations;

import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.function.Function;

/**
 * The folders of one kind ({@link AnimationFolder.Kind}) as a tree: the folders in a folder, the path of a folder, the
 * label of an item with its folder's path before its name ({@code Idle / Standing / Breathe}), and the checks of a name
 * and of a move. Read only: {@code AnimationFolderService} changes the folders.
 */
public final class AnimationFolders {

    /** Between the folders of a path and before the item's name. */
    public static final String SEPARATOR = " / ";

    private final Map<String, AnimationFolder> byUuid = new LinkedHashMap<>();

    public AnimationFolders(Collection<AnimationFolder> folders) {
        for (AnimationFolder folder : folders) {
            if (folder != null && folder.getUuid() != null) {
                byUuid.put(folder.getUuid(), folder);
            }
        }
    }

    /** All the folders, in no order. */
    public Collection<AnimationFolder> all() {
        return Collections.unmodifiableCollection(byUuid.values());
    }

    /** The folder of the uuid, null for none (the top level). */
    public AnimationFolder find(String uuid) {
        return uuid == null ? null : byUuid.get(uuid);
    }

    /** The uuid of the folder as an item names it: null when it is the top level or a folder that is gone. */
    public String known(String uuid) {
        return find(uuid) == null ? null : uuid;
    }

    /** The folders in the folder (null: the top level), by name. */
    public List<AnimationFolder> children(String parent) {
        String in = known(parent);
        List<AnimationFolder> children = new ArrayList<>();
        for (AnimationFolder folder : byUuid.values()) {
            if (Objects.equals(known(folder.getParent()), in)) {
                children.add(folder);
            }
        }
        children.sort(Comparator.comparing(f -> StringUtils.defaultString(f.getName()), String.CASE_INSENSITIVE_ORDER));
        return children;
    }

    /** The folders above the folder, from the top level down, the folder itself not included. */
    public List<AnimationFolder> ancestors(String uuid) {
        LinkedList<AnimationFolder> above = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        AnimationFolder folder = find(uuid);
        if (folder != null) {
            seen.add(folder.getUuid());
            AnimationFolder parent = find(folder.getParent());
            // a loop (a folder made its own ancestor by hand) ends the path
            while (parent != null && seen.add(parent.getUuid())) {
                above.addFirst(parent);
                parent = find(parent.getParent());
            }
        }
        return above;
    }

    /** The folder and every folder below it, at any depth. */
    public Set<String> withDescendants(String uuid) {
        Set<String> found = new LinkedHashSet<>();
        if (find(uuid) == null) {
            return found;
        }
        Deque<String> open = new ArrayDeque<>(List.of(uuid));
        while (!open.isEmpty()) {
            String next = open.poll();
            if (found.add(next)) {
                for (AnimationFolder child : children(next)) {
                    open.add(child.getUuid());
                }
            }
        }
        return found;
    }

    /** The path of the folder: the names from the top level down to it ({@code Idle / Standing}); empty for the top level. */
    public String path(String uuid) {
        AnimationFolder folder = find(uuid);
        if (folder == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (AnimationFolder above : ancestors(uuid)) {
            names.add(above.getName());
        }
        names.add(folder.getName());
        return String.join(SEPARATOR, names);
    }

    /** An item's label: the path of its folder before its name ({@code Idle / Standing / Breathe}), the name alone at the top level. */
    public String label(String folder, String name) {
        String path = path(folder);
        String shown = StringUtils.defaultString(name);
        return path.isEmpty() ? shown : path + SEPARATOR + shown;
    }

    /**
     * The items sorted by their labels, case insensitive: those of a folder together, after the items above them that
     * sort before the folder's name.
     */
    public <T> List<T> sortedByLabel(Collection<T> items, Function<T, String> folder, Function<T, String> name) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(item -> label(folder.apply(item), name.apply(item)), String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    /**
     * The name as a folder may have it: stripped; refused when blank or with a slash (the paths are written with them).
     *
     * @throws IllegalArgumentException when it can't be a folder's name
     */
    public static String validName(String name) {
        String stripped = StringUtils.strip(name);
        if (StringUtils.isBlank(stripped) || stripped.contains("/") || stripped.length() > 255) {
            throw new IllegalArgumentException("not a folder name: " + name);
        }
        return stripped;
    }

    /** Whether a folder in the parent (null: the top level) has the name, case insensitive, other than the one left out. */
    public boolean taken(String parent, String name, String except) {
        for (AnimationFolder folder : children(parent)) {
            if (!folder.getUuid().equals(except) && StringUtils.equalsIgnoreCase(folder.getName(), name)) {
                return true;
            }
        }
        return false;
    }
}
