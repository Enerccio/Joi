package com.github.enerccio.joi.ui.widgets;

import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.persona.PersonaAttributes;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.timepicker.TimePicker;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings of the summaries, the same in the user settings and in an assistant's About tab: the model that writes
 * them, the pause after which the conversation is summarized, whether she summarizes on her own when the conversation
 * grows (and after how many tokens), and the time of day the summary of a week, a month and a year is made. Each field
 * is a link of the chain (the assistant, the user setting, the default): an empty one is not set, and its placeholder
 * says what the next link gives.
 */
@Configurable
public class SummaryFields {

    @Autowired
    private Localization loc;

    private ComboBox<AI> summaryModel;
    private IntegerField pause;
    private Select<Boolean> autoSummary;
    private IntegerField autoTokens;
    private TimePicker weekly;
    private TimePicker monthly;
    private TimePicker yearly;

    /** Makes the fields (after the injection: the texts come from the localization). */
    public SummaryFields create() {
        summaryModel = new ComboBox<>(loc.getValue(J_L.LABEL_SUMMARY_MODEL));
        summaryModel.setItemLabelGenerator(AI::getName);
        summaryModel.setClearButtonVisible(true);
        summaryModel.setHelperText(loc.getValue(J_L.HELP_SUMMARY_MODEL));

        pause = new IntegerField(loc.getValue(J_L.LABEL_PAUSE_MINUTES));
        pause.setMin(0);
        pause.setStepButtonsVisible(true);
        pause.setPlaceholder(String.valueOf(JoiDefaults.PAUSE_MINUTES));
        pause.setHelperText(loc.getValue(J_L.HELP_PAUSE_MINUTES));

        autoSummary = new Select<>();
        autoSummary.setLabel(loc.getValue(J_L.LABEL_AUTO_SUMMARY));
        autoSummary.setItems(Boolean.TRUE, Boolean.FALSE);
        autoSummary.setItemLabelGenerator(on -> on == null ? "" : loc.getValue(on ? L.LABEL_YES : L.LABEL_NO));
        autoSummary.setEmptySelectionAllowed(true);
        autoSummary.setHelperText(loc.getValue(J_L.HELP_AUTO_SUMMARY));

        autoTokens = new IntegerField(loc.getValue(J_L.LABEL_AUTO_SUMMARY_TOKENS));
        autoTokens.setMin(1000);
        autoTokens.setStep(1000);
        autoTokens.setStepButtonsVisible(true);
        autoTokens.setPlaceholder(String.valueOf(JoiDefaults.AUTO_SUMMARY_TOKENS));
        autoTokens.setHelperText(loc.getValue(J_L.HELP_AUTO_SUMMARY_TOKENS));

        weekly = timePicker(J_L.LABEL_WEEKLY_SUMMARY_TIME);
        monthly = timePicker(J_L.LABEL_MONTHLY_SUMMARY_TIME);
        yearly = timePicker(J_L.LABEL_YEARLY_SUMMARY_TIME);
        weekly.setHelperText(loc.getValue(J_L.HELP_SUMMARY_TIME));
        return this;
    }

    private TimePicker timePicker(J_L label) {
        TimePicker picker = new TimePicker(loc.getValue(label));
        picker.setStep(Duration.ofMinutes(15));
        picker.setPlaceholder(JoiDefaults.SUMMARY_TIME);
        picker.setClearButtonVisible(true);
        return picker;
    }

    /** Adds the fields to the form, the model and the pause first, then the rest. */
    public void addTo(FormLayout form) {
        form.add(summaryModel, pause, autoSummary, autoTokens, weekly, monthly, yearly);
    }

    /** Every field, for the form to watch their changes. */
    public List<HasValue<?, ?>> fields() {
        return new ArrayList<>(List.of(summaryModel, pause, autoSummary, autoTokens, weekly, monthly, yearly));
    }

    /**
     * Shows the settings of one link of the chain.
     *
     * @param own       the link: the assistant or the user setting
     * @param inherited what the next link has (the user setting for an assistant), null for the user setting itself
     * @param ais       the models of the user
     * @param chatModel the chat model that applies: what she keeps unsummarized never passes its context; null when not known
     */
    public void load(PersonaAttributes own, PersonaAttributes inherited, List<AI> ais, AI chatModel) {
        RoleModels.fill(summaryModel, ais, TextLLMType.SUMMARY, own.getSummaryModel());
        AI next = inherited == null ? null : RoleModels.find(ais, inherited.getSummaryModel());
        summaryModel.setPlaceholder(next == null ? "" : next.getName());

        pause.setValue(own.getPauseMinutes());
        pause.setPlaceholder(String.valueOf(inherited != null && inherited.getPauseMinutes() != null ? inherited.getPauseMinutes() : JoiDefaults.PAUSE_MINUTES));

        autoSummary.setValue(own.getAutoSummary());
        Boolean inheritedAuto = inherited == null ? null : inherited.getAutoSummary();
        autoSummary.setPlaceholder(loc.getValue(Boolean.TRUE.equals(inheritedAuto) ? L.LABEL_YES : L.LABEL_NO));
        autoSummary.setEmptySelectionCaption(loc.getValue(Boolean.TRUE.equals(inheritedAuto) ? L.LABEL_YES : L.LABEL_NO));

        Integer context = chatModel == null || chatModel.getMaxContext() == null || chatModel.getMaxContext() < 1 ? null : chatModel.getMaxContext();
        autoTokens.setMax(context == null ? Integer.MAX_VALUE : context);
        autoTokens.setValue(own.getAutoSummaryTokens());
        autoTokens.setPlaceholder(String.valueOf(inherited != null && inherited.getAutoSummaryTokens() != null ? inherited.getAutoSummaryTokens() : JoiDefaults.AUTO_SUMMARY_TOKENS));

        load(weekly, own.getWeeklySummaryTime(), inherited == null ? null : inherited.getWeeklySummaryTime());
        load(monthly, own.getMonthlySummaryTime(), inherited == null ? null : inherited.getMonthlySummaryTime());
        load(yearly, own.getYearlySummaryTime(), inherited == null ? null : inherited.getYearlySummaryTime());
    }

    private static void load(TimePicker picker, String own, String inherited) {
        picker.setValue(parse(own));
        picker.setPlaceholder(StringUtils.defaultIfBlank(inherited, JoiDefaults.SUMMARY_TIME));
    }

    /** Writes the fields into the link of the chain. */
    public void save(PersonaAttributes own) {
        own.setSummaryModel(summaryModel.getValue() == null ? null : summaryModel.getValue().getId());
        own.setPauseMinutes(pause.getValue());
        own.setAutoSummary(autoSummary.getValue());
        own.setAutoSummaryTokens(autoTokens.getValue());
        own.setWeeklySummaryTime(weekly.getValue() == null ? null : weekly.getValue().toString());
        own.setMonthlySummaryTime(monthly.getValue() == null ? null : monthly.getValue().toString());
        own.setYearlySummaryTime(yearly.getValue() == null ? null : yearly.getValue().toString());
    }

    private static LocalTime parse(String text) {
        try {
            return StringUtils.isBlank(text) ? null : LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // for tests

    public ComboBox<AI> getSummaryModel() {
        return summaryModel;
    }

    public IntegerField getPause() {
        return pause;
    }

    public Select<Boolean> getAutoSummary() {
        return autoSummary;
    }

    public IntegerField getAutoTokens() {
        return autoTokens;
    }

    public TimePicker getWeekly() {
        return weekly;
    }

    public TimePicker getMonthly() {
        return monthly;
    }

    public TimePicker getYearly() {
        return yearly;
    }
}
