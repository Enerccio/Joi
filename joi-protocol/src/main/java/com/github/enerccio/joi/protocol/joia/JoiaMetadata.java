package com.github.enerccio.joi.protocol.joia;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a {@code .joia} holds besides its clips' files (documented in {@code joi-protocol/joia.md}): the animations, each
 * steps of clips, and the clips, in the order of the file's {@code VRMA} sections. Clips and steps are linked by the
 * clip's uuid.
 *
 * @param animations the animations, in the order they were chosen
 * @param clips      the clips the animations play, each once
 * @param exportedAt when the file was written (ISO 8601), null when not known
 */
public record JoiaMetadata(List<Animation> animations, List<Clip> clips, String exportedAt) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public JoiaMetadata {
        animations = animations == null ? List.of() : List.copyOf(animations.stream().filter(a -> a != null).toList());
        clips = clips == null ? List.of() : List.copyOf(clips.stream().filter(c -> c != null).toList());
    }

    /**
     * An animation: steps of clips.
     *
     * @param uuid        its uuid where it was exported (an import makes a new animation)
     * @param loop        whether it starts again after its last step
     * @param steps       its steps, in order
     */
    public record Animation(String uuid, String name, String description, Boolean loop, List<Step> steps) {

        public Animation {
            steps = steps == null ? List.of() : List.copyOf(steps.stream().filter(s -> s != null && s.clip() != null).toList());
        }
    }

    /**
     * A step of an animation.
     *
     * @param clip    the uuid of its clip (one of {@link JoiaMetadata#clips()})
     * @param loop    whether it repeats until the animation is finished
     * @param speed   how fast it plays, 1 for as made
     * @param inPlace whether her hips keep their spot on the floor
     */
    public record Step(String clip, boolean loop, double speed, boolean inPlace) {
    }

    /**
     * A clip: a VRMA, its file in the {@code VRMA} section of the same index.
     *
     * @param uuid        its uuid: an import links a clip of the same uuid it already has instead of adding it again
     * @param tags        lowercase
     * @param loop        what a new step of it starts from, null for no
     * @param inPlace     as the loop
     * @param speed       as the loop, null for 1
     * @param fileName    the name of its VRMA
     * @param source      the FBX or BVH it was converted from, null for none
     * @param take        the take of the source, null for none
     * @param duration    how long it is in seconds
     * @param bones       the humanoid bones it moves (VRM names)
     * @param expressions whether it moves expressions too
     * @param size        the bytes of its VRMA
     * @param sha256      the SHA-256 of its VRMA in hex (the section's hash)
     */
    public record Clip(String uuid, String name, String description, List<String> tags, Boolean loop, Boolean inPlace,
                       Double speed, String fileName, String source, String take, Double duration, List<String> bones,
                       Boolean expressions, Long size, String sha256) {

        public Clip {
            tags = tags == null ? List.of() : List.copyOf(tags.stream().filter(t -> t != null).toList());
            bones = bones == null ? List.of() : List.copyOf(bones.stream().filter(b -> b != null).toList());
        }
    }

    /** The clip of the uuid, null for none. */
    public Clip clip(String uuid) {
        for (Clip clip : clips) {
            if (clip.uuid() != null && clip.uuid().equals(uuid)) {
                return clip;
            }
        }
        return null;
    }

    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }

    /**
     * Reads the metadata; missing keys are empty.
     *
     * @throws IOException when it is not the metadata of a {@code .joia}
     */
    public static JoiaMetadata fromJson(JsonObject json) throws IOException {
        try {
            JoiaMetadata meta = GSON.fromJson(json, JoiaMetadata.class);
            if (meta == null) {
                return new JoiaMetadata(List.of(), List.of(), null);
            }
            List<String> uuids = new ArrayList<>();
            for (Clip clip : meta.clips()) {
                if (clip.uuid() == null || uuids.contains(clip.uuid())) {
                    throw new IOException("a clip without a uuid, or two of one uuid");
                }
                uuids.add(clip.uuid());
            }
            return meta;
        } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new IOException("the metadata is not of animations: " + e.getMessage(), e);
        }
    }
}
