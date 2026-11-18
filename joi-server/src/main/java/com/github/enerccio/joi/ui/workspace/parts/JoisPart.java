package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.ChatMessageService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.dialogs.TextInputDialog;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The assistants of the user ({@link Joi}): their names and when they were last talked to, opened in a
 * {@link JoiDialog}. <i>Create Joi</i> asks for a name (prefilled with the one of the user's settings, else the default)
 * and creates her.
 */
@Configurable
public class JoisPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private JoiService joiService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private PersonaService personaService;

    private Grid<Joi> grid;
    private Button createButton;
    private final Map<Long, String> names = new HashMap<>();
    private final Map<Long, Date> lastChatted = new HashMap<>();

    @Override
    public Component create() {
        createButton = new Button(loc.getValue(J_L.LABEL_CREATE_JOI), Solid.PLUS.create(), e -> askName());
        createButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(createButton);
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        grid = new Grid<>(Joi.class, false);
        grid.setSizeFull();
        grid.addColumn(joi -> names.getOrDefault(joi.getId(), ""))
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        grid.addColumn(joi -> {
                    Date last = lastChatted.get(joi.getId());
                    return last == null ? loc.getValue(L.LABEL_NEVER) : format.format(last);
                })
                .setHeader(loc.getValue(J_L.LABEL_LAST_CHATTED))
                .setFlexGrow(0)
                .setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.addComponentColumn(this::actions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_HUGE);
        grid.addItemDoubleClickListener(e -> open(e.getItem()));

        VerticalLayout layout = new VerticalLayout(header, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private Component actions(Joi joi) {
        Button openButton = new Button(Solid.FOLDER_OPEN.create(), e -> open(joi));
        openButton.setTooltipText(loc.getValue(L.LABEL_OPEN));

        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        joiService.delete(joiService.find(joi.getId()), false);
                        refresh();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));

        HorizontalLayout actions = new HorizontalLayout(openButton, deleteButton);
        actions.setSpacing(true);
        return actions;
    }

    /** Asks for the name of a new assistant, prefilled with the user's (or the default) one. */
    private void askName() {
        try {
            nameDialog().open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The dialog of {@link #askName()}. */
    TextInputDialog nameDialog() throws Exception {
        return new TextInputDialog.Builder(loc.getValue(J_L.LABEL_CREATE_JOI), value -> {
            try {
                joiService.create(value);
                refresh();
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        })
                .showCancel(true)
                .messageRequired()
                .inputMessageLabel(loc.getValue(L.LABEL_NAME))
                .initialValue(personaService.assistantName())
                .build();
    }

    private void open(Joi joi) {
        try {
            JoiDialog dialog = new JoiDialog(joiService.find(joi.getId()));
            dialog.setOnClose(this::refreshQuietly);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    @Override
    public void refresh() throws Exception {
        if (grid == null) {
            return;
        }
        List<Joi> jois = joiService.findAllByName();
        names.clear();
        for (Joi joi : jois) {
            names.put(joi.getId(), joiService.name(joi));
        }
        lastChatted.clear();
        lastChatted.putAll(chatMessageService.findLastSentAt(jois));
        grid.setItems(jois);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid of the assistants, for tests. */
    Grid<Joi> getGrid() {
        return grid;
    }

    /** What Open does, for tests. */
    void clickOpen(Joi joi) {
        open(joi);
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(createButton);
    }
}
