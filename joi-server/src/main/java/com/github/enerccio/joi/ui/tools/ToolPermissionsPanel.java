package com.github.enerccio.joi.ui.tools;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.ToolMetadataService;
import com.github.enerccio.joi.domain.service.ToolPermissionService;
import com.github.enerccio.joi.domain.service.ToolPermissionService.Chain;
import com.github.enerccio.joi.domain.extensions.JoiExtensions;
import com.github.enerccio.joi.domain.tools.ApprovableTool;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.extensions.ExtensionTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What she may do with each tool that needs approval, in one place of the chain: the administrator's (Administration →
 * Tools), the user's (User Settings → Tools) or the assistant's (her dialog, Tasks). A row per tool, the server's and then those the
 * loaded extensions declared ({@link ExtensionTool}): a select (Inherit, Allow, Ask, Deny, stored at once) and what is in force for
 * the viewer and where it comes from.
 */
@Configurable
public class ToolPermissionsPanel {

    @Autowired
    private Localization loc;

    @Autowired
    private ToolMetadataService metadataService;

    @Autowired
    private ToolPermissionService permissionService;

    @Autowired
    private JoiExtensions extensions;

    private final ToolScope scope;
    private Joi joi;
    private final Map<ApprovableTool, Select<ToolPermission>> selects = new LinkedHashMap<>();
    private final Map<ApprovableTool, Span> states = new LinkedHashMap<>();

    public ToolPermissionsPanel(ToolScope scope) {
        this.scope = scope;
    }

    public Component create() {
        Span help = new Span(loc.getValue(J_L.HELP_TOOL_PERMISSIONS));
        help.addClassName(CssStyles.HINT_TEXT);
        VerticalLayout layout = new VerticalLayout(help);
        layout.setPadding(false);
        layout.addClassName(JoiCssStyles.TOOL_PERMISSIONS);
        for (ApprovableTool tool : tools()) {
            Select<ToolPermission> select = new Select<>();
            select.setLabel(toolLabel(tool));
            select.setItems(ToolPermission.values());
            select.setItemLabelGenerator(permission -> permission == null ? "" : label(permission));
            select.setEmptySelectionAllowed(true);
            select.setEmptySelectionCaption(loc.getValue(J_L.LABEL_PERMISSION_INHERIT));
            select.setWidth("14rem");
            select.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    save(tool, e.getValue());
                }
            });
            Span state = new Span();
            state.addClassName(CssStyles.HINT_TEXT);
            HorizontalLayout row = new HorizontalLayout(select, state);
            row.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
            row.setWidthFull();
            row.setWrap(true);
            layout.add(row);
            selects.put(tool, select);
            states.put(tool, state);
        }
        return layout;
    }

    /** Shows the place: the assistant's own for {@link ToolScope#JOI}, else the user's or the administrator's. */
    public void load(Joi joi) {
        this.joi = joi;
        for (ApprovableTool tool : selects.keySet()) {
            try {
                ToolMetadata metadata = metadataService.find(scope, scope == ToolScope.JOI ? joi : null, tool);
                selects.get(tool).setValue(metadata == null ? null : metadata.getPermission());
                showState(tool);
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        }
    }

    private void save(ApprovableTool tool, ToolPermission permission) {
        try {
            ToolMetadata metadata = metadataService.edit(scope, scope == ToolScope.JOI ? joi : null, tool);
            if (permission == null && metadata.getId() == null) {
                showState(tool);
                return;
            }
            metadata.setPermission(permission);
            metadataService.save(metadata);
            showState(tool);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    /** What is in force for the viewer: along the whole chain, or from this place up on a page of a higher one. */
    private void showState(ApprovableTool tool) throws Exception {
        Chain chain = permissionService.chain(scope == ToolScope.JOI ? joi : null, tool);
        ToolPermission effective = scope == ToolScope.APP ? (chain.admin() != null ? chain.admin() : tool.fallback()) : chain.effective();
        ToolScope source = scope == ToolScope.APP ? (chain.admin() != null ? ToolScope.APP : null) : chain.source();
        String text = String.format(loc.getValue(J_L.MSG_PERMISSION_IN_FORCE), label(effective), from(source));
        if (scope != ToolScope.APP && chain.capped()) {
            text += " " + String.format(loc.getValue(J_L.MSG_PERMISSION_CAPPED), label(chain.value()));
        }
        states.get(tool).setText(text);
    }

    /** The server's tools, then those of the loaded extensions (the rows are made when the panel is). */
    private List<ApprovableTool> tools() {
        List<ApprovableTool> all = new ArrayList<>(List.of(ServerTool.values()));
        extensions.declaredTools().forEach(declared -> all.add(declared.tool()));
        return all;
    }

    private String toolLabel(ApprovableTool tool) {
        if (tool instanceof ServerTool server) {
            return loc.getValue(switch (server) {
                case WEB_SEARCH -> J_L.LABEL_TOOL_WEB_SEARCH;
                case FETCH_PAGE -> J_L.LABEL_TOOL_FETCH_PAGE;
            });
        }
        return tool instanceof ExtensionTool extensionTool ? extensionTool.label() : tool.function();
    }

    private String label(ToolPermission permission) {
        return loc.getValue(switch (permission) {
            case ALLOW -> J_L.LABEL_PERMISSION_ALLOW;
            case ASK -> J_L.LABEL_PERMISSION_ASK;
            case DENY -> J_L.LABEL_PERMISSION_DENY;
        });
    }

    private String from(ToolScope source) {
        return loc.getValue(source == null ? J_L.LABEL_PERMISSION_FROM_BUILTIN : switch (source) {
            case JOI -> J_L.LABEL_PERMISSION_FROM_JOI;
            case USER -> J_L.LABEL_PERMISSION_FROM_USER;
            case APP -> J_L.LABEL_PERMISSION_FROM_ADMIN;
        });
    }

    /** The select of the tool, for tests. */
    public Select<ToolPermission> getSelect(ApprovableTool tool) {
        return selects.get(tool);
    }

    /** The line of what is in force for the tool, for tests. */
    public String getState(ApprovableTool tool) {
        return states.get(tool).getText();
    }
}
