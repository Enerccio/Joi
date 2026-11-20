package com.github.enerccio.joi.ui.dialogs;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.WebTaskService;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.List;

/**
 * What the user approved ahead of time for an assistant ("Approve all of this tool"): the tool's name and description, and
 * a button to be asked again. A task of these still goes through approval, which approves it at once; she says so when it
 * ends, so it is in the chat.
 */
@Configurable
public class AutoApprovedDialog extends Dialog {

    @Autowired
    private Localization loc;

    @Autowired
    private WebTaskService tasks;

    private final Joi joi;
    private Runnable onChange = () -> {
    };
    private Grid<ServerTool> grid;

    public AutoApprovedDialog(Joi joi) {
        this.joi = joi;
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {
        } : onChange;
    }

    public void create() throws Exception {
        setHeaderTitle(loc.getValue(J_L.LABEL_AUTO_APPROVED));
        setWidth("min(44em, 95vw)");
        Span hint = new Span(loc.getValue(J_L.MSG_AUTO_APPROVED_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        grid = new Grid<>();
        grid.setAllRowsVisible(true);
        grid.addColumn(ServerTool::function).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(0).setWidth("12rem");
        grid.addColumn(ServerTool::description).setHeader(loc.getValue(L.LABEL_DESCRIPTION)).setFlexGrow(1);
        grid.addComponentColumn(tool -> {
            Button remove = new Button(loc.getValue(J_L.LABEL_AUTO_APPROVED_REMOVE), Solid.UNDO.create(), e -> remove(tool));
            remove.setThemeName("tertiary");
            return remove;
        }).setFlexGrow(0).setWidth("11rem");
        grid.setEmptyStateText(loc.getValue(J_L.MSG_NO_AUTO_APPROVED));
        VerticalLayout content = new VerticalLayout(hint, grid);
        content.setPadding(false);
        add(content);
        Button close = new Button(loc.getValue(L.LABEL_CLOSE), e -> close());
        close.setThemeName("primary");
        getFooter().add(close);
        refresh();
    }

    private void refresh() throws Exception {
        List<ServerTool> approved = tasks.autoApproved(joi);
        grid.setItems(approved);
    }

    private void remove(ServerTool tool) {
        try {
            tasks.autoApprove(joi, tool, false);
            refresh();
            onChange.run();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** The grid, for tests. */
    public Grid<ServerTool> getGrid() {
        return grid;
    }
}
