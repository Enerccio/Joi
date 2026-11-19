package com.github.enerccio.joi.domain.bodies;

import com.github.enerccio.joi.domain.collections.ModelFormat;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * What an uploaded file of a model is:
 * <ul>
 *     <li>one model file (.vrm, .glb, .gltf, .fbx);</li>
 *     <li>a zip of a model and the files it needs (textures, buffers, the {@code .fbx.meta} and {@code .mat} files of a
 *     Unity project);</li>
 *     <li>a Unity package ({@code .unitypackage}, how VRChat avatars are sold): a tar.gz of assets, each in a folder
 *     named by its GUID with its {@code pathname}, {@code asset} and {@code asset.meta}.</li>
 * </ul>
 * In a zip or a package the main file is the avatar: a VRM before a GLB, a glTF, an FBX; among the FBX files the one
 * Unity imports as a humanoid (its {@code .meta} has {@code animationType: 3} and the bones of
 * {@code humanDescription}), the largest of them; then the one nearest to the root.
 */
public final class ModelFiles {

    /** A zip of a model and its files. */
    public static final String ZIP_CONTENT_TYPE = "application/zip";
    /** A Unity package. */
    public static final String UNITY_PACKAGE_CONTENT_TYPE = "application/gzip";

    /** Unity's required humanoid bones: fewer is no avatar. */
    private static final int MIN_HUMAN_BONES = 15;

    private static final Pattern HUMAN_BONE = Pattern.compile("^\\s+humanName: ", Pattern.MULTILINE);

    private ModelFiles() {
    }

    /**
     * A new file in the folder for an upload to stream into, named like the platform names resources (a random name
     * with the extension of the user's file).
     */
    public static File uploadFile(File folder, String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return new File(folder, UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension));
    }

    /**
     * @param format      the format of the model (of the main file in a zip or a package)
     * @param mainFile    the path of the main file inside the zip or the package, null for a single file
     * @param fileCount   how many files: 1, or the files of the zip or the assets of the package
     * @param contentType what the stored file is (the format's, a zip or a Unity package)
     * @param fbxFiles    the FBX files it has (paths inside the zip or the package, the file name of a single FBX): the
     *                    candidates for the avatar a conversion reads, the main one first
     */
    public record Inspection(ModelFormat format, String mainFile, int fileCount, String contentType, List<String> fbxFiles) {
    }

    /** Whether the name is of a Unity package. */
    public static boolean isUnityPackage(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".unitypackage");
    }

    /** Whether the name is of a zip. */
    public static boolean isZip(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /**
     * @param fileName the name the user's file had (its extension tells a zip or a package from a model)
     * @return what the file is, null when it is not a model, nor a zip or a package with one
     */
    public static Inspection inspect(File file, String fileName) throws IOException {
        ModelFormat format = ModelFormat.ofFileName(fileName);
        if (format == ModelFormat.JOIM) {
            // a Joi model is imported, not attached (JoimService)
            return null;
        }
        if (format != null) {
            return new Inspection(format, null, 1, format.contentType(), format == ModelFormat.FBX ? List.of(fileName) : List.of());
        }
        if (isZip(fileName)) {
            return inspectZip(file);
        }
        if (isUnityPackage(fileName)) {
            return inspectUnityPackage(file);
        }
        return null;
    }

    /** Whether a file of a zip or a package is a model (a Joi model in one is not read). */
    private static boolean isModelFile(String path) {
        ModelFormat format = ModelFormat.ofFileName(path);
        return format != null && format != ModelFormat.JOIM;
    }

    /** A file of a zip or a package: its path, size, and the text of its {@code .meta} when it is an FBX. */
    private record Candidate(String path, long size, String meta) {
    }

    private static Inspection inspectZip(File file) throws IOException {
        List<Candidate> models = new ArrayList<>();
        int files = 0;
        try (ZipFile zip = new ZipFile(file)) {
            Map<String, ZipEntry> byName = new HashMap<>();
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && !isJunk(entry.getName())) {
                    byName.put(entry.getName(), entry);
                }
            }
            for (ZipEntry entry : byName.values()) {
                files++;
                if (!isModelFile(entry.getName())) {
                    continue;
                }
                String meta = null;
                ZipEntry metaEntry = byName.get(entry.getName() + ".meta");
                if (metaEntry != null && metaEntry.getSize() < 8 * 1024 * 1024) {
                    try (InputStream in = zip.getInputStream(metaEntry)) {
                        meta = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                models.add(new Candidate(entry.getName(), entry.getSize(), meta));
            }
        } catch (ZipException e) {
            return null;
        }
        return choose(models, files, ZIP_CONTENT_TYPE);
    }

    private static Inspection inspectUnityPackage(File file) throws IOException {
        Map<String, String> paths = new HashMap<>();
        Map<String, Long> sizes = new HashMap<>();
        Map<String, String> metas = new HashMap<>();
        try {
            forEachPackageEntry(file, (guid, part, entry, in) -> {
                switch (part) {
                    case "pathname" -> paths.put(guid, firstLine(in));
                    case "asset" -> sizes.put(guid, entry.getSize());
                    case "asset.meta" -> {
                        if (entry.getSize() < 8 * 1024 * 1024) {
                            metas.put(guid, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    default -> {
                    }
                }
            });
        } catch (IOException e) {
            return null;
        }
        List<Candidate> models = new ArrayList<>();
        for (Map.Entry<String, String> path : paths.entrySet()) {
            if (sizes.containsKey(path.getKey()) && isModelFile(path.getValue())) {
                models.add(new Candidate(path.getValue(), sizes.get(path.getKey()), metas.get(path.getKey())));
            }
        }
        return choose(models, sizes.size(), UNITY_PACKAGE_CONTENT_TYPE);
    }

    private static Inspection choose(List<Candidate> models, int files, String contentType) {
        Comparator<Candidate> best = Comparator
                .comparingInt((Candidate c) -> ModelFormat.ofFileName(c.path()).ordinal())
                // a humanoid rig first (props and particles have none); the bone counts of humanoids are close, so the
                // avatar is the largest of them (helpers like GoGo Loco's bones are small)
                .thenComparing((Candidate c) -> humanBones(c.meta()) < MIN_HUMAN_BONES)
                .thenComparing(Comparator.comparingLong(Candidate::size).reversed())
                .thenComparingInt(c -> c.path().split("/").length)
                .thenComparing(Candidate::path);
        List<Candidate> sorted = models.stream().sorted(best).toList();
        if (sorted.isEmpty()) {
            return null;
        }
        Candidate main = sorted.getFirst();
        List<String> fbx = sorted.stream().filter(c -> ModelFormat.ofFileName(c.path()) == ModelFormat.FBX).map(Candidate::path).toList();
        return new Inspection(ModelFormat.ofFileName(main.path()), main.path(), files, contentType, fbx);
    }

    /** How many humanoid bones Unity maps in the model's {@code .meta}, 0 when it is not a humanoid. */
    static int humanBones(String meta) {
        if (meta == null || !meta.contains("animationType: 3")) {
            return 0;
        }
        int count = 0;
        Matcher matcher = HUMAN_BONE.matcher(meta);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** What macOS and Windows add to a zip: never a part of the model. */
    static boolean isJunk(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        return name.startsWith("__MACOSX/") || name.contains("/__MACOSX/") || base.equals(".DS_Store") || base.equals("Thumbs.db")
                || base.startsWith("._");
    }

    /** One entry of a Unity package: {@code <guid>/<part>}. */
    @FunctionalInterface
    public interface PackageVisitor {
        void visit(String guid, String part, TarArchiveEntry entry, InputStream content) throws IOException;
    }

    /** Reads the entries of a Unity package in the order they are stored. */
    public static void forEachPackageEntry(File file, PackageVisitor visitor) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file.toPath()), 1 << 16);
             TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw, 1 << 16))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                int slash = name.indexOf('/');
                if (slash <= 0 || name.indexOf('/', slash + 1) >= 0) {
                    continue;
                }
                visitor.visit(name.substring(0, slash), name.substring(slash + 1), entry, tar);
            }
        }
    }

    private static String firstLine(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).strip();
    }
}
