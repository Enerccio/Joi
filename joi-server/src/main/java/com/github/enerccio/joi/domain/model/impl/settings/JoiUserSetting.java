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

    /** How a description of an image is put into a message when the chat model cannot see ({@code ImageNoteTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String imageNote;

    /** What she is told with the pictures of her tools' results ({@code ToolImagesTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String toolImages;

    /** The request to a model that can see, to describe an image for one that cannot ({@code ImageDescriptionTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String imageDescriptionPrompt;

    /** What she is told when the user asks to change parts of her body and her personality lets her decide ({@code PartRequestTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String partRequestEvent;

    /** What the stronger model is told when it searches the web or reads a page for her ({@code WebTaskTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String webTaskPrompt;

    /** What she is told when a task of hers ends ({@code TaskEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String taskEvent;

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

    /** The AI that writes summaries (marked Summary), null for none: the small-jobs model is used then, see {@code SummaryModels}. */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.WEAK, target = AI.class)
    private Long summaryModel;

    /** Whether she summarizes the oldest messages on her own when the unsummarized ones pass {@link #autoSummaryTokens}; null for the next link. */
    @ExtendedAttribute
    @Transient
    private Boolean autoSummary;

    /** The tokens of unsummarized messages after which she summarizes the oldest of them (at most the context of her chat model); null for the next link. */
    @ExtendedAttribute
    @Transient
    private Integer autoSummaryTokens;

    /** When the summary of the past week may be made on the first day of the next one ({@code HH:mm}), blank for the next link. */
    @ExtendedAttribute
    @Transient
    private String weeklySummaryTime;

    /** When the summary of the past month may be made on the first day of the next one ({@code HH:mm}), blank for the next link. */
    @ExtendedAttribute
    @Transient
    private String monthlySummaryTime;

    /** When the summary of the past year may be made on the first day of the next one ({@code HH:mm}), blank for the next link. */
    @ExtendedAttribute
    @Transient
    private String yearlySummaryTime;

    /** Rolls the summaries of a week up into the week summary ({@code PeriodSummaryTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String weekSummaryPrompt;

    /** Rolls the summaries of a month up into the month summary ({@code PeriodSummaryTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String monthSummaryPrompt;

    /** Rolls the summaries of a year up into the year summary ({@code PeriodSummaryTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String yearSummaryPrompt;

    /** What she is told when a summary was made ({@code SummaryEventTemplateData}). */
    @ExtendedAttribute
    @Transient
    private String summaryEvent;

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

    public Long getSummaryModel() {
        return summaryModel;
    }

    public void setSummaryModel(Long summaryModel) {
        this.summaryModel = summaryModel;
    }

    public Boolean getAutoSummary() {
        return autoSummary;
    }

    public void setAutoSummary(Boolean autoSummary) {
        this.autoSummary = autoSummary;
    }

    public Integer getAutoSummaryTokens() {
        return autoSummaryTokens;
    }

    public void setAutoSummaryTokens(Integer autoSummaryTokens) {
        this.autoSummaryTokens = autoSummaryTokens;
    }

    public String getWeeklySummaryTime() {
        return weeklySummaryTime;
    }

    public void setWeeklySummaryTime(String weeklySummaryTime) {
        this.weeklySummaryTime = weeklySummaryTime;
    }

    public String getMonthlySummaryTime() {
        return monthlySummaryTime;
    }

    public void setMonthlySummaryTime(String monthlySummaryTime) {
        this.monthlySummaryTime = monthlySummaryTime;
    }

    public String getYearlySummaryTime() {
        return yearlySummaryTime;
    }

    public void setYearlySummaryTime(String yearlySummaryTime) {
        this.yearlySummaryTime = yearlySummaryTime;
    }

    public String getWeekSummaryPrompt() {
        return weekSummaryPrompt;
    }

    public void setWeekSummaryPrompt(String weekSummaryPrompt) {
        this.weekSummaryPrompt = weekSummaryPrompt;
    }

    public String getMonthSummaryPrompt() {
        return monthSummaryPrompt;
    }

    public void setMonthSummaryPrompt(String monthSummaryPrompt) {
        this.monthSummaryPrompt = monthSummaryPrompt;
    }

    public String getYearSummaryPrompt() {
        return yearSummaryPrompt;
    }

    public void setYearSummaryPrompt(String yearSummaryPrompt) {
        this.yearSummaryPrompt = yearSummaryPrompt;
    }

    public String getSummaryEvent() {
        return summaryEvent;
    }

    public void setSummaryEvent(String summaryEvent) {
        this.summaryEvent = summaryEvent;
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

    public String getImageNote() {
        return imageNote;
    }

    public void setImageNote(String imageNote) {
        this.imageNote = imageNote;
    }

    public String getToolImages() {
        return toolImages;
    }

    public void setToolImages(String toolImages) {
        this.toolImages = toolImages;
    }

    public String getImageDescriptionPrompt() {
        return imageDescriptionPrompt;
    }

    public void setImageDescriptionPrompt(String imageDescriptionPrompt) {
        this.imageDescriptionPrompt = imageDescriptionPrompt;
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

    @Override
    public String getWebTaskPrompt() {
        return webTaskPrompt;
    }

    @Override
    public void setWebTaskPrompt(String webTaskPrompt) {
        this.webTaskPrompt = webTaskPrompt;
    }

    @Override
    public String getTaskEvent() {
        return taskEvent;
    }

    @Override
    public void setTaskEvent(String taskEvent) {
        this.taskEvent = taskEvent;
    }
}
