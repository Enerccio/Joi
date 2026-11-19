package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.AnimationPackDialog;
import com.github.enerccio.joi.ui.workspace.WorkspacePart;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.LLMInteract;
import com.github.enerccio.marginalia.ui.LLMInteraction;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The animations of the user ({@link AnimationPack}, see {@code DESIGN.md} → Bodies, animations): a table with
 * checkboxes (Delete selected), the name, the description, how many steps and when it was added, Open and Delete (its
 * clips stay in the library); <i>Add animation</i> and Open show the {@link AnimationPackDialog} (steps of clips of the
 * library, previewed on a body).
 */
@Configurable
public class AnimationPacksPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationPackService packService;

    private Grid<AnimationPack> grid;
    private Button addButton;
    private Button deleteSelectedButton;

    @Override
    public Component create() {
        H4 title = new H4(loc.getValue(J_L.LABEL_ANIMATION_PACKS));
        addButton = new Button(loc.getValue(J_L.LABEL_ADD_ANIMATION_PACK), Solid.PLUS.create(), e -> open(new AnimationPack()));
        addButton.setThemeName("primary");
        deleteSelectedButton = new Button(loc.getValue(L.LABEL_DELETE_SELECTED), Solid.TRASH.create(), e -> deleteSelected());
        deleteSelectedButton.setThemeName("error");
        deleteSelectedButton.setEnabled(false);
        HorizontalLayout buttons = new HorizontalLayout(deleteSelectedButton, addButton);
        buttons.setAlignItems(FlexComponent.Alignment.CENTER);
        HorizontalLayout header = new HorizontalLayout(title, buttons);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        grid = new Grid<>(AnimationPack.class, false);
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        grid.addSelectionListener(e -> deleteSelectedButton.setEnabled(!e.getAllSelectedItems().isEmpty()));
        grid.setSizeFull();
        grid.addColumn(pack -> StringUtils.defaultIfBlank(pack.getName(), loc.getValue(J_L.LABEL_NEW_ANIMATION_PACK)))
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        grid.addColumn(pack -> StringUtils.abbreviate(StringUtils.defaultString(pack.getDescription()), 120))
                .setHeader(loc.getValue(L.LABEL_DESCRIPTION))
                .setFlexGrow(2);
        grid.addColumn(pack -> pack.steps().size())
                .setHeader(loc.getValue(J_L.LABEL_ANIMATION_STEPS))
                .setFlexGrow(0)
                .setWidth("90px");
        grid.addColumn(pack -> pack.getCreation() == null ? "" : format.format(pack.getCreation()))
                .setHeader(loc.getValue(L.LABEL_CREATED))
                .setFlexGrow(0)
                .setWidth(UIConstants.DATE_COLUMN_SIZE);
        grid.addComponentColumn(this::actions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_TRIPLE);
        grid.addItemDoubleClickListener(e -> open(e.getItem()));

        VerticalLayout layout = new VerticalLayout(header, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.setFlexGrow(1, grid);
        return layout;
    }

    private Component actions(AnimationPack pack) {
        Button openButton = new Button(Solid.FOLDER_OPEN.create(), e -> open(pack));
        openButton.setTooltipText(loc.getValue(L.LABEL_OPEN));

        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        packService.delete(packService.find(pack.getId()), false);
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

    /** Deletes the animations checked in the table, once confirmed (their clips stay). */
    private void deleteSelected() {
        List<AnimationPack> selected = new ArrayList<>(grid.getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        ConfirmDialog.show(String.format(loc.getValue(J_L.MSG_CONFIRM_DELETE_ANIMATIONS), selected.size()), () -> {
            try {
                for (AnimationPack pack : selected) {
                    packService.delete(packService.find(pack.getId()), false);
                }
                grid.deselectAll();
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
    }

    /** Shows the dialog of the pack, a new one (not stored until it has a name or a clip) or a stored one. */
    private void open(AnimationPack pack) {
        try {
            AnimationPackDialog dialog = new AnimationPackDialog(pack.getId() == null ? pack : packService.find(pack.getId()));
            dialog.setOnClose(this::refreshQuietly);
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    @Override
    public void refresh() throws Exception {
        if (grid != null) {
            grid.setItems(packService.findAllByName());
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid of the packs, for tests. */
    Grid<AnimationPack> getGrid() {
        return grid;
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(addButton);
    }
}
