package com.github.enerccio.joi.domain.persona;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.templates.BodyTemplateData;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.domain.templates.DaySummaryTemplateData;
import com.github.enerccio.joi.domain.templates.FactsTemplateData;
import com.github.enerccio.joi.domain.templates.NodeEventTemplateData;
import com.github.enerccio.joi.domain.templates.PartImageTemplateData;
import com.github.enerccio.joi.domain.templates.PartRequestTemplateData;
import com.github.enerccio.joi.domain.templates.SelfImageTemplateData;
import com.github.enerccio.joi.domain.templates.SummaryTemplateData;
import com.github.enerccio.marginalia.domain.templates.TemplateData;
import org.apache.commons.lang3.StringUtils;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The text settings of Joi, resolved along a chain like Marginalia's {@code PromptField} without the folders: the
 * assistant's own value ({@code Joi}), else the user setting, else the built-in default in the prompt language
 * ({@link JoiDefaults}). A blank text is not set.
 */
public enum JoiPromptField {
    MASTER_TEMPLATE("masterTemplate", ChatTemplateData.class, PersonaAttributes::getMasterTemplate, PersonaAttributes::setMasterTemplate,
            JoiDefaults::masterTemplate),
    SUMMARY_PROMPT("summaryPrompt", SummaryTemplateData.class, PersonaAttributes::getSummaryPrompt, PersonaAttributes::setSummaryPrompt,
            JoiDefaults::summaryPrompt),
    DAY_SUMMARY_PROMPT("daySummaryPrompt", DaySummaryTemplateData.class, PersonaAttributes::getDaySummaryPrompt,
            PersonaAttributes::setDaySummaryPrompt, JoiDefaults::daySummaryPrompt),
    FACTS_PROMPT("factsPrompt", FactsTemplateData.class, PersonaAttributes::getFactsPrompt, PersonaAttributes::setFactsPrompt,
            JoiDefaults::factsPrompt),
    BODY_PROMPT("bodyPrompt", BodyTemplateData.class, PersonaAttributes::getBodyPrompt, PersonaAttributes::setBodyPrompt,
            JoiDefaults::bodyPrompt),
    SELF_IMAGE_PROMPT("selfImagePrompt", SelfImageTemplateData.class, PersonaAttributes::getSelfImagePrompt,
            PersonaAttributes::setSelfImagePrompt, JoiDefaults::selfImagePrompt),
    PART_IMAGE_PROMPT("partImagePrompt", PartImageTemplateData.class, PersonaAttributes::getPartImagePrompt,
            PersonaAttributes::setPartImagePrompt, JoiDefaults::partImagePrompt),
    FIRST_BOOT_EVENT("firstBootEvent", NodeEventTemplateData.class, PersonaAttributes::getFirstBootEvent,
            PersonaAttributes::setFirstBootEvent, JoiDefaults::firstBootEvent),
    CONNECTED_EVENT("connectedEvent", NodeEventTemplateData.class, PersonaAttributes::getConnectedEvent,
            PersonaAttributes::setConnectedEvent, JoiDefaults::connectedEvent),
    LOOKING_GLASS_ENTERED_EVENT("lookingGlassEnteredEvent", NodeEventTemplateData.class, PersonaAttributes::getLookingGlassEnteredEvent,
            PersonaAttributes::setLookingGlassEnteredEvent, JoiDefaults::lookingGlassEnteredEvent),
    LOOKING_GLASS_EXITED_EVENT("lookingGlassExitedEvent", NodeEventTemplateData.class, PersonaAttributes::getLookingGlassExitedEvent,
            PersonaAttributes::setLookingGlassExitedEvent, JoiDefaults::lookingGlassExitedEvent),
    SERVER_RESTARTED_EVENT("serverRestartedEvent", NodeEventTemplateData.class, PersonaAttributes::getServerRestartedEvent,
            PersonaAttributes::setServerRestartedEvent, JoiDefaults::serverRestartedEvent),
    PART_REQUEST_EVENT("partRequestEvent", PartRequestTemplateData.class, PersonaAttributes::getPartRequestEvent,
            PersonaAttributes::setPartRequestEvent, JoiDefaults::partRequestEvent);

    private final String templateName;
    private final Class<? extends TemplateData> dataClass;
    private final Function<PersonaAttributes, String> user;
    private final BiConsumer<PersonaAttributes, String> setUser;
    private final Function<JoiDefaults, String> fallback;

    JoiPromptField(String templateName, Class<? extends TemplateData> dataClass, Function<PersonaAttributes, String> user,
                   BiConsumer<PersonaAttributes, String> setUser, Function<JoiDefaults, String> fallback) {
        this.templateName = templateName;
        this.dataClass = dataClass;
        this.user = user;
        this.setUser = setUser;
        this.fallback = fallback;
    }

    /** The name of the template, for its errors. */
    public String templateName() {
        return templateName;
    }

    /** The data the template is rendered with (its variables, shown in the hints). */
    public Class<? extends TemplateData> dataClass() {
        return dataClass;
    }

    /** What the user setting sets, null or blank when it does not. */
    public String ofUser(JoiUserSetting setting) {
        return setting == null ? null : user.apply(setting);
    }

    /** Sets what the user setting sets; a blank text clears it (the default is used). */
    public void setOfUser(JoiUserSetting setting, String value) {
        setUser.accept(setting, StringUtils.defaultIfBlank(value, null));
    }

    /** What the link of the chain (the assistant or the user setting) sets, null or blank when it does not. */
    public String of(PersonaAttributes attributes) {
        return attributes == null ? null : user.apply(attributes);
    }

    /** Sets what the link of the chain sets; a blank text clears it (the next link is used). */
    public void setOf(PersonaAttributes attributes, String value) {
        setUser.accept(attributes, StringUtils.defaultIfBlank(value, null));
    }

    /** The built-in text. */
    public String fallback(JoiDefaults defaults) {
        return fallback.apply(defaults);
    }

    /**
     * The text to use: the first set link of the chain.
     *
     * @param assistant the assistant ({@code Joi}), null for none
     * @param setting  the user setting, may be null
     * @param defaults the built-in texts of the prompt language
     */
    public String resolve(PersonaAttributes assistant, JoiUserSetting setting, JoiDefaults defaults) {
        return resolve(assistant == null ? null : user.apply(assistant), setting, defaults);
    }

    /**
     * The text to use: the first set link of the chain.
     *
     * @param own      the assistant's own value, null or blank for none
     * @param setting  the user setting, may be null
     * @param defaults the built-in texts of the prompt language
     */
    public String resolve(String own, JoiUserSetting setting, JoiDefaults defaults) {
        if (StringUtils.isNotBlank(own)) {
            return own;
        }
        String ofUser = ofUser(setting);
        return StringUtils.isNotBlank(ofUser) ? ofUser : fallback(defaults);
    }
}
