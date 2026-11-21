package com.github.enerccio.joi.domain.workspace;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.marginalia.Configuration;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The temporary workspace of an assistant: a flat folder of text files that her tasks write ({@code write_file} of a task of
 * the stronger model) and that she can offer to the user's clipboard ({@code offer_file_to_clipboard}). It is a place to
 * hand a result over, not storage: a file is kept for {@link #KEEP}, at most {@link #MAX_FILES} files are kept (the oldest
 * go first), and a file is at most {@link #MAX_CHARS} characters of text.
 * <p>
 * The folder is {@code <data>/workspace/<uuid of the assistant>}. A name is a plain file name (letters, digits, dots,
 * dashes, underscores and spaces; no path), so nothing a model writes reaches outside the folder.
 */
public class TaskWorkspace {

    /** How long a file is kept after it was written. */
    public static final Duration KEEP = Duration.ofHours(24);
    /** The most files an assistant has in her workspace. */
    public static final int MAX_FILES = 30;
    /** The most characters of text in a file: what a node can take as a clipboard offer. */
    public static final int MAX_CHARS = 100_000;

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._ -]{0,79}");

    /** A file of the workspace. */
    public record Entry(String name, long size, Instant modified) {
    }

    /** The name or the content is not acceptable; the message says why (a model reads it and tries again). */
    public static class RejectedException extends Exception {
        public RejectedException(String message) {
            super(message);
        }
    }

    @Autowired
    private Configuration configuration;

    /** The name as it is kept, or the reason it is not a name. */
    public static String cleanName(String name) throws RejectedException {
        String clean = name == null ? "" : name.strip();
        if (!NAME.matcher(clean).matches() || clean.contains("..") || clean.endsWith(".") || clean.endsWith(" ")) {
            throw new RejectedException("The name of a file is up to 80 letters, digits, dots, dashes, underscores or spaces, "
                    + "starting with a letter or a digit, with no folder: " + name);
        }
        return clean;
    }

    /** Writes the file (replacing one of the same name); the oldest files go when there are too many. */
    public synchronized Entry write(Joi joi, String name, String content) throws RejectedException, IOException {
        String clean = cleanName(name);
        if (content == null || content.isEmpty()) {
            throw new RejectedException("The file is empty");
        }
        if (content.length() > MAX_CHARS) {
            throw new RejectedException("The file is too long (" + content.length() + " characters, at most " + MAX_CHARS + "): "
                    + "write it as shorter parts or leave out what is not needed");
        }
        Path folder = folder(joi);
        purge(folder);
        Path file = folder.resolve(clean);
        // the oldest go to make room for a new file (not when this one only replaces its own)
        if (!Files.exists(file)) {
            List<Entry> files = entries(folder);
            for (int i = files.size() - 1; i >= 0 && files.size() >= MAX_FILES; i--) {
                Files.deleteIfExists(folder.resolve(files.get(i).name()));
                files.remove(i);
            }
        }
        Path temp = Files.createTempFile(folder, ".write-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return entry(file);
    }

    /** The text of the file. */
    public String read(Joi joi, String name) throws RejectedException, IOException {
        Path file = folder(joi).resolve(cleanName(name));
        try {
            if (Files.size(file) > MAX_CHARS * 4L) {
                throw new RejectedException("The file is too long");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new RejectedException("There is no file " + name + " in the workspace");
        }
    }

    /** The files, the newest first; the ones that are too old are gone. */
    public synchronized List<Entry> list(Joi joi) throws IOException {
        Path folder = folder(joi);
        purge(folder);
        return entries(folder);
    }

    /** Deletes the file; false when there was none. */
    public synchronized boolean delete(Joi joi, String name) throws RejectedException, IOException {
        return Files.deleteIfExists(folder(joi).resolve(cleanName(name)));
    }

    private Path folder(Joi joi) throws IOException {
        return Files.createDirectories(configuration.getDataFolder().toPath().resolve("workspace").resolve(joi.getUuid()));
    }

    private static Entry entry(Path file) throws IOException {
        return new Entry(file.getFileName().toString(), Files.size(file), Files.getLastModifiedTime(file).toInstant());
    }

    /** The files, the newest first (a half-written file is not one). */
    private static List<Entry> entries(Path folder) throws IOException {
        List<Entry> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(file) && !file.getFileName().toString().startsWith(".")) {
                    files.add(entry(file));
                }
            }
        }
        files.sort(Comparator.comparing(Entry::modified).reversed().thenComparing(Entry::name));
        return files;
    }

    private static void purge(Path folder) throws IOException {
        Instant limit = Instant.now().minus(KEEP);
        for (Entry entry : entries(folder)) {
            if (entry.modified().isBefore(limit)) {
                Files.deleteIfExists(folder.resolve(entry.name()));
            }
        }
    }
}
