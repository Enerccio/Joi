package com.github.enerccio.joi.domain.persona;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the user sets for one personality, by its key: a built-in one they changed (a blank text keeps the built-in
 * one) or one of their own. Kept in the user setting ({@code JoiUserSetting.getPersonalities()}).
 */
public class PersonalitySetting {

    private String key;
    private String name;
    private String prompt;
    /** How she decides on a change of her parts; blank keeps the built-in one (none for the user's own). */
    private String partConsent;
    /** Mood key to how that mood sounds in this personality; a blank one keeps the built-in one. */
    private Map<String, String> moodExpressions = new LinkedHashMap<>();

    public PersonalitySetting() {
    }

    public PersonalitySetting(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getPartConsent() {
        return partConsent;
    }

    public void setPartConsent(String partConsent) {
        this.partConsent = partConsent;
    }

    public Map<String, String> getMoodExpressions() {
        if (moodExpressions == null) {
            moodExpressions = new LinkedHashMap<>();
        }
        return moodExpressions;
    }

    public void setMoodExpressions(Map<String, String> moodExpressions) {
        this.moodExpressions = moodExpressions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(moodExpressions);
    }
}
