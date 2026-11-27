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

    String getOutfitImagePrompt();

    void setOutfitImagePrompt(String outfitImagePrompt);

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

    String getMessageStamp();

    void setMessageStamp(String messageStamp);

    String getRecalledMemories();

    void setRecalledMemories(String recalledMemories);

    String getImageNote();

    void setImageNote(String imageNote);

    String getToolImages();

    void setToolImages(String toolImages);

    String getImageDescriptionPrompt();

    void setImageDescriptionPrompt(String imageDescriptionPrompt);

    String getServerRestartedEvent();

    void setServerRestartedEvent(String serverRestartedEvent);

    String getPartRequestEvent();

    void setPartRequestEvent(String partRequestEvent);

    String getWebTaskPrompt();

    void setWebTaskPrompt(String webTaskPrompt);

    String getTaskEvent();

    void setTaskEvent(String taskEvent);

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

    /** The AI that writes summaries, null when not set. */
    Long getSummaryModel();

    void setSummaryModel(Long summaryModel);

    /** Whether she summarizes the oldest messages on her own when too many are unsummarized, null when not set. */
    Boolean getAutoSummary();

    void setAutoSummary(Boolean autoSummary);

    /** The tokens of unsummarized messages after which she does, null when not set. */
    Integer getAutoSummaryTokens();

    void setAutoSummaryTokens(Integer autoSummaryTokens);

    /** The time ({@code HH:mm}) the summary of the past week is made, blank when not set. */
    String getWeeklySummaryTime();

    void setWeeklySummaryTime(String weeklySummaryTime);

    String getMonthlySummaryTime();

    void setMonthlySummaryTime(String monthlySummaryTime);

    String getYearlySummaryTime();

    void setYearlySummaryTime(String yearlySummaryTime);

    String getWeekSummaryPrompt();

    void setWeekSummaryPrompt(String weekSummaryPrompt);

    String getMonthSummaryPrompt();

    void setMonthSummaryPrompt(String monthSummaryPrompt);

    String getYearSummaryPrompt();

    void setYearSummaryPrompt(String yearSummaryPrompt);

    String getSummaryEvent();

    void setSummaryEvent(String summaryEvent);
}
