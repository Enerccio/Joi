package com.github.enerccio.joi.ui.dialogs.joi;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.ModelService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.dialogs.BodyDialog;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.UIConstants;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.List;

/**
 * Her bodies ({@link Body}): a table with the one she is in, the name and the models, Open, Delete and a button that
 * puts her into a body; <i>Add body</i> and Open show the {@link BodyDialog}. Switching is an event for her once she
 * has chatted ({@link BodyService#activate}).
 */
@Configurable
public class JoiBodiesPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private BodyService bodyService;

    @Autowired
    private ModelService modelService;

    private Joi joi;
    private Grid<Body> grid;
    private Span empty;
    private Button addButton;

    @Override
    public J_L caption() {
        return J_L.LABEL_BODIES;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.HELP_BODY_EVENTS));
        hint.addClassName(CssStyles.HINT_TEXT);
        addButton = new Button(loc.getValue(J_L.LABEL_ADD_BODY), Solid.PLUS.create(), e -> open(null));
        addButton.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(hint, addButton);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        empty = new Span(loc.getValue(J_L.MSG_NO_BODIES));
        empty.addClassName(CssStyles.HINT_TEXT);

        grid = new Grid<>(Body.class, false);
        grid.setSizeFull();
        grid.addComponentColumn(this::active)
                .setHeader(loc.getValue(J_L.LABEL_ACTIVE_BODY))
                .setFlexGrow(0)
                .setWidth("130px");
        grid.addColumn(Body::getName)
                .setHeader(loc.getValue(L.LABEL_NAME))
                .setFlexGrow(1);
        grid.addColumn(body -> modelName(body.getModel()))
                .setHeader(loc.getValue(J_L.LABEL_BODY_MODEL))
                .setFlexGrow(1);
        grid.addColumn(body -> modelName(body.getLookingGlassModel()))
                .setHeader(loc.getValue(J_L.LABEL_LOOKING_GLASS_MODEL))
                .setFlexGrow(1);
        grid.addComponentColumn(this::actions)
                .setHeader("")
                .setFlexGrow(0)
                .setWidth(UIConstants.TOOL_COLUMN_SIZE_HUGE);
        grid.addItemDoubleClickListener(e -> open(e.getItem()));

        VerticalLayout layout = new VerticalLayout(header, empty, grid);
        layout.setSizeFull();
        layout.setFlexGrow(1, grid);
        return layout;
    }

    /** A mark on the body she is in, a button to put her into another one. */
    private Component active(Body body) {
        if (body.isActive()) {
            return Solid.CHECK_CIRCLE.create();
        }
        Button button = new Button(Solid.PERSON_WALKING_ARROW_RIGHT.create(), e -> {
            try {
                bodyService.activate(joi, bodyService.find(body.getId()));
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        button.setThemeName("tertiary");
        button.setTooltipText(loc.getValue(J_L.LABEL_SWITCH_TO_BODY));
        return button;
    }

    private Component actions(Body body) {
        Button openButton = new Button(Solid.FOLDER_OPEN.create(), e -> open(body));
        openButton.setTooltipText(loc.getValue(L.LABEL_OPEN));
        Button deleteButton = new Button(Solid.TRASH.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
                    try {
                        bodyService.deleteBody(body);
                        refresh();
                    } catch (Exception ex) {
                        UIUtils.internalServerError(loc, ex);
                    }
                }));
        deleteButton.setThemeName("error tertiary");
        deleteButton.setTooltipText(loc.getValue(L.LABEL_DELETE));
        return new HorizontalLayout(openButton, deleteButton);
    }

    /** The name of a model of a body (a lazy reference: read by its id). */
    private String modelName(Model reference) {
        if (reference == null) {
            return "";
        }
        try {
            Model model = modelService.find(reference.getId());
            return model == null || model.isDeleted() ? "" : StringUtils.defaultString(model.getName());
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
            return "";
        }
    }

    /** Shows the dialog of the body, a new one (null; stored once it has a name or a model) or a stored one. */
    private void open(Body body) {
        try {
            BodyDialog dialog = new BodyDialog(joi, body == null ? null : bodyService.find(body.getId()));
            dialog.setOnClose(() -> {
                try {
                    refresh();
                } catch (Exception e) {
                    UIUtils.internalServerError(loc, e);
                }
            });
            dialog.create();
            dialog.open();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    @Override
    public void load(Joi joi) throws Exception {
        this.joi = joi;
        refresh();
    }

    @Override
    public void shown() throws Exception {
        refresh();
    }

    private void refresh() throws Exception {
        List<Body> bodies = bodyService.findForJoi(joi);
        grid.setItems(bodies);
        empty.setVisible(bodies.isEmpty());
    }

    /** The table, for tests. */
    public Grid<Body> getGrid() {
        return grid;
    }
}
