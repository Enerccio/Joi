package com.github.enerccio.joi.node.renderer;

import com.github.enerccio.joi.protocol.joia.JoiaFile;
import com.github.enerccio.joi.protocol.joia.JoiaMetadata;
import com.github.enerccio.joi.protocol.joim.JoimFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Her body as the renderer reads it: the VRM out of her {@code .joim} and the clips out of her {@code .joia}, unpacked
 * once into a folder of the cache named by the files' hashes, with the animations of the {@code .joia}. Either file
 * may be missing (no model, or no animations).
 */
public final class BodyFiles {

    private static final String DONE = ".unpacked";

    private final Path model;
    private final Map<String, JoiaMetadata.Animation> animations = new HashMap<>();
    private final Map<String, Path> clips = new HashMap<>();
    private final Map<String, Double> lengths = new HashMap<>();

    private BodyFiles(Path model) {
        this.model = model;
    }

    /** No body: the renderer shows nothing. */
    public static BodyFiles none() {
        return new BodyFiles(null);
    }

    /**
     * Unpacks her files into {@code into} (left as they are when an earlier run unpacked them).
     *
     * @param joim her model's file, null for none
     * @param joia her animations' file, null for none
     */
    public static BodyFiles unpack(Path joim, Path joia, Path into) throws IOException {
        Path model = null;
        if (joim != null) {
            Path dir = into.resolve(stem(joim));
            model = dir.resolve("model.vrm");
            if (!Files.exists(dir.resolve(DONE))) {
                Files.createDirectories(dir);
                Path temp = Files.createTempFile(dir, "model", ".part");
                try {
                    JoimFile.Contents contents = JoimFile.read(joim.toFile(), temp.toFile(), bytes -> {
                    });
                    if (!contents.hasModel()) {
                        throw new IOException("her model's file holds no model");
                    }
                    Files.move(temp, model, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
                Files.writeString(dir.resolve(DONE), "");
            }
        }
        BodyFiles files = new BodyFiles(model);
        if (joia != null) {
            Path dir = into.resolve(stem(joia));
            boolean unpacked = Files.exists(dir.resolve(DONE));
            Files.createDirectories(dir);
            JoiaFile.Contents contents = JoiaFile.read(joia.toFile(), new JoiaFile.Reader() {
                @Override
                public File clip(int index, JoiaMetadata.Clip clip) {
                    return unpacked ? null : dir.resolve(index + ".vrma").toFile();
                }
            });
            List<JoiaMetadata.Clip> clips = contents.meta().clips();
            for (int i = 0; i < clips.size(); i++) {
                files.clips.put(clips.get(i).uuid(), dir.resolve(i + ".vrma"));
                if (clips.get(i).duration() != null) {
                    files.lengths.put(clips.get(i).uuid(), clips.get(i).duration());
                }
            }
            for (JoiaMetadata.Animation animation : contents.meta().animations()) {
                files.animations.put(animation.uuid(), animation);
            }
            if (!unpacked) {
                Files.writeString(dir.resolve(DONE), "");
            }
        }
        return files;
    }

    /** Her VRM, null for none. */
    public Path model() {
        return model;
    }

    /** An animation of the {@code .joia} by its uuid, null for one it does not hold. */
    public JoiaMetadata.Animation animation(String uuid) {
        return uuid == null ? null : animations.get(uuid);
    }

    /** The animations of the {@code .joia}. */
    public java.util.Collection<JoiaMetadata.Animation> animations() {
        return java.util.Collections.unmodifiableCollection(animations.values());
    }

    /** A clip's length in seconds by its uuid, null when it does not say. */
    public Double clipLength(String uuid) {
        return uuid == null ? null : lengths.get(uuid);
    }

    /** A clip's file by its uuid, null for one the {@code .joia} does not hold. */
    public Path clip(String uuid) {
        return uuid == null ? null : clips.get(uuid);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "-" + (dot < 0 ? "bin" : name.substring(dot + 1));
    }
}
