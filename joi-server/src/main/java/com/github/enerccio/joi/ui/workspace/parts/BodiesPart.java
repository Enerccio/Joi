package com.github.enerccio.joi.ui.workspace.parts;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.ModelDialog;
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
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.text.DateFormat;
import java.util.Map;

/**
 * The bodies of the user (see {@code DESIGN.md} → Bodies). For now their 3D models ({@link Model}): a table with the
 * name, the format, the size and when it was added, Open and Delete; <i>Add model</i> and Open show the
 * {@link ModelDialog} (upload and preview).
 */
@Configurable
public class BodiesPart implements WorkspacePart {

    @Autowired
    private Localization loc;

    @Autowired
    private ModelService modelService;

    private Grid<Model> grid;
    private Button addButton;

    @Override
    public Component create() {
        H4 title = new H4(loc.getValue(J_L.LABEL_3D_MODELS));
        addButton = new Button(loc.getValue(J_L.LABEL_ADD_3D_MODEL), Solid.PLUS.create(), e -> open(new Model()));
        addButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(title, addButton);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        grid = new Grid<>(Model.class, false);
        grid.setSizeFull();
        grid.addColumn(Model::getName)
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        grid.addColumn(model -> model.getFormat() == null ? "" : model.getFormat().name()
                        + (model.getMainFile() == null ? "" : " (zip)"))
                .setHeader(loc.getValue(J_L.LABEL_FORMAT))
                .setFlexGrow(0)
                .setWidth("120px");
        grid.addColumn(model -> model.getFileSize() == null ? "" : FileUtils.byteCountToDisplaySize(model.getFileSize()))
                .setHeader(loc.getValue(L.LABEL_SIZE))
                .setFlexGrow(0)
                .setWidth("110px");
        grid.addColumn(model -> model.getCreation() == null ? "" : format.format(model.getCreation()))
                .setHeader(loc.getValue(L.LABEL_CREATED))
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

    private Component actions(Model model) {
        Button openButton = new Button(Solid.FOLDER_OPEN.create(), e -> open(model));
        openButton.setTooltipText(loc.getValue(L.LABEL_OPEN));

        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        modelService.delete(modelService.find(model.getId()), false);
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

    /** Shows the dialog of the model, a new one (not stored until it has a name or a file) or a stored one. */
    private void open(Model model) {
        try {
            ModelDialog dialog = new ModelDialog(model.getId() == null ? model : modelService.find(model.getId()));
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
            grid.setItems(modelService.findAllByName());
        }
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid of the models, for tests. */
    Grid<Model> getGrid() {
        return grid;
    }

    @Override
    public Map<String, LLMInteraction> getInteraction() {
        return LLMInteract.dialogButtons(addButton);
    }
}
