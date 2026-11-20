package com.github.enerccio.joi.domain.model.impl.settings;

import com.github.enerccio.joi.domain.persona.MoodSetting;
import com.github.enerccio.joi.domain.persona.PersonaAttributes;
import com.github.enerccio.joi.domain.persona.PersonalitySetting;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.settings.BaseUserSetting;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings of a Joi user, on top of the platform's ({@link BaseUserSetting}: default model and protocol, theme,
 * language). {@code SettingService} creates it ({@code userSettingClass} in {@code services-config.xml}). Fields are
 * extended attributes, so adding one needs no migration.
 * <p>
 * The texts and the personality are the user's link of the chain of a setting: a blank one is not set, and the
 * built-in default ({@code JoiDefaults}) is used (see {@code JoiPromptField} and {@code PersonaService}).
 * <p>
 * The discriminator must be the simple class name: {@code Setting} writes {@code getClass().getSimpleName()} into
 * {@code DTYPE} whatever the annotation says, so any other value can't be read back.
 */
@Entity
@DiscriminatorValue("JoiUserSetting")
public class JoiUserSetting extends BaseUserSetting implements PersonaAttributes {

    /** The name of the assistant, blank for the default ({@code Joi}). */
    @ExtendedAttribute
    @Transient
    private String assistantName;

    /** What the assistant calls the user ({@code {{user}}}), blank for the name of the account. */
    @ExtendedAttribute
    @Transient
    private String userName;

    /** The system prompt of a chat, blank for the built-in one. */
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

    /** The request for one toggle of a model, described from two close-ups. */
    @ExtendedAttribute
    @Transient
    private String partImagePrompt;

    /** The request for an outfit from the renders of the model in it ({@code OutfitImageTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String outfitImagePrompt;

    /** The section of her system prompt about the body she is in ({@code {{#body}}}). */
    @ExtendedAttribute
    @Transient
    private String bodyPrompt;

    /** What she is told when she first wakes up on a node ({@code NodeEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String firstBootEvent;

    /** What she is told when the user comes back to her on a node ({@code NodeEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String connectedEvent;

    /** What she is told when the user moves her onto a Looking Glass ({@code NodeEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String lookingGlassEnteredEvent;

    /** What she is told when the user moves her off the Looking Glass, back into the node's window ({@code NodeEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String lookingGlassExitedEvent;

    /** What she is told when the server was down and is back ({@code NodeEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String serverRestartedEvent;

    /** The line before each message of the user and each event: when it was said ({@code MessageStampTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String messageStamp;

    /** The memories that fit the message, added to it ({@code RecalledMemoriesTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String recalledMemories;

    /** What she is told when the user asks to change parts of her body and her personality lets her decide ({@code PartRequestTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String partRequestEvent;

    /** The key of the personality, blank for the default one. */
    @ExtendedAttribute
    @Transient
    private String personality;

    /** The user's changes of the built-in personalities and their own ones, by key. */
    @ExtendedAttribute(inject = true, injectPrefix = "personalities")
    @Transient
    private List<PersonalitySetting> personalities = new ArrayList<>();

    /** The user's changes of the built-in moods and their own ones, by key. */
    @ExtendedAttribute(inject = true, injectPrefix = "moods")
    @Transient
    private List<MoodSetting> moods = new ArrayList<>();

    /** The AI of the conversation role (marked Chat). */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long chatModel;

    /** The AI of the heavy role (marked Deep reasoning). */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long reasoningModel;

    /** The AI of the small jobs (marked Auxiliary): summaries, facts, classifications. */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long auxiliaryModel;

    /** The id of the TTS model the assistant speaks with ({@code TtsModel}), null for none. */
    @ExtendedAttribute
    @Transient
    private String ttsModel;

    /** The id of the voice actor that is the assistant's voice ({@code Actor}), null for none. */
    @ExtendedAttribute
    @Transient
    private String voiceActor;

    /** When a new day starts ({@code HH:mm}), blank for the default. */
    @ExtendedAttribute
    @Transient
    private String dayBoundary;

    /** Minutes without interaction before the live conversation steps back, null for the default. */
    @ExtendedAttribute
    @Transient
    private Integer pauseMinutes;

    public String getAssistantName() {
        return assistantName;
    }

    public void setAssistantName(String assistantName) {
        this.assistantName = assistantName;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getMasterTemplate() {
        return masterTemplate;
    }

    public void setMasterTemplate(String masterTemplate) {
        this.masterTemplate = masterTemplate;
    }

    public String getSummaryPrompt() {
        return summaryPrompt;
    }

    public void setSummaryPrompt(String summaryPrompt) {
        this.summaryPrompt = summaryPrompt;
    }

    public String getDaySummaryPrompt() {
        return daySummaryPrompt;
    }

    public void setDaySummaryPrompt(String daySummaryPrompt) {
        this.daySummaryPrompt = daySummaryPrompt;
    }

    public String getFactsPrompt() {
        return factsPrompt;
    }

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
    public String getOutfitImagePrompt() {
        return outfitImagePrompt;
    }

    @Override
    public void setOutfitImagePrompt(String outfitImagePrompt) {
        this.outfitImagePrompt = outfitImagePrompt;
    }

    @Override
    public String getPartImagePrompt() {
        return partImagePrompt;
    }

    @Override
    public void setPartImagePrompt(String partImagePrompt) {
        this.partImagePrompt = partImagePrompt;
    }

    @Override
    public String getBodyPrompt() {
        return bodyPrompt;
    }

    @Override
    public void setBodyPrompt(String bodyPrompt) {
        this.bodyPrompt = bodyPrompt;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public List<PersonalitySetting> getPersonalities() {
        if (personalities == null) {
            personalities = new ArrayList<>();
        }
        return personalities;
    }

    public void setPersonalities(List<PersonalitySetting> personalities) {
        this.personalities = personalities == null ? new ArrayList<>() : new ArrayList<>(personalities);
    }

    public List<MoodSetting> getMoods() {
        if (moods == null) {
            moods = new ArrayList<>();
        }
        return moods;
    }

    public void setMoods(List<MoodSetting> moods) {
        this.moods = moods == null ? new ArrayList<>() : new ArrayList<>(moods);
    }

    public Long getChatModel() {
        return chatModel;
    }

    public void setChatModel(Long chatModel) {
        this.chatModel = chatModel;
    }

    public Long getReasoningModel() {
        return reasoningModel;
    }

    public void setReasoningModel(Long reasoningModel) {
        this.reasoningModel = reasoningModel;
    }

    public Long getAuxiliaryModel() {
        return auxiliaryModel;
    }

    public void setAuxiliaryModel(Long auxiliaryModel) {
        this.auxiliaryModel = auxiliaryModel;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getVoiceActor() {
        return voiceActor;
    }

    public void setVoiceActor(String voiceActor) {
        this.voiceActor = voiceActor;
    }

    public String getDayBoundary() {
        return dayBoundary;
    }

    public void setDayBoundary(String dayBoundary) {
        this.dayBoundary = dayBoundary;
    }

    public Integer getPauseMinutes() {
        return pauseMinutes;
    }

    public void setPauseMinutes(Integer pauseMinutes) {
        this.pauseMinutes = pauseMinutes;
    }

    @Override
    public String getFirstBootEvent() {
        return firstBootEvent;
    }

    @Override
    public void setFirstBootEvent(String firstBootEvent) {
        this.firstBootEvent = firstBootEvent;
    }

    @Override
    public String getConnectedEvent() {
        return connectedEvent;
    }

    @Override
    public void setConnectedEvent(String connectedEvent) {
        this.connectedEvent = connectedEvent;
    }

    @Override
    public String getLookingGlassEnteredEvent() {
        return lookingGlassEnteredEvent;
    }

    @Override
    public void setLookingGlassEnteredEvent(String lookingGlassEnteredEvent) {
        this.lookingGlassEnteredEvent = lookingGlassEnteredEvent;
    }

    @Override
    public String getLookingGlassExitedEvent() {
        return lookingGlassExitedEvent;
    }

    @Override
    public void setLookingGlassExitedEvent(String lookingGlassExitedEvent) {
        this.lookingGlassExitedEvent = lookingGlassExitedEvent;
    }

    @Override
    public String getMessageStamp() {
        return messageStamp;
    }

    public void setMessageStamp(String messageStamp) {
        this.messageStamp = messageStamp;
    }

    public String getRecalledMemories() {
        return recalledMemories;
    }

    public void setRecalledMemories(String recalledMemories) {
        this.recalledMemories = recalledMemories;
    }

    public String getServerRestartedEvent() {
        return serverRestartedEvent;
    }

    public void setServerRestartedEvent(String serverRestartedEvent) {
        this.serverRestartedEvent = serverRestartedEvent;
    }

    public String getPartRequestEvent() {
        return partRequestEvent;
    }

    @Override
    public void setPartRequestEvent(String partRequestEvent) {
        this.partRequestEvent = partRequestEvent;
    }
}
