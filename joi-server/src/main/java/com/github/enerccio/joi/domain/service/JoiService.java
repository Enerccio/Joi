package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.QueuedEvent;
import com.github.enerccio.joi.domain.repository.JoiRepository;
import com.github.enerccio.marginalia.domain.service.ExtendableService;

import java.util.List;

/** The assistants of a user. */
public interface JoiService extends ExtendableService<Joi, JoiRepository> {

    /**
     * Who an assistant is as she sees it: her name, what she calls the user and her personality, each resolved along the
     * chain (hers, else the user's setting, else the default).
     *
     * @param personalityKey  the key of the personality
     * @param personalityName its name
     * @param voiceActorId     the id of her voice actor, null for none
     * @param voiceActorName   its name
     * @param voiceDescription how it sounds, blank for not described
     */
    record Persona(String assistantName, String userName, String personalityKey, String personalityName, String voiceActorId,
                   String voiceActorName, String voiceDescription) {
    }

    /** A new assistant of the current user with the name. */
    Joi create(String name) throws Exception;

    /** The persona of the assistant, resolved. */
    Persona persona(Joi joi) throws Exception;

    /** The assistants of the current user, by name. */
    List<Joi> findAllByName() throws Exception;

    /** Her name: her own, else the user's setting, else the default. */
    String name(Joi joi) throws Exception;

    /**
     * Saves the assistant after a change of her About, and tells her what changed of her persona: each change of her
     * name, of what she calls the user, of her personality or of her voice, compared with {@code before}, is queued as an event. Only
     * when she has chatted already: before that it is the initial setup, nothing she would react to.
     *
     * @param before her persona when the editing began ({@link #persona(Joi)})
     * @return the events queued, empty when none
     */
    List<QueuedEvent> saveAbout(Joi joi, Persona before) throws Exception;

    /**
     * Changes her as stored now: reads her fresh, applies {@code change} and saves her, in one write transaction. The way to
     * change her from a copy read earlier (a dialog, a turn), so what was saved since (her mood of the day, the user's edit)
     * is not put back.
     */
    Joi update(Joi joi, java.util.function.Consumer<Joi> change) throws Exception;

    /** Writes her mood of the day through {@link #update}, and onto {@code joi} too. */
    Joi saveMood(Joi joi, String mood, java.util.Date since) throws Exception;
}
