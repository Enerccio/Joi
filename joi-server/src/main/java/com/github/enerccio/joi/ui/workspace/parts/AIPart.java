package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.Usage;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.InferenceServices;
import com.github.enerccio.marginalia.domain.service.UsageService;
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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The models (the platform's {@link AI}: an endpoint, its model and settings) the user owns or that are shared with
 * them, with the tokens they used. Created and edited with the platform's {@link AIDialog}. The assistant's model
 * roles (conversation, heavy) will pick from these.
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

    private Grid<AI> grid;
    private Button addButton;
    private final Map<Long, Usage> usages = new HashMap<>();
    private boolean administrator;
    private boolean canCreate;

    @Override
    public Component create() {
        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setPadding(true);
        layout.setSpacing(true);

        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);

        Span title = new Span(loc.getValue(J_L.LABEL_MODELS));
        title.addClassName(CssStyles.SECTION_TITLE);

        addButton = new Button(loc.getValue(J_L.LABEL_ADD_MODEL), event -> openDialog(new AIDialog()));
        addButton.setThemeName("primary");

        // the usage of everybody, only for administrators
        Button usagePerUserButton = new Button(loc.getValue(L.LABEL_USAGE_PER_USER), Solid.CHART_BAR.create(),
                event -> UsageDialog.show());
        usagePerUserButton.setVisible(aiService.isAdministrator());

        header.add(title, usagePerUserButton, addButton);
        header.setFlexGrow(1, title);

        grid = new Grid<>(AI.class, false);
        grid.setSizeFull();
        grid.addColumn(ai -> AITypes.label(loc, inferenceServices, ai))
                .setHeader(loc.getValue(L.LABEL_TYPE))
                .setFlexGrow(1);
        grid.addColumn(AI::getName)
                .setHeader(loc.getValue(L.LABEL_NAME))
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
        Button editButton = new Button(Solid.PEN.create(), event -> openDialog(new AIDialog(ai)));
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

    private void openDialog(AIDialog dialog) {
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

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(addButton);
    }

    private static String formatTokens(long tokens) {
        return NumberFormat.getIntegerInstance().format(tokens);
    }
}
