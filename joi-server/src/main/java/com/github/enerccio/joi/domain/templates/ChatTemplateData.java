package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the system prompt of a chat ({@code JoiPromptField.MASTER_TEMPLATE}). A blank value leaves its section out.
 */
public class ChatTemplateData extends JoiTemplateData {

    /** The personality, rendered (not {@code personality}: that is a SillyTavern macro the platform ignores). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PERSONALITY)
    private String personalityPrompt;

    /** Today's mood as it sounds in the personality, rendered. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_MOOD)
    private String mood;

    /** The short always-in-prompt block about the user. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PROFILE)
    private String profile;

    /** The facts and episodes recalled for this turn. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_MEMORIES)
    private String memories;

    /** The summaries of what happened before the messages of the prompt, the oldest first. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PAST_SUMMARIES)
    private String summaries;

    /** Short summaries of the other active conversations. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_CONVERSATIONS)
    private String conversations;

    /** The current body: what it can do and its state. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY)
    private String body;

    /** The node the user talks through, and what it offers. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_NODE)
    private String node;

    /** How her voice sounds: the description of her voice actor. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_VOICE)
    private String voice;

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getPersonalityPrompt() {
        return personalityPrompt;
    }

    public void setPersonalityPrompt(String personalityPrompt) {
        this.personalityPrompt = personalityPrompt;
    }

    public String getMood() {
        return mood;
    }

    public void setMood(String mood) {
        this.mood = mood;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getMemories() {
        return memories;
    }

    public void setMemories(String memories) {
        this.memories = memories;
    }

    public String getSummaries() {
        return summaries;
    }

    public void setSummaries(String summaries) {
        this.summaries = summaries;
    }

    public String getConversations() {
        return conversations;
    }

    public void setConversations(String conversations) {
        this.conversations = conversations;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

}
