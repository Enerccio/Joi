package com.github.enerccio.joi.ui.workspace.parts;

import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.ui.access.AccessPanel;
import com.github.enerccio.joi.ui.tools.ToolsPanel;
import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.JoiPromptField;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.persona.Mood;
import com.github.enerccio.joi.domain.persona.Personality;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.ui.dialogs.MoodDialog;
import com.github.enerccio.joi.ui.dialogs.PersonalityDialog;
import com.github.enerccio.joi.ui.dialogs.SystemPromptPreviewDialog;
import com.github.enerccio.joi.ui.widgets.PromptAreas;
import com.github.enerccio.joi.ui.widgets.RoleModels;
import com.github.enerccio.joi.ui.widgets.SummaryFields;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.service.TemplateService;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.ui.widgets.TemplateHints;
import com.github.enerccio.marginalia.ui.widgets.TextAreaPopoverComponent;
import com.github.enerccio.marginalia.ui.workspace.BaseWorkspace;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The settings of the user: the user's link of the chain of every setting (empty is the built-in default of
 * {@link JoiDefaults}, shown as the placeholder), in tabs:
 * <ul>
 *     <li>General: the assistant's name and personality, the models of the roles, the voice, the day;</li>
 *     <li>Prompts: the master system template and the prompts of the memory ({@link JoiPromptField});</li>
 *     <li>Personalities and Moods: the built-in ones (changed or reset) and the user's own.</li>
 * </ul>
 * General and Prompts are stored by <i>Save</i>; the dialogs of the personalities and moods store at once. A change of
 * General or Prompts not saved yet shows the unsaved hint and freezes the workspace's navigation until it is saved or
 * discarded (as Marginalia's settings do).
 */
@Configurable
public class UserSettingsPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private com.github.enerccio.joi.domain.service.JoiService joiService;

    @Autowired
    private com.github.enerccio.joi.domain.service.NodeService nodeService;

    @Autowired
    private SettingService settingService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private AIService aiService;

    @Autowired
    private TtsService ttsService;

    /** The workspace whose navigation unsaved changes freeze, null in tests of the part alone. */
    private final BaseWorkspace workspace;

    private TextField assistantNameField;
    private TextField userNameField;
    private Select<Personality> personalitySelect;
    // in the order of the roles, not of the enum
    private final Map<TextLLMType, ComboBox<AI>> modelBoxes = new java.util.LinkedHashMap<>();
    private Select<TtsModel> ttsModelSelect;
    private Select<Actor> voiceActorSelect;
    private TimePicker dayBoundaryPicker;
    private SummaryFields summaryFields;
    private final Map<JoiPromptField, TextAreaPopoverComponent> promptAreas = new EnumMap<>(JoiPromptField.class);
    private Grid<Personality> personalityGrid;
    private Grid<Mood> moodGrid;
    private Button saveButton;
    private Button discardButton;
    private Span unsavedSpan;
    private TabSheet tabs;
    private final AccessPanel accessPanel = new AccessPanel();
    private final ToolsPanel toolsPanel = new ToolsPanel(ToolScope.USER);
    /** True while {@link #refresh()} fills the fields: their changes are not the user's. */
    private boolean loading;
    private boolean changed;

    public UserSettingsPart() {
        this(null);
    }

    public UserSettingsPart(BaseWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public Component create() throws Exception {
        unsavedSpan = new Span(loc.getValue(J_L.MSG_UNSAVED_SETTINGS));
        unsavedSpan.addClassName(CssStyles.TEXT_ERROR);
        unsavedSpan.setVisible(false);
        discardButton = new Button(loc.getValue(J_L.LABEL_DISCARD_CHANGES), Solid.UNDO.create(), e -> discard());
        discardButton.setEnabled(false);
        saveButton = new Button(loc.getValue(L.LABEL_SAVE), Solid.SAVE.create(), e -> save());
        saveButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(unsavedSpan, discardButton, saveButton);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        // the buttons hide on the tabs that store at once: the row stays, or the tabs jump
        header.addClassName(JoiCssStyles.BUTTON_ROW);

        tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add(loc.getValue(J_L.LABEL_GENERAL), createGeneral());
        tabs.add(loc.getValue(J_L.LABEL_PROMPTS), createPrompts());
        tabs.add(loc.getValue(J_L.LABEL_PERSONALITIES), createPersonalities());
        tabs.add(loc.getValue(J_L.LABEL_MOODS), createMoods());
        Tab accessTab = tabs.add(loc.getValue(L.LABEL_ACCESS), accessPanel.create());
        Tab toolsTab = tabs.add(loc.getValue(J_L.LABEL_TOOLS), toolsPanel.create());
        // the grids store at once, the buttons are for the forms (and stay while their changes are unsaved)
        tabs.addSelectedChangeListener(e -> {
            updateButtons();
            // devices are approved elsewhere (the link a device shows), tokens used by the API: show them as they are now
            if (e.getSelectedTab() == toolsTab) {
                try {
                    toolsPanel.refresh();
                } catch (Exception ex) {
                    UIUtils.internalServerError(loc, ex);
                }
            }
            if (e.getSelectedTab() == accessTab) {
                try {
                    accessPanel.refresh();
                } catch (Exception ex) {
                    UIUtils.internalServerError(loc, ex);
                }
            }
        });
        trackChanges();

        VerticalLayout layout = new VerticalLayout(header, tabs);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, tabs);
        return layout;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // general
    // ----------------------------------------------------------------------------------------------------------------

    private Component createGeneral() {
        assistantNameField = new TextField(loc.getValue(J_L.LABEL_ASSISTANT_NAME));
        assistantNameField.setPlaceholder(JoiDefaults.ASSISTANT_NAME);
        assistantNameField.setHelperText(loc.getValue(J_L.HELP_ASSISTANT_NAME));

        userNameField = new TextField(loc.getValue(J_L.LABEL_USER_NAME));
        userNameField.setHelperText(loc.getValue(J_L.HELP_USER_NAME));

        personalitySelect = new Select<>();
        personalitySelect.setLabel(loc.getValue(J_L.LABEL_PERSONALITY));
        personalitySelect.setItemLabelGenerator(p -> p == null ? "" : p.name());
        personalitySelect.setHelperText(loc.getValue(J_L.HELP_PERSONALITY));

        modelBoxes.put(TextLLMType.CHAT, modelBox(J_L.LABEL_CHAT_MODEL));
        modelBoxes.put(TextLLMType.DEEP_REASONING, modelBox(J_L.LABEL_REASONING_MODEL));
        modelBoxes.put(TextLLMType.AUXILIARY, modelBox(J_L.LABEL_AUXILIARY_MODEL));

        ttsModelSelect = new Select<>();
        ttsModelSelect.setLabel(loc.getValue(J_L.LABEL_TTS_MODEL));
        ttsModelSelect.setItemLabelGenerator(model -> model == null ? "" : model.getName());
        ttsModelSelect.setEmptySelectionAllowed(true);

        voiceActorSelect = new Select<>();
        voiceActorSelect.setLabel(loc.getValue(J_L.LABEL_VOICE_ACTOR));
        voiceActorSelect.setItemLabelGenerator(actor -> actor == null ? "" : actor.getName());
        voiceActorSelect.setEmptySelectionAllowed(true);

        dayBoundaryPicker = new TimePicker(loc.getValue(J_L.LABEL_DAY_BOUNDARY));
        dayBoundaryPicker.setStep(Duration.ofMinutes(30));
        dayBoundaryPicker.setPlaceholder(JoiDefaults.DAY_BOUNDARY);
        dayBoundaryPicker.setClearButtonVisible(true);

        summaryFields = new SummaryFields().create();

        FormLayout form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("40em", 2));
        form.add(assistantNameField, userNameField, personalitySelect);
        modelBoxes.values().forEach(form::add);
        form.add(ttsModelSelect, voiceActorSelect, dayBoundaryPicker);
        summaryFields.addTo(form);
        form.addClassName(CssStyles.FORM_PADDING_BOTTOM);
        return form;
    }

    private ComboBox<AI> modelBox(J_L label) {
        ComboBox<AI> box = new ComboBox<>(loc.getValue(label));
        box.setItemLabelGenerator(AI::getName);
        box.setClearButtonVisible(true);
        box.setHelperText(loc.getValue(J_L.HELP_CHAT_MODEL));
        return box;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // prompts
    // ----------------------------------------------------------------------------------------------------------------

    private Component createPrompts() {
        Span hint = new Span(loc.getValue(J_L.HELP_INHERITED_DEFAULT));
        hint.addClassName(CssStyles.HINT_TEXT);
        VerticalLayout layout = new VerticalLayout(hint);
        layout.setPadding(false);
        for (JoiPromptField field : JoiPromptField.values()) {
            TextAreaPopoverComponent area = PromptAreas.create(loc, loc.getValue(PromptAreas.label(field)), PromptAreas.TEMPLATE_HEIGHT,
                    field.dataClass(), null);
            promptAreas.put(field, area);
            layout.add(area);
            if (field == JoiPromptField.MASTER_TEMPLATE) {
                Span help = new Span(loc.getValue(J_L.HELP_MASTER_TEMPLATE));
                help.addClassName(CssStyles.HINT_TEXT);
                Button preview = new Button(loc.getValue(J_L.LABEL_PREVIEW_SYSTEM_PROMPT), Solid.EYE.create(), e -> preview());
                preview.setThemeName("tertiary");
                layout.add(help, preview);
            }
        }
        layout.addClassName(CssStyles.FORM_PADDING_BOTTOM);
        return layout;
    }

    private void preview() {
        try {
            SystemPromptPreviewDialog dialog = new SystemPromptPreviewDialog(promptAreas.get(JoiPromptField.MASTER_TEMPLATE).getValue());
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // personalities and moods
    // ----------------------------------------------------------------------------------------------------------------

    private Component createPersonalities() {
        Button add = new Button(loc.getValue(J_L.LABEL_ADD_PERSONALITY), Solid.PLUS.create(), e -> openPersonality(null));
        add.setThemeName("primary");
        personalityGrid = new Grid<>(Personality.class, false);
        personalityGrid.setSizeFull();
        personalityGrid.addColumn(Personality::name).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        personalityGrid.addColumn(Personality::key).setHeader(loc.getValue(J_L.LABEL_KEY)).setFlexGrow(1);
        personalityGrid.addColumn(p -> state(p.builtIn(), p.changed())).setHeader(loc.getValue(J_L.LABEL_STATE)).setFlexGrow(0)
                .setWidth("10em");
        personalityGrid.addComponentColumn(p -> actions(p.builtIn(), p.changed(), () -> openPersonality(p.key()),
                () -> personaService.resetPersonality(p.key()), () -> clonePersonality(p))).setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_TRIPLE);
        return gridLayout(add, personalityGrid);
    }

    private Component createMoods() {
        Button add = new Button(loc.getValue(J_L.LABEL_ADD_MOOD), Solid.PLUS.create(), e -> openMood(null));
        add.setThemeName("primary");
        moodGrid = new Grid<>(Mood.class, false);
        moodGrid.setSizeFull();
        moodGrid.addColumn(Mood::name).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        moodGrid.addColumn(Mood::key).setHeader(loc.getValue(J_L.LABEL_KEY)).setFlexGrow(1);
        moodGrid.addColumn(Mood::weight).setHeader(loc.getValue(J_L.LABEL_WEIGHT)).setFlexGrow(0).setWidth("12em");
        moodGrid.addColumn(m -> m.delivery().label()).setHeader(loc.getValue(J_L.LABEL_DELIVERY)).setFlexGrow(0).setWidth("10em");
        moodGrid.addColumn(m -> state(m.builtIn(), m.changed())).setHeader(loc.getValue(J_L.LABEL_STATE)).setFlexGrow(0).setWidth("10em");
        moodGrid.addComponentColumn(m -> actions(m.builtIn(), m.changed(), () -> openMood(m.key()),
                () -> personaService.resetMood(m.key()), null)).setFlexGrow(0).setWidth(UIConstants.TOOL_COLUMN_SIZE_LARGE);
        return gridLayout(add, moodGrid);
    }

    private static Component gridLayout(Button add, Grid<?> grid) {
        HorizontalLayout header = new HorizontalLayout(add);
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        VerticalLayout layout = new VerticalLayout(header, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private String state(boolean builtIn, boolean changed) {
        return loc.getValue(!builtIn ? J_L.LABEL_OWN : changed ? J_L.LABEL_CHANGED : J_L.LABEL_BUILT_IN);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** Edit; clone when there is one to make; reset for a changed built-in one, delete for the user's own one. */
    private Component actions(boolean builtIn, boolean changed, Runnable edit, Action reset, Action clone) {
        Button editButton = new Button(Solid.PEN.create(), e -> edit.run());
        editButton.setTooltipText(loc.getValue(L.LABEL_EDIT));
        Button resetButton = new Button(builtIn ? Solid.UNDO.create() : Solid.TRASH.create(),
                e -> ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        reset.run();
                        reloadLists();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
        resetButton.setTooltipText(loc.getValue(builtIn ? J_L.LABEL_RESET_TO_DEFAULT : L.LABEL_DELETE));
        resetButton.setThemeName(builtIn ? "tertiary" : "error tertiary");
        resetButton.setVisible(!builtIn || changed);
        HorizontalLayout actions = new HorizontalLayout(editButton);
        if (clone != null) {
            Button cloneButton = new Button(Solid.CLONE.create(), e -> {
                try {
                    clone.run();
                } catch (Exception ex) {
                    UIUtils.internalServerError(loc, ex);
                }
            });
            cloneButton.setTooltipText(loc.getValue(J_L.LABEL_CLONE));
            actions.add(cloneButton);
        }
        actions.add(resetButton);
        actions.setSpacing(true);
        return actions;
    }

    /** Makes the user's own personality of this one, and opens it so it can be changed. */
    private void clonePersonality(Personality personality) throws Exception {
        String copy = personaService.clonePersonality(personality.key(),
                loc.getValue(J_L.LABEL_COPY_OF).formatted(personality.name()));
        reloadLists();
        openPersonality(copy);
    }

    private void openPersonality(String key) {
        try {
            PersonalityDialog dialog = new PersonalityDialog(key);
            dialog.setOnSave(this::reloadListsQuietly);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void openMood(String key) {
        try {
            MoodDialog dialog = new MoodDialog(key);
            dialog.setOnSave(this::reloadListsQuietly);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // load and save
    // ----------------------------------------------------------------------------------------------------------------

    /** Every field of General and Prompts: a change of one is unsaved until Save. */
    private List<HasValue<?, ?>> trackedFields() {
        List<HasValue<?, ?>> fields = new ArrayList<>(List.of(assistantNameField, userNameField, personalitySelect));
        fields.addAll(modelBoxes.values());
        fields.addAll(List.of(ttsModelSelect, voiceActorSelect, dayBoundaryPicker));
        fields.addAll(summaryFields.fields());
        fields.addAll(promptAreas.values());
        return fields;
    }

    private void trackChanges() {
        for (HasValue<?, ?> field : trackedFields()) {
            field.addValueChangeListener(e -> {
                if (!loading) {
                    setChanged(true);
                }
            });
        }
    }

    private void setChanged(boolean changed) {
        if (this.changed == changed) {
            return;
        }
        this.changed = changed;
        unsavedSpan.setVisible(changed);
        discardButton.setEnabled(changed);
        updateButtons();
        if (workspace != null) {
            workspace.setNavigationLocked(changed);
        }
    }

    private void updateButtons() {
        boolean formTab = tabs.getSelectedIndex() < 2;
        saveButton.setVisible(formTab || changed);
        discardButton.setVisible(formTab || changed);
    }

    /** Whether General or Prompts have changes not saved yet. */
    public boolean isChanged() {
        return changed;
    }

    private void discard() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    @Override
    public void refresh() throws Exception {
        if (assistantNameField == null) {
            return;
        }
        accessPanel.refresh();
        loading = true;
        try {
            load();
        } finally {
            loading = false;
        }
        setChanged(false);
    }

    private void load() throws Exception {
        JoiUserSetting setting = personaService.getUserSetting();
        JoiDefaults defaults = personaService.defaults();

        assistantNameField.setValue(StringUtils.defaultString(setting.getAssistantName()));
        userNameField.setValue(StringUtils.defaultString(setting.getUserName()));
        userNameField.setPlaceholder(personaService.accountName());
        List<Personality> personalities = personaService.personalities();
        personalitySelect.setItems(personalities);
        personalitySelect.setValue(personaService.currentPersonality());

        List<AI> ais = aiService.findAllForUser();
        RoleModels.fill(modelBoxes.get(TextLLMType.CHAT), ais, TextLLMType.CHAT, setting.getChatModel());
        RoleModels.fill(modelBoxes.get(TextLLMType.DEEP_REASONING), ais, TextLLMType.DEEP_REASONING, setting.getReasoningModel());
        RoleModels.fill(modelBoxes.get(TextLLMType.AUXILIARY), ais, TextLLMType.AUXILIARY, setting.getAuxiliaryModel());

        List<TtsModel> ttsModels = ttsService.models();
        ttsModelSelect.setItems(ttsModels);
        ttsModelSelect.setValue(find(ttsModels, TtsModel::getId, setting.getTtsModel()));
        List<Actor> actors = ttsService.actors();
        voiceActorSelect.setItems(actors);
        voiceActorSelect.setValue(find(actors, Actor::getId, setting.getVoiceActor()));

        dayBoundaryPicker.setValue(parseTime(setting.getDayBoundary()));
        summaryFields.load(setting, null, ais, RoleModels.find(ais, setting.getChatModel()));

        for (JoiPromptField field : JoiPromptField.values()) {
            TextAreaPopoverComponent area = promptAreas.get(field);
            area.setValue(StringUtils.defaultString(field.ofUser(setting)));
            PromptAreas.showDefault(loc, area, field.dataClass(), field.fallback(defaults));
        }

        personalityGrid.setItems(personalities);
        moodGrid.setItems(personaService.moods());
    }

    /**
     * Loads the personalities and moods again after a dialog or a reset stored one, keeping the unsaved changes of the
     * forms (the chosen personality stays chosen).
     */
    private void reloadLists() throws Exception {
        boolean wasLoading = loading;
        loading = true;
        try {
            Personality chosen = personalitySelect.getValue();
            List<Personality> personalities = personaService.personalities();
            personalitySelect.setItems(personalities);
            Personality same = chosen == null ? null
                    : personalities.stream().filter(p -> p.key().equals(chosen.key())).findFirst().orElse(null);
            personalitySelect.setValue(same != null ? same : personaService.currentPersonality());
            personalityGrid.setItems(personalities);
            moodGrid.setItems(personaService.moods());
        } finally {
            loading = wasLoading;
        }
        // a mood's face may have changed: her nodes are told the mood as it is now
        for (Joi joi : joiService.findAllForUser()) {
            nodeService.mood(joi);
        }
    }

    private static <T> T find(List<T> items, Function<T, String> id, String wanted) {
        return wanted == null ? null : items.stream().filter(item -> wanted.equals(id.apply(item))).findFirst().orElse(null);
    }

    private static LocalTime parseTime(String text) {
        try {
            return StringUtils.isBlank(text) ? null : LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void save() {
        try {
            // the templates first: an invalid one is not stored, an unknown name is only a warning
            for (JoiPromptField field : JoiPromptField.values()) {
                TextAreaPopoverComponent area = promptAreas.get(field);
                if (StringUtils.isBlank(area.getValue())) {
                    TemplateHints.showWarnings(loc, area, null);
                    continue;
                }
                TemplateService.ValidationResult result = templateService.isValidTemplate(area.getValue(), field.templateName(),
                        field.dataClass());
                if (!result.isValid()) {
                    Notification.error(loc.getValue(J_L.MSG_TEMPLATE_INVALID).formatted(result.errorMessage()));
                    return;
                }
                TemplateHints.showWarnings(loc, area, result);
            }

            JoiUserSetting setting = personaService.getUserSetting();
            setting.setAssistantName(StringUtils.defaultIfBlank(assistantNameField.getValue().strip(), null));
            setting.setUserName(StringUtils.defaultIfBlank(userNameField.getValue().strip(), null));
            Personality personality = personalitySelect.getValue();
            setting.setPersonality(personality == null ? null : personality.key());
            setting.setChatModel(idOf(modelBoxes.get(TextLLMType.CHAT).getValue()));
            setting.setReasoningModel(idOf(modelBoxes.get(TextLLMType.DEEP_REASONING).getValue()));
            setting.setAuxiliaryModel(idOf(modelBoxes.get(TextLLMType.AUXILIARY).getValue()));
            setting.setTtsModel(ttsModelSelect.getValue() == null ? null : ttsModelSelect.getValue().getId());
            setting.setVoiceActor(voiceActorSelect.getValue() == null ? null : voiceActorSelect.getValue().getId());
            setting.setDayBoundary(dayBoundaryPicker.getValue() == null ? null : dayBoundaryPicker.getValue().toString());
            summaryFields.save(setting);
            for (JoiPromptField field : JoiPromptField.values()) {
                field.setOfUser(setting, promptAreas.get(field).getValue());
            }
            settingService.save(setting);
            setChanged(false);
            Notification.success(loc.getValue(J_L.MSG_SETTINGS_SAVED));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private static Long idOf(AI ai) {
        return ai == null ? null : ai.getId();
    }

    private void reloadListsQuietly() {
        try {
            reloadLists();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // for tests
    // ----------------------------------------------------------------------------------------------------------------

    TextField getAssistantNameField() {
        return assistantNameField;
    }

    TextField getUserNameField() {
        return userNameField;
    }

    void clickDiscard() {
        discard();
    }

    Select<Personality> getPersonalitySelect() {
        return personalitySelect;
    }

    ComboBox<AI> getModelBox(TextLLMType type) {
        return modelBoxes.get(type);
    }

    SummaryFields getSummaryFields() {
        return summaryFields;
    }

    TextAreaPopoverComponent getPromptArea(JoiPromptField field) {
        return promptAreas.get(field);
    }

    Grid<Personality> getPersonalityGrid() {
        return personalityGrid;
    }

    Grid<Mood> getMoodGrid() {
        return moodGrid;
    }

    void clickSave() {
        save();
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(discardButton, saveButton);
    }
}
