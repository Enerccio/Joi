package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.*;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.domain.templates.JoiTemplateData;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Who the assistant of the current user is: her name, the texts of the prompts, the personalities and moods, each
 * resolved along the chain (the user's setting, else the built-in default of {@link JoiDefaults}), and the rendering of
 * the templates.
 */
public interface PersonaService {

    /** The user setting of the current user. */
    JoiUserSetting getUserSetting() throws Exception;

    /** The built-in texts in the user's prompt language. */
    JoiDefaults defaults() throws Exception;

    /** The assistant's name: the user's setting, else {@link JoiDefaults#ASSISTANT_NAME}. */
    String assistantName() throws Exception;

    /** The name the assistant calls the user: the user's setting, else {@link #accountName()}. */
    String userName() throws Exception;

    /** The name of the account: the full name, else the login (the default of {@link #userName()}). */
    String accountName();

    /** The text of the field to use. */
    String resolve(JoiPromptField field) throws Exception;

    /** The personalities, the built-in ones first in their order, then the user's own by name. */
    List<Personality> personalities() throws Exception;

    /** The personality with the key, null when there is none. */
    Personality personality(String key) throws Exception;

    /** The user's personality, else the default one. */
    Personality currentPersonality() throws Exception;

    /** The personality of the assistant: hers, else the user's. */
    Personality personalityOf(Joi joi) throws Exception;

    /**
     * What she is told when the user asks to change parts of her body and her personality lets her decide on it
     * ({@code PART_REQUEST_EVENT}); null when it does not: such a change is just made.
     *
     * @param requests what is asked, one request on a line
     * @param events   what else happened to her meanwhile, one event on a line, null for nothing
     */
    String renderPartRequests(Joi joi, String requests, String events) throws Exception;

    /** Stores the user's version of a personality (a built-in key changes it, another key is the user's own one). */
    void savePersonality(PersonalitySetting setting) throws Exception;

    /**
     * Makes the user's own personality of what the personality is now (a built-in one with the user's changes, or the
     * user's own), under a new key.
     *
     * @param name the name of the copy, null for the name of the personality
     * @return the key of the copy
     */
    String clonePersonality(String key, String name) throws Exception;

    /** Forgets the user's version: a built-in one is back to its default, the user's own one is deleted. */
    void resetPersonality(String key) throws Exception;

    /** The moods, the built-in ones first in their order, then the user's own by name. */
    List<Mood> moods() throws Exception;

    /** The mood with the key, null when there is none. */
    Mood mood(String key) throws Exception;

    /** Stores the user's version of a mood (a built-in key changes it, another key is the user's own one). */
    void saveMood(MoodSetting setting) throws Exception;

    /** Forgets the user's version: a built-in one is back to its default, the user's own one is deleted. */
    void resetMood(String key) throws Exception;

    /** How the mood sounds in the personality: the personality's expression of it, else the mood's own text. */
    String moodText(Personality personality, Mood mood);

    /** Rolls the mood of the day by the weights; the default mood when every weight is 0. */
    Mood rollMood(RandomGenerator random) throws Exception;

    /** Renders the resolved template of the field with the data (the names are set here). */
    String render(JoiPromptField field, JoiTemplateData data) throws Exception;

    /**
     * Renders the template of the field resolved along the assistant's chain (hers, else the user setting, else the
     * default) with the data, with her names.
     */
    String render(Joi joi, JoiPromptField field, JoiTemplateData data) throws Exception;

    /**
     * Renders the system prompt of a chat: the user's personality and the mood (as it sounds in that personality) are
     * rendered and put into the data, then the master template.
     *
     * @param moodKey the mood of the day, null or unknown for the default one
     * @param data    the rest of the data (memories, profile, body...); its personality and mood are set here
     */
    String renderChatSystemPrompt(String moodKey, ChatTemplateData data) throws Exception;

    /**
     * The same with a master template given (an edited one not saved yet, for a preview); blank uses the resolved one.
     */
    String renderChatSystemPrompt(String template, String moodKey, ChatTemplateData data) throws Exception;

    /**
     * The system prompt of a chat with the assistant: her name, what she calls the user, her personality and her master
     * template, each along the chain (hers, else the user's setting, else the default).
     *
     * @param joi      the assistant, null for the user's settings alone
     * @param template the master template to use (an edited one not saved yet), blank for the resolved one
     */
    String renderChatSystemPrompt(Joi joi, String template, String moodKey, ChatTemplateData data) throws Exception;
}
