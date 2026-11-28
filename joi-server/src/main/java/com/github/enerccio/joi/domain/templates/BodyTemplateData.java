package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the section of her system prompt about the body she is in ({@code JoiPromptField.BODY_PROMPT}, put into
 * the master template's {@code {{#body}}}).
 */
public class BodyTemplateData extends JoiTemplateData {

    /** The body's name. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_BODY_NAME)
    private String bodyName;

    /** How she looks in it; empty when nobody has described it. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_APPEARANCE)
    private String appearance;

    /** The expressions her face can show (moods and custom ones), comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_EXPRESSIONS)
    private String expressions;

    /** The parts she can show or hide, each with its state in the part's words ("(worn)"; by default "(shown)" or "(hidden)"), comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_PARTS)
    private String parts;

    /** The outfit she wears now (the one the body's toggles are in the state of); empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OUTFIT)
    private String outfit;

    /** The names of her outfits in this body, except the one she wears, comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OUTFITS)
    private String outfits;

    /** The names of her other bodies, comma separated; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_OTHER_BODIES)
    private String otherBodies;

    /** The animation she holds now, her stance; empty when none (or her body does not play it). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_STANCE)
    private String stance;

    /** Whether her body plays any animation (so she can hold a stance); false hides the line about her stance. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_ANIMATED)
    private boolean animated;

    /** Why she took her stance, in her words; empty when not said. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_STANCE_REASON)
    private String stanceReason;

    /** When she took her stance ("Saturday 11:58 AM"), with {@code stance}. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_STANCE_SINCE)
    private String stanceSince;

    /** The stances she held lately, the last first, each with when it began and ended; empty when none. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_RECENT_STANCES)
    private String recentStances;

    /** The profile she is in (where: home, work); empty when she has only the default one. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_WHERE_SHE_IS)
    private String profile;

    /** The profile she was in until this turn, when this turn is in another one; empty otherwise. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_WHERE_SHE_WAS)
    private String profileFrom;

    public boolean isAnimated() {
        return animated;
    }

    public void setAnimated(boolean animated) {
        this.animated = animated;
    }

    public String getStanceReason() {
        return stanceReason;
    }

    public void setStanceReason(String stanceReason) {
        this.stanceReason = stanceReason;
    }

    public String getStanceSince() {
        return stanceSince;
    }

    public void setStanceSince(String stanceSince) {
        this.stanceSince = stanceSince;
    }

    public String getRecentStances() {
        return recentStances;
    }

    public void setRecentStances(String recentStances) {
        this.recentStances = recentStances;
    }

    public String getStance() {
        return stance;
    }

    public void setStance(String stance) {
        this.stance = stance;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getProfileFrom() {
        return profileFrom;
    }

    public void setProfileFrom(String profileFrom) {
        this.profileFrom = profileFrom;
    }

    public String getBodyName() {
        return bodyName;
    }

    public void setBodyName(String bodyName) {
        this.bodyName = bodyName;
    }

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public String getExpressions() {
        return expressions;
    }

    public void setExpressions(String expressions) {
        this.expressions = expressions;
    }

    public String getParts() {
        return parts;
    }

    public void setParts(String parts) {
        this.parts = parts;
    }

    public String getOutfit() {
        return outfit;
    }

    public void setOutfit(String outfit) {
        this.outfit = outfit;
    }

    public String getOutfits() {
        return outfits;
    }

    public void setOutfits(String outfits) {
        this.outfits = outfits;
    }

    public String getOtherBodies() {
        return otherBodies;
    }

    public void setOtherBodies(String otherBodies) {
        this.otherBodies = otherBodies;
    }
}
