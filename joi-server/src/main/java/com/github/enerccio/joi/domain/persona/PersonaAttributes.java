package com.github.enerccio.joi.domain.persona;

import java.util.List;

/**
 * The template attributes of an assistant: her name, what she calls the user, the prompts, the personality and the
 * moods, the models of the roles, the voice and the day. Two links of the chain of a setting carry them, the
 * assistant ({@code Joi}) and the user setting ({@code JoiUserSetting}); a blank or null value is not set, and the
 * next link is asked (see {@link JoiPromptField} and {@code PersonaService}).
 */
public interface PersonaAttributes {

    /** The name of the assistant ({@code {{char}}}), blank when not set. */
    String getAssistantName();

    void setAssistantName(String assistantName);

    /** What the assistant calls the user ({@code {{user}}}), blank when not set. */
    String getUserName();

    void setUserName(String userName);

    String getMasterTemplate();

    void setMasterTemplate(String masterTemplate);

    String getSummaryPrompt();

    void setSummaryPrompt(String summaryPrompt);

    String getDaySummaryPrompt();

    void setDaySummaryPrompt(String daySummaryPrompt);

    String getFactsPrompt();

    void setFactsPrompt(String factsPrompt);

    String getSelfImagePrompt();

    void setSelfImagePrompt(String selfImagePrompt);

    String getPartImagePrompt();

    void setPartImagePrompt(String partImagePrompt);

    String getBodyPrompt();

    void setBodyPrompt(String bodyPrompt);

    String getFirstBootEvent();

    void setFirstBootEvent(String firstBootEvent);

    String getConnectedEvent();

    void setConnectedEvent(String connectedEvent);

    String getLookingGlassEnteredEvent();

    void setLookingGlassEnteredEvent(String lookingGlassEnteredEvent);

    String getLookingGlassExitedEvent();

    void setLookingGlassExitedEvent(String lookingGlassExitedEvent);

    /** The key of the personality, blank when not set. */
    String getPersonality();

    void setPersonality(String personality);

    /** The changes of the built-in personalities and the own ones, by key. */
    List<PersonalitySetting> getPersonalities();

    void setPersonalities(List<PersonalitySetting> personalities);

    /** The changes of the built-in moods and the own ones, by key. */
    List<MoodSetting> getMoods();

    void setMoods(List<MoodSetting> moods);

    /** The AI of the conversation role, null when not set. */
    Long getChatModel();

    void setChatModel(Long chatModel);

    /** The AI of the heavy role, null when not set. */
    Long getReasoningModel();

    void setReasoningModel(Long reasoningModel);

    /** The AI of the small jobs, null when not set. */
    Long getAuxiliaryModel();

    void setAuxiliaryModel(Long auxiliaryModel);

    /** The id of the TTS model, null when not set. */
    String getTtsModel();

    void setTtsModel(String ttsModel);

    /** The id of the voice actor, null when not set. */
    String getVoiceActor();

    void setVoiceActor(String voiceActor);

    /** When a new day starts ({@code HH:mm}), blank when not set. */
    String getDayBoundary();

    void setDayBoundary(String dayBoundary);

    /** Minutes without interaction before the live conversation steps back, null when not set. */
    Integer getPauseMinutes();

    void setPauseMinutes(Integer pauseMinutes);
}
