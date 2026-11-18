package com.github.enerccio.joi.tts.model;

import java.util.UUID;

/**
 * A voice actor of the user: a name, the voice, and the voices for a mood or a language. Kept in the TTS settings of the user
 * ({@code TtsSettings}); Joi's voice will name one. Copied from the audiobook plugin of Marginalia.
 */
public class Actor extends Role {

    private String id = UUID.randomUUID().toString();
    private String name = "";
    /** A note of the user shown under the name in the pickers of an actor (the character they play, any tag); empty for none. */
    private String tagline = "";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTagline() {
        return tagline == null ? "" : tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }
}
