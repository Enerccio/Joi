package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.tts.ServerCheck;
import com.github.enerccio.joi.tts.SpeechBackend;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.tts.model.TtsSettings;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The speech of the current user: their TTS models (speech servers), their voice actors and the reference clips the actors speak
 * with (the user's audio resources). Everything is kept in their user setting ({@link TtsSettings}); a call to a server takes the
 * GPU lock of its model for its length (see {@code GpuService}).
 */
public interface TtsService {

    /** A reference clip of the user: an audio resource, by uuid and file name. */
    record Clip(String uuid, String name) {
    }

    /** The settings of the current user (a copy: change it through the methods here). */
    TtsSettings getSettings() throws Exception;

    /** The TTS models of the user, by name. */
    List<TtsModel> models() throws Exception;

    /** The model with the id, null when the user has none. */
    TtsModel findModel(String id) throws Exception;

    /** Stores the model (a new one is added, one with the same id replaced). */
    void saveModel(TtsModel model) throws Exception;

    /** Removes the model. */
    void deleteModel(String id) throws Exception;

    /** Stores a copy of the model under the name and returns it. */
    TtsModel cloneModel(String id, String name) throws Exception;

    /** The voice actors of the user, by name. */
    List<Actor> actors() throws Exception;

    /** Stores the actor (a new one is added, one with the same id replaced). */
    void saveActor(Actor actor) throws Exception;

    /** Removes the actor. */
    void deleteActor(String id) throws Exception;

    /** The audio resources of the user, by name: what a voice of an actor can be ({@code clip:<uuid>}). */
    List<Clip> clips() throws Exception;

    /** Puts a clip among the resources of the user. */
    Clip uploadClip(String fileName, byte[] content, String contentType) throws Exception;

    /** The words spoken in the clip, null when they are not known. */
    String clipText(String clipUuid) throws Exception;

    /** Sets (or with a blank text removes) the words spoken in the clip. */
    void saveClipText(String clipUuid, String text) throws Exception;

    /** The key protected for storing in a model, null for a blank one. */
    String protect(String secret);

    /** The client of the model's server, with its key opened. */
    SpeechBackend createBackend(TtsModel model);

    /**
     * Asks the server of the model (as it is in the form) whether it is there and has a model loaded, under its GPU lock: the lock's
     * start-up steps run first when the GPU holds something else. Nothing is spoken. Runs on a thread of its own.
     */
    CompletableFuture<ServerCheck.Result> test(TtsModel model);

    /**
     * Speaks the text with the voice ({@code clip:<uuid>} or {@code predefined:<file>}) on the model's server, under its GPU lock. The
     * clip is read here (on the caller's thread, which knows the user); the speech is made on a thread of its own.
     *
     * @return the audio, in the format of the model
     */
    CompletableFuture<byte[]> speak(TtsModel model, String voice, String text) throws Exception;
}
