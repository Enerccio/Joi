package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.persona.MoodSetting;
import com.github.enerccio.joi.domain.persona.PersonaAttributes;
import com.github.enerccio.joi.domain.persona.PersonalitySetting;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * An assistant of a user (see {@code DESIGN.md} → Several assistants): the owner of her conversation
 * ({@link ChatMessage}), her memory ({@link Memory}, {@link Summary}) and her tasks ({@link JoiTask}).
 * <p>
 * Her template attributes ({@link PersonaAttributes}: her name, what she calls the user, the prompts, the
 * personality, the moods, the models, the voice) are extended attributes and the first link of the chain of a
 * setting: a blank one is not set, and the user's setting, then the built-in default, is used. So nothing here is a
 * column: nobody queries them in SQL.
 */
@Entity
@Table(name = "assistants")
public class Joi extends ExtendableEntity implements PersonaAttributes {

    @ExtendedAttribute
    @Transient
    private String assistantName;

    @ExtendedAttribute
    @Transient
    private String userName;

    /** The wake word, blank for "Hey" and her name. */
    @ExtendedAttribute
    @Transient
    private String wakeWord;

    @ExtendedAttribute
    @Transient
    private String masterTemplate;

    @ExtendedAttribute
    @Transient
    private String summaryPrompt;

    @ExtendedAttribute
    @Transient
    private String daySummaryPrompt;

    @ExtendedAttribute
    @Transient
    private String factsPrompt;

    /** The request for a body's self-image (a multimodal model describes it from snapshots). */
    @ExtendedAttribute
    @Transient
    private String selfImagePrompt;

    /** The section of her system prompt about the body she is in ({@code {{#body}}}). */
    @ExtendedAttribute
    @Transient
    private String bodyPrompt;

    @ExtendedAttribute
    @Transient
    private String personality;

    @ExtendedAttribute(inject = true, injectPrefix = "personalities")
    @Transient
    private List<PersonalitySetting> personalities = new ArrayList<>();

    @ExtendedAttribute(inject = true, injectPrefix = "moods")
    @Transient
    private List<MoodSetting> moods = new ArrayList<>();

    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long chatModel;

    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long reasoningModel;

    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long auxiliaryModel;

    @ExtendedAttribute
    @Transient
    private String ttsModel;

    @ExtendedAttribute
    @Transient
    private String voiceActor;

    @ExtendedAttribute
    @Transient
    private String dayBoundary;

    @ExtendedAttribute
    @Transient
    private Integer pauseMinutes;

    /** The key of today's mood, null before the first roll. */
    @ExtendedAttribute
    @Transient
    private String mood;

    /** When the mood was rolled or set, null before the first roll. */
    @ExtendedAttribute
    @Transient
    private Date moodSince;

    @Override
    public String getAssistantName() {
        return assistantName;
    }

    @Override
    public void setAssistantName(String assistantName) {
        this.assistantName = assistantName;
    }

    @Override
    public String getUserName() {
        return userName;
    }

    @Override
    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getWakeWord() {
        return wakeWord;
    }

    public void setWakeWord(String wakeWord) {
        this.wakeWord = wakeWord;
    }

    @Override
    public String getMasterTemplate() {
        return masterTemplate;
    }

    @Override
    public void setMasterTemplate(String masterTemplate) {
        this.masterTemplate = masterTemplate;
    }

    @Override
    public String getSummaryPrompt() {
        return summaryPrompt;
    }

    @Override
    public void setSummaryPrompt(String summaryPrompt) {
        this.summaryPrompt = summaryPrompt;
    }

    @Override
    public String getDaySummaryPrompt() {
        return daySummaryPrompt;
    }

    @Override
    public void setDaySummaryPrompt(String daySummaryPrompt) {
        this.daySummaryPrompt = daySummaryPrompt;
    }

    @Override
    public String getFactsPrompt() {
        return factsPrompt;
    }

    @Override
    public void setFactsPrompt(String factsPrompt) {
        this.factsPrompt = factsPrompt;
    }

    @Override
    public String getSelfImagePrompt() {
        return selfImagePrompt;
    }

    @Override
    public void setSelfImagePrompt(String selfImagePrompt) {
        this.selfImagePrompt = selfImagePrompt;
    }

    @Override
    public String getBodyPrompt() {
        return bodyPrompt;
    }

    @Override
    public void setBodyPrompt(String bodyPrompt) {
        this.bodyPrompt = bodyPrompt;
    }

    @Override
    public String getPersonality() {
        return personality;
    }

    @Override
    public void setPersonality(String personality) {
        this.personality = personality;
    }

    @Override
    public List<PersonalitySetting> getPersonalities() {
        if (personalities == null) {
            personalities = new ArrayList<>();
        }
        return personalities;
    }

    @Override
    public void setPersonalities(List<PersonalitySetting> personalities) {
        this.personalities = personalities == null ? new ArrayList<>() : new ArrayList<>(personalities);
    }

    @Override
    public List<MoodSetting> getMoods() {
        if (moods == null) {
            moods = new ArrayList<>();
        }
        return moods;
    }

    @Override
    public void setMoods(List<MoodSetting> moods) {
        this.moods = moods == null ? new ArrayList<>() : new ArrayList<>(moods);
    }

    @Override
    public Long getChatModel() {
        return chatModel;
    }

    @Override
    public void setChatModel(Long chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Long getReasoningModel() {
        return reasoningModel;
    }

    @Override
    public void setReasoningModel(Long reasoningModel) {
        this.reasoningModel = reasoningModel;
    }

    @Override
    public Long getAuxiliaryModel() {
        return auxiliaryModel;
    }

    @Override
    public void setAuxiliaryModel(Long auxiliaryModel) {
        this.auxiliaryModel = auxiliaryModel;
    }

    @Override
    public String getTtsModel() {
        return ttsModel;
    }

    @Override
    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    @Override
    public String getVoiceActor() {
        return voiceActor;
    }

    @Override
    public void setVoiceActor(String voiceActor) {
        this.voiceActor = voiceActor;
    }

    @Override
    public String getDayBoundary() {
        return dayBoundary;
    }

    @Override
    public void setDayBoundary(String dayBoundary) {
        this.dayBoundary = dayBoundary;
    }

    @Override
    public Integer getPauseMinutes() {
        return pauseMinutes;
    }

    @Override
    public void setPauseMinutes(Integer pauseMinutes) {
        this.pauseMinutes = pauseMinutes;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public Date getMoodSince() {
        return moodSince;
    }

    public void setMoodSince(Date moodSince) {
        this.moodSince = moodSince;
    }
}
