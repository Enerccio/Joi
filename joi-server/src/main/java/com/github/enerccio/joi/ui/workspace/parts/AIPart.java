package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.components.TextModelTypes;
import com.github.enerccio.joi.domain.service.TtsService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.tts.model.Actor;
import com.github.enerccio.joi.tts.model.TtsModel;
import com.github.enerccio.joi.ui.dialogs.JoiAIDialog;
import com.github.enerccio.joi.ui.dialogs.TtsModelDialog;
import com.github.enerccio.joi.ui.tts.ActorDialog;
import com.github.enerccio.joi.ui.tts.VoiceChoices;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.Usage;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.GpuService;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.UsageService;
import com.github.enerccio.marginalia.domain.service.gpu.GpuLockChoice;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.*;
import com.github.enerccio.marginalia.utils.AITypes;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The models of the user, in a tab sheet by kind:
 * <ul>
 *     <li>Text Models: the platform's {@link AI}s the user owns or that are shared with them (an endpoint, its model and
 *     settings) with what each is used for ({@link TextLLMType}) and the tokens used, edited with {@link JoiAIDialog};</li>
 *     <li>TTS Models: the user's speech servers ({@link TtsModel}), edited with {@link TtsModelDialog};</li>
 *     <li>Voice Actors: the user's voices ({@link Actor}), edited with {@link ActorDialog}.</li>
 * </ul>
 * The assistant's model roles and its voice pick from these.
 */
@Configurable
public class AIPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private AIService aiService;

    @Autowired
    private InferenceServices inferenceServices;

    @Autowired
    private UsageService usageService;

    @Autowired
    private TtsService ttsService;

    @Autowired(required = false)
    private GpuService gpuService;

    private Grid<AI> grid;
    private Button addButton;
    private final Map<Long, Usage> usages = new HashMap<>();
    private boolean administrator;
    private boolean canCreate;

    private Grid<TtsModel> ttsGrid;
    private Button addTtsButton;
    private Grid<Actor> actorGrid;
    private Button addActorButton;
    private final Map<String, String> lockNames = new HashMap<>();
    private VoiceChoices voiceLabels;

    @Override
    public Component create() {
        TabSheet kinds = new TabSheet();
        kinds.setSizeFull();
        kinds.add(loc.getValue(J_L.LABEL_TEXT_MODELS), createTextModels());
        kinds.add(loc.getValue(J_L.LABEL_TTS_MODELS), createTtsModels());
        kinds.add(loc.getValue(J_L.LABEL_VOICE_ACTORS), createVoiceActors());
        return kinds;
    }

    private Component createTtsModels() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);

        addTtsButton = new Button(loc.getValue(J_L.LABEL_ADD_TTS_MODEL), event -> openTtsDialog(new TtsModelDialog()));
        addTtsButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(addTtsButton);
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        ttsGrid = new Grid<>(TtsModel.class, false);
        ttsGrid.setSizeFull();
        ttsGrid.addColumn(TtsModel::getName)
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        ttsGrid.addColumn(TtsModel::getModel)
                .setHeader(loc.getValue(J_L.LABEL_SPEECH_MODEL))
                .setFlexGrow(1);
        ttsGrid.addColumn(TtsModel::getBaseUrl)
                .setHeader(loc.getValue(J_L.LABEL_ADDRESS))
                .setFlexGrow(1);
        ttsGrid.addColumn(model -> model.getGpuLock() == null ? "" : lockNames.getOrDefault(model.getGpuLock(), "?"))
                .setHeader(loc.getValue(L.LABEL_GPU_LOCK))
                .setFlexGrow(1);
        ttsGrid.addComponentColumn(this::ttsActions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_TRIPLE);

        layout.add(header, ttsGrid);
        layout.setFlexGrow(1, ttsGrid);
        return layout;
    }

    private Component ttsActions(TtsModel model) {
        Button editButton = new Button(Solid.PEN.create(), event -> openTtsDialog(new TtsModelDialog(model)));
        editButton.setTooltipText(loc.getValue(L.LABEL_EDIT));

        Button cloneButton = new Button(Solid.CLONE.create(), event -> new TextInputDialog.Builder(loc.getValue(J_L.LABEL_CLONE_MODEL), name -> {
            try {
                ttsService.cloneModel(model.getId(), name);
                refresh();
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        })
                .showCancel(true)
                .messageRequired()
                .inputMessageLabel(loc.getValue(L.LABEL_NAME))
                .initialValue(model.getName() + " (" + loc.getValue(L.LABEL_COPY) + ")")
                .build()
                .open());
        cloneButton.setTooltipText(loc.getValue(J_L.LABEL_CLONE_MODEL));

        Button deleteButton = new Button(Solid.TRASH.create(), event ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        ttsService.deleteModel(model.getId());
                        refresh();
                    } catch (Exception e) {
                        UIUtils.internalServerError(loc, e);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));

        HorizontalLayout actions = new HorizontalLayout(editButton, cloneButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    private void openTtsDialog(TtsModelDialog dialog) {
        dialog.setOnSave(this::refreshQuietly);
        dialog.create();
        dialog.open();
    }

    private Component createVoiceActors() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);

        addActorButton = new Button(loc.getValue(J_L.LABEL_ADD_ACTOR), event -> openActorDialog(new ActorDialog()));
        addActorButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(addActorButton);
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        actorGrid = new Grid<>(Actor.class, false);
        actorGrid.setSizeFull();
        actorGrid.addComponentColumn(AIPart::nameWithTagline)
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        actorGrid.addColumn(actor -> voiceLabels == null ? actor.getVoice() : voiceLabels.label(actor.getVoice()))
                .setHeader(loc.getValue(J_L.LABEL_VOICE))
                .setFlexGrow(1);
        actorGrid.addComponentColumn(this::actorActions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_HUGE);

        layout.add(header, actorGrid);
        layout.setFlexGrow(1, actorGrid);
        return layout;
    }

    private static Component nameWithTagline(Actor actor) {
        Div item = new Div(new Div(actor.getName()));
        if (!actor.getTagline().isBlank()) {
            Div line = new Div(actor.getTagline());
            line.addClassNames(com.github.enerccio.marginalia.CssStyles.TEXT_XS, com.github.enerccio.marginalia.CssStyles.TEXT_SECONDARY);
            item.add(line);
        }
        return item;
    }

    private Component actorActions(Actor actor) {
        Button editButton = new Button(Solid.PEN.create(), event -> openActorDialog(new ActorDialog(actor)));
        editButton.setTooltipText(loc.getValue(L.LABEL_EDIT));

        Button deleteButton = new Button(Solid.TRASH.create(), event ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        ttsService.deleteActor(actor.getId());
                        refresh();
                    } catch (Exception e) {
                        UIUtils.internalServerError(loc, e);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));

        HorizontalLayout actions = new HorizontalLayout(editButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    private void openActorDialog(ActorDialog dialog) {
        dialog.setOnSave(this::refreshQuietly);
        try {
            dialog.create();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return;
        }
        dialog.open();
    }

    private Component createTextModels() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setSpacing(true);

        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        addButton = new Button(loc.getValue(J_L.LABEL_ADD_MODEL), event -> openDialog(new JoiAIDialog()));
        addButton.setThemeName("primary");

        // the usage of everybody, only for administrators
        Button usagePerUserButton = new Button(loc.getValue(L.LABEL_USAGE_PER_USER), Solid.CHART_BAR.create(),
                event -> UsageDialog.show());
        usagePerUserButton.setVisible(aiService.isAdministrator());

        header.add(usagePerUserButton, addButton);

        grid = new Grid<>(AI.class, false);
        grid.setSizeFull();
        grid.addColumn(ai -> AITypes.label(loc, inferenceServices, ai))
                .setHeader(loc.getValue(L.LABEL_TYPE))
                .setFlexGrow(1);
        grid.addColumn(AI::getName)
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        grid.addColumn(ai -> TextModelTypes.get(ai).stream().map(type -> loc.getValue(type.getLabel()))
                        .collect(Collectors.joining(", ")))
                .setHeader(loc.getValue(J_L.LABEL_USED_FOR))
                .setFlexGrow(1);
        grid.addColumn(ai -> loc.getValue(ai.isShared() ? L.LABEL_YES : L.LABEL_NO))
                .setHeader(loc.getValue(J_L.LABEL_SHARED))
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_LARGE);
        grid.addColumn(ai -> formatTokens(usage(ai) == null ? 0 : usage(ai).getPrefillTokens()))
                .setHeader(loc.getValue(L.LABEL_TOKENS_SENT))
                .setFlexGrow(0)
                .setWidth(UIConstants.NUMBER_COLUMN_SIZE);
        grid.addColumn(ai -> formatTokens(usage(ai) == null ? 0 : usage(ai).getGeneratedTokens()))
                .setHeader(loc.getValue(L.LABEL_TOKENS_GENERATED))
                .setFlexGrow(0)
                .setWidth(UIConstants.NUMBER_COLUMN_SIZE);
        grid.addComponentColumn(this::actions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_FIVE);

        layout.add(header, grid);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private Component actions(AI ai) {
        Button editButton = new Button(Solid.PEN.create(), event -> openDialog(new JoiAIDialog(ai)));
        editButton.setTooltipText(loc.getValue(L.LABEL_EDIT));

        Button cloneButton = new Button(Solid.CLONE.create(), event -> cloneModel(ai));
        cloneButton.setTooltipText(loc.getValue(J_L.LABEL_CLONE_MODEL));

        Button deleteButton = new Button(Solid.TRASH.create(), event ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        aiService.delete(ai, false);
                        refresh();
                    } catch (Exception e) {
                        UIUtils.internalServerError(loc, e);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));

        // what the user used of a shared AI and what they are limited to
        Button infoButton = new Button(Solid.INFO_CIRCLE.create(), event -> AILimitInfoDialog.show(ai));
        infoButton.setTooltipText(loc.getValue(L.LABEL_YOUR_LIMITS));
        infoButton.setVisible(ai.isShared());

        // the limits of the users of a shared AI
        Button limitsButton = new Button(Solid.USER_LOCK.create(), event -> AILimitsDialog.show(ai));
        limitsButton.setTooltipText(loc.getValue(L.LABEL_LIMITS));

        // AIs shared by somebody else can be used, not seen or changed
        boolean own = aiService.isOwnedByCurrentUser(ai);
        editButton.setVisible(own);
        cloneButton.setVisible(own && canCreate);
        deleteButton.setVisible(own);
        limitsButton.setVisible(own && ai.isShared() && administrator);

        HorizontalLayout actions = new HorizontalLayout(infoButton, limitsButton, editButton, cloneButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    private void openDialog(JoiAIDialog dialog) {
        dialog.setOnSave(this::refreshQuietly);
        dialog.create();
        dialog.open();
    }

    private void cloneModel(AI ai) {
        new TextInputDialog.Builder(loc.getValue(J_L.LABEL_CLONE_MODEL), name -> {
            try {
                aiService.cloneAI(ai, name);
                refresh();
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        })
                .showCancel(true)
                .messageRequired()
                .inputMessageLabel(loc.getValue(L.LABEL_NAME))
                .initialValue(ai.getName() + " (" + loc.getValue(L.LABEL_COPY) + ")")
                .build()
                .open();
    }

    @Override
    public void refresh() throws Exception {
        if (grid == null) {
            return;
        }
        List<AI> models = aiService.findAllForUser();
        models.sort(Comparator.comparing(AI::getName, String.CASE_INSENSITIVE_ORDER));
        administrator = aiService.isAdministrator();
        canCreate = aiService.canCreate();
        usages.clear();
        usageService.findAllForUser().forEach(usage -> usages.put(usage.getAi().getId(), usage));
        addButton.setVisible(canCreate);
        grid.setItems(models);

        lockNames.clear();
        if (gpuService != null) {
            for (GpuLockChoice choice : gpuService.lockChoices()) {
                lockNames.put(choice.uuid(), choice.label());
            }
        }
        ttsGrid.setItems(ttsService.models());
        voiceLabels = new VoiceChoices(ttsService);
        actorGrid.setItems(ttsService.actors());
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private Usage usage(AI ai) {
        return usages.get(ai.getId());
    }

    /**
     * The grid of the models, for tests.
     */
    Grid<AI> getGrid() {
        return grid;
    }

    /**
     * The grid of the TTS models, for tests.
     */
    Grid<TtsModel> getTtsGrid() {
        return ttsGrid;
    }

    /**
     * The grid of the voice actors, for tests.
     */
    Grid<Actor> getActorGrid() {
        return actorGrid;
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(addButton, addTtsButton, addActorButton);
    }

    private static String formatTokens(long tokens) {
        return NumberFormat.getIntegerInstance().format(tokens);
    }
}
