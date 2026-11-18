package com.github.enerccio.joi.ui.tts;

import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.domain.service.TtsService.Clip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reference clips of the user, read once for all the voice fields of a dialog; a field that adds a clip tells the others.
 * Copied from Marginalia's audiobook plugin.
 */
public class VoiceChoices {

    private final TtsService service;
    private final List<Clip> clips;
    private final List<Runnable> listeners = new ArrayList<>();

    public VoiceChoices(TtsService service) throws Exception {
        this.service = service;
        this.clips = new ArrayList<>(service.clips());
    }

    public TtsService service() {
        return service;
    }

    public List<Clip> clips() {
        return List.copyOf(clips);
    }

    /** Puts a clip among the resources of the user and into the choices. */
    public Clip upload(String fileName, byte[] content, String contentType) throws Exception {
        Clip clip = service.uploadClip(fileName, content, contentType);
        clips.add(clip);
        clips.sort(Comparator.comparing(Clip::name, String.CASE_INSENSITIVE_ORDER));
        new ArrayList<>(listeners).forEach(Runnable::run);
        return clip;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** What the user sees for a voice: the name of the clip, or the file of the server's voice. */
    public String label(String voice) {
        if (voice == null || voice.isBlank()) {
            return "";
        }
        if (voice.startsWith("clip:")) {
            String uuid = voice.substring("clip:".length());
            return clips.stream().filter(c -> c.uuid().equals(uuid)).map(c -> "♪ " + c.name()).findFirst().orElse("♪ ?");
        }
        return voice.startsWith("predefined:") ? voice.substring("predefined:".length()) : voice;
    }
}
