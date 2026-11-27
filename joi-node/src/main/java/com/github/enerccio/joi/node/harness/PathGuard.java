package com.github.enerccio.joi.node.harness;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Where a path she names really is, and what the policy says of it. A path is made absolute ({@code ~} is the user's home, a
 * relative one is under the first root, else the home), the longest part of it that exists is resolved with
 * {@link Path#toRealPath} and the rest appended: neither {@code ..} nor a link can lead out of a checked folder, and a call works on
 * the resolved path it checked. On macOS and Windows the checks ignore case, as their disks do.
 */
public final class PathGuard {

    private final Path userHome;
    private final Path nodeHome;
    private final boolean ignoreCase;
    private final List<Path> roots = new ArrayList<>();
    private final List<Path> denied = new ArrayList<>();
    private final List<Path> deniedWrites = new ArrayList<>();

    /**
     * @param userHome the user's home ({@code ~})
     * @param nodeHome the node's own home (its credentials, the policy, the audit): never read nor written, whatever the policy says
     */
    public PathGuard(Path userHome, Path nodeHome, Policy policy, boolean ignoreCase) {
        this.userHome = userHome.toAbsolutePath().normalize();
        this.ignoreCase = ignoreCase;
        this.nodeHome = real(nodeHome.toAbsolutePath());
        for (String root : policy.roots()) {
            Path path = entry(root);
            if (path != null) {
                roots.add(path);
            }
        }
        for (String path : policy.denied()) {
            Path p = entry(path);
            if (p != null) {
                denied.add(p);
            }
        }
        for (String path : policy.deniedWrites()) {
            Path p = entry(path);
            if (p != null) {
                deniedWrites.add(p);
            }
        }
    }

    /** True on the systems whose disks ignore case (macOS, Windows). */
    public static boolean caseInsensitiveSystem() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("win");
    }

    /** Where a relative path starts: the first root, else the user's home. */
    public Path base() {
        return roots.isEmpty() ? userHome : roots.getFirst();
    }

    public List<Path> roots() {
        return List.copyOf(roots);
    }

    public List<Path> denied() {
        List<Path> all = new ArrayList<>(denied);
        all.add(nodeHome);
        return all;
    }

    public Path userHome() {
        return userHome;
    }

    /**
     * The path she names, resolved (see the class).
     *
     * @throws IllegalArgumentException not a path
     */
    public Path resolve(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Give a path.");
        }
        if (input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A path has no NUL character.");
        }
        Path path;
        try {
            path = expand(input.strip(), base());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a path: " + e.getMessage());
        }
        return real(path);
    }

    /** Never read nor written: a denied path, or the node's own home. */
    public boolean denied(Path path) {
        if (within(path, nodeHome)) {
            return true;
        }
        for (Path d : denied) {
            if (within(path, d)) {
                return true;
            }
        }
        return false;
    }

    /** Never written: denied, or under a folder the policy keeps from writes. */
    public boolean writeDenied(Path path) {
        if (denied(path)) {
            return true;
        }
        for (Path d : deniedWrites) {
            if (within(path, d)) {
                return true;
            }
        }
        return false;
    }

    /** Inside one of the user's folders. */
    public boolean inRoots(Path path) {
        for (Path root : roots) {
            if (within(path, root)) {
                return true;
            }
        }
        return false;
    }

    /** The path is the folder or under it (by names, not by the letters: {@code /a/bc} is not under {@code /a/b}). */
    public boolean within(Path path, Path folder) {
        String p = text(path);
        String f = text(folder);
        if (p.equals(f)) {
            return true;
        }
        String sep = path.getFileSystem().getSeparator();
        return p.startsWith(f.endsWith(sep) ? f : f + sep);
    }

    private String text(Path path) {
        String s = path.toString();
        return ignoreCase ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** An entry of the policy, resolved like her paths but relative to the user's home; null when it is not a path. */
    private Path entry(String value) {
        try {
            return real(expand(value.strip(), userHome));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private Path expand(String value, Path relativeTo) {
        Path path;
        if (value.equals("~")) {
            path = userHome;
        } else if (value.startsWith("~/") || value.startsWith("~\\")) {
            path = userHome.resolve(value.substring(2));
        } else {
            path = Path.of(value);
            if (!path.isAbsolute()) {
                path = relativeTo.resolve(path);
            }
        }
        return path.toAbsolutePath().normalize();
    }

    /** The longest part that exists, resolved to the real path, and the rest appended. */
    static Path real(Path absolute) {
        Path existing = absolute.normalize();
        Deque<Path> rest = new ArrayDeque<>();
        while (existing != null) {
            try {
                Path resolved = existing.toRealPath();
                for (Path name : rest) {
                    resolved = resolved.resolve(name);
                }
                return resolved;
            } catch (IOException | SecurityException e) {
                // missing, or not to be resolved (no permission): its parent is resolved and the name appended
                if (existing.getFileName() == null) {
                    break;
                }
                rest.push(existing.getFileName());
                existing = existing.getParent();
            }
        }
        return absolute.normalize();
    }
}
