package com.github.enerccio.joi.domain.bodies;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The files of an upload unpacked for a conversion, in a work folder: the FBX, its {@code .meta}, the Unity materials
 * and the textures, at their paths in the zip or the package. A package's other assets (animations, sounds, prefabs,
 * scripts) are left out. It also reads what Unity knew of them: the GUID of each file (from the {@code .meta} files, or
 * the folders of a package), so the materials find their textures and the FBX its materials.
 */
public final class ConversionSource {

    /** What a conversion needs from a package; everything else stays packed. */
    private static final Set<String> NEEDED = Set.of("fbx", "mat", "png", "jpg", "jpeg", "tga", "tif", "tiff", "bmp", "psd", "exr");

    private static final Pattern GUID = Pattern.compile("^guid: ([0-9a-f]{32})", Pattern.MULTILINE);
    private static final Pattern REF_GUID = Pattern.compile("guid: ([0-9a-f]{32})");

    private final Path root;
    private final Map<String, String> pathsByGuid;

    private ConversionSource(Path root, Map<String, String> pathsByGuid) {
        this.root = root;
        this.pathsByGuid = pathsByGuid;
    }

    /** The folder the files are in. */
    public Path root() {
        return root;
    }

    /** The path (relative to {@link #root()}) of the file with the GUID, null when it was not unpacked. */
    public String pathOf(String guid) {
        String path = pathsByGuid.get(guid);
        return path != null && Files.isRegularFile(root.resolve(path)) ? path : null;
    }

    /**
     * Unpacks the upload into the folder.
     *
     * @param upload   the uploaded file
     * @param fileName its name (tells a zip, a package or a single file)
     */
    public static ConversionSource unpack(File upload, String fileName, Path root) throws IOException {
        Files.createDirectories(root);
        Map<String, String> paths = new HashMap<>();
        if (ModelFiles.isUnityPackage(fileName)) {
            // a package stores the asset before its pathname: the paths are read first, then what is needed is unpacked
            ModelFiles.forEachPackageEntry(upload, (guid, part, entry, in) -> {
                if (part.equals("pathname")) {
                    String path = safe(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().findFirst().orElse(""));
                    if (path != null) {
                        paths.put(guid, path);
                    }
                }
            });
            ModelFiles.forEachPackageEntry(upload, (guid, part, entry, in) -> {
                String path = paths.get(guid);
                if (path == null || !needed(path)) {
                    return;
                }
                if (part.equals("asset")) {
                    copy(root, path, in);
                } else if (part.equals("asset.meta")) {
                    copy(root, path + ".meta", in);
                }
            });
        } else if (ModelFiles.isZip(fileName)) {
            try (ZipFile zip = new ZipFile(upload)) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    String path = safe(entry.getName());
                    if (entry.isDirectory() || path == null || ModelFiles.isJunk(path)) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        copy(root, path, in);
                    }
                }
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path meta : files.filter(p -> p.getFileName().toString().endsWith(".meta")).toList()) {
                    Matcher matcher = GUID.matcher(Files.readString(meta, StandardCharsets.UTF_8));
                    if (matcher.find()) {
                        String path = root.relativize(meta).toString().replace(File.separatorChar, '/');
                        paths.put(matcher.group(1), path.substring(0, path.length() - ".meta".length()));
                    }
                }
            }
        } else {
            String path = safe(fileName);
            Files.copy(upload.toPath(), root.resolve(path == null ? "model.fbx" : path), StandardCopyOption.REPLACE_EXISTING);
        }
        return new ConversionSource(root, paths);
    }

    /**
     * The Unity materials of the source, for the converter: {@code {path of the .mat: {name, file, textures: {property:
     * path}, colors: {property: [r, g, b, a]}, floats: {property: value}}}}, the paths relative to {@link #root()}.
     */
    public Map<String, Map<String, Object>> materials() throws IOException {
        Map<String, Map<String, Object>> materials = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mat")).sorted().toList()) {
                String path = root.relativize(file).toString().replace(File.separatorChar, '/');
                Map<String, Object> material = UnityMaterial.parse(Files.readString(file, StandardCharsets.UTF_8), this::pathOf);
                if (material != null) {
                    material.put("file", path);
                    materials.put(path, material);
                }
            }
        }
        return materials;
    }

    /**
     * Which Unity material each material of the FBX uses, from the remapping in the FBX's {@code .meta}
     * ({@code externalObjects}): {@code {FBX material: path of the .mat}}. Empty when the FBX has no {@code .meta}; the
     * converter then matches the materials by name.
     */
    public Map<String, String> materialMap(String fbx) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        Path meta = root.resolve(fbx + ".meta");
        if (!Files.isRegularFile(meta)) {
            return map;
        }
        List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        String name = null;
        boolean material = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- first:")) {
                name = null;
                material = false;
            } else if (trimmed.startsWith("type: UnityEngine:Material")) {
                material = true;
            } else if (trimmed.startsWith("name: ") && material) {
                name = trimmed.substring("name: ".length()).strip();
            } else if (trimmed.startsWith("second:") && material && name != null) {
                Matcher matcher = REF_GUID.matcher(trimmed);
                String path = matcher.find() ? pathOf(matcher.group(1)) : null;
                if (path != null) {
                    map.put(name, path);
                }
                name = null;
                material = false;
            }
        }
        return map;
    }

    /** The path when it stays inside the folder ({@code a/b.png}), null otherwise. */
    static String safe(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.matches("^[A-Za-z]:.*")) {
            return null;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.equals("..") || part.equals(".")) {
                return null;
            }
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static boolean needed(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && NEEDED.contains(lower.substring(dot + 1));
    }

    private static void copy(Path root, String path, InputStream in) throws IOException {
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root.normalize())) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
