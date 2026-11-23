package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.bodies.Profiles;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.NodeService;
import com.github.enerccio.joi.domain.service.ProfileService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.dialogs.TextInputDialog;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Profiles tab of the Joi dialog: where she is (home, work), each profile with its own body and the state of its
 * parts ({@link Profiles}, {@code DESIGN.md} → Outfits → Profiles), and which node is in which. A profile starts as
 * the default one is; it is changed in the Bodies tab, which has the profile to choose. A node that connected is
 * remembered by its name, so it can be assigned while it is away.
 */
@Configurable
public class JoiProfilesPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private NodeService nodeService;

    private final JoiBodiesPart bodies;
    private Joi joi;
    private Grid<String> profiles;
    private Grid<String> nodes;
    private Span noNodes;
    private Profiles set = new Profiles();
    private Set<String> connected = new HashSet<>();

    /** @param bodies the Bodies tab, told when the profiles change (it has the choice of the profile to edit) */
    public JoiProfilesPart(JoiBodiesPart bodies) {
        this.bodies = bodies;
    }

    @Override
    public J_L caption() {
        return J_L.LABEL_PROFILES;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.HELP_PROFILES));
        hint.addClassName(CssStyles.HINT_TEXT);
        Button add = new Button(loc.getValue(J_L.LABEL_ADD_PROFILE), VaadinIcon.PLUS.create(), e -> ask(null));
        add.setThemeName("primary");
        HorizontalLayout header = new HorizontalLayout(hint, add);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        profiles = new Grid<>(String.class, false);
        profiles.setAllRowsVisible(true);
        profiles.addColumn(id -> set.nameOf(id)).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        profiles.addColumn(this::nodeNames).setHeader(loc.getValue(J_L.LABEL_PROFILE_NODES)).setFlexGrow(2);
        profiles.addComponentColumn(this::actions).setHeader("").setFlexGrow(0).setWidth("120px");

        Span nodesHint = new Span(loc.getValue(J_L.HELP_PROFILE_NODES));
        nodesHint.addClassName(CssStyles.HINT_TEXT);
        noNodes = new Span(loc.getValue(J_L.MSG_NO_NODES_YET));
        noNodes.addClassName(CssStyles.HINT_TEXT);
        nodes = new Grid<>(String.class, false);
        nodes.setAllRowsVisible(true);
        nodes.addColumn(this::nodeName).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        nodes.addColumn(id -> connected.contains(id) ? "●" : "").setHeader(loc.getValue(J_L.LABEL_NODE_CONNECTED)).setFlexGrow(0)
                .setWidth("110px");
        nodes.addComponentColumn(this::profileChoice).setHeader(loc.getValue(J_L.LABEL_PROFILE)).setFlexGrow(1);
        nodes.addComponentColumn(this::nodeActions).setHeader("").setFlexGrow(0).setWidth("70px");

        VerticalLayout layout = new VerticalLayout(header, profiles, new H4(loc.getValue(J_L.LABEL_PROFILE_NODES)), nodesHint, noNodes, nodes);
        layout.addClassName(JoiCssStyles.JOI_PROFILES);
        layout.setSizeFull();
        return layout;
    }

    private String nodeNames(String profile) {
        List<String> names = new ArrayList<>();
        for (String node : set.knownNodes().keySet()) {
            if (Profiles.idOf(profile).equals(set.profileOf(node))) {
                names.add(nodeName(node));
            }
        }
        return String.join(", ", names);
    }

    private String nodeName(String nodeId) {
        String name = set.knownNodes().get(nodeId);
        return StringUtils.isBlank(name) ? nodeId : name;
    }

    private Component actions(String profile) {
        Button rename = new Button(VaadinIcon.PENCIL.create(), e -> ask(profile));
        rename.setTooltipText(loc.getValue(J_L.LABEL_RENAME_PROFILE));
        Button delete = new Button(VaadinIcon.TRASH.create(), e -> ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), () -> {
            try {
                profileService.delete(joi, profile);
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        }));
        delete.setThemeName("error tertiary");
        delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
        // the default profile stays
        delete.setVisible(!Profiles.isDefault(profile));
        return new HorizontalLayout(rename, delete);
    }

    private Component profileChoice(String nodeId) {
        ComboBox<String> choice = new ComboBox<>();
        choice.setItems(set.ids());
        choice.setItemLabelGenerator(id -> set.nameOf(id));
        choice.setAllowCustomValue(false);
        choice.setClearButtonVisible(false);
        choice.setWidthFull();
        choice.setValue(set.profileOf(nodeId));
        choice.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            try {
                profileService.assign(joi, nodeId, e.getValue());
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        return choice;
    }

    /** A node that is away can be forgotten. */
    private Component nodeActions(String nodeId) {
        Button forget = new Button(VaadinIcon.TRASH.create(), e -> {
            try {
                profileService.forget(joi, nodeId);
                refresh();
            } catch (Exception ex) {
                UIUtils.internalServerError(loc, ex);
            }
        });
        forget.setThemeName("error tertiary");
        forget.setTooltipText(loc.getValue(L.LABEL_DELETE));
        forget.setVisible(!connected.contains(nodeId));
        return forget;
    }

    /** Asks for a name: of a new profile (null) or the new name of one. */
    private void ask(String profile) {
        new TextInputDialog.Builder(loc.getValue(profile == null ? J_L.LABEL_ADD_PROFILE : J_L.LABEL_RENAME_PROFILE), name -> {
            try {
                if (profile == null) {
                    profileService.create(joi, name);
                } else {
                    profileService.rename(joi, profile, name);
                }
                refresh();
            } catch (IllegalArgumentException e) {
                UIUtils.showError(loc.getValue(J_L.MSG_PROFILE_NAME_TAKEN), e);
            } catch (Exception e) {
                UIUtils.internalServerError(loc, e);
            }
        })
                .showCancel(true)
                .messageRequired()
                .inputMessageLabel(loc.getValue(L.LABEL_NAME))
                .initialValue(profile == null ? "" : set.nameOf(profile))
                .build()
                .open();
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
        set = profileService.profiles(joi);
        connected = new HashSet<>();
        for (var session : nodeService.sessions(joi)) {
            if (session.hello() != null && session.hello().nodeId() != null) {
                connected.add(session.hello().nodeId());
            }
        }
        profiles.setItems(set.ids());
        // the ones that connected are the first
        List<String> known = new ArrayList<>(set.knownNodes().keySet());
        known.sort((a, b) -> Boolean.compare(connected.contains(b), connected.contains(a)));
        nodes.setItems(known);
        noNodes.setVisible(known.isEmpty());
        nodes.setVisible(!known.isEmpty());
        bodies.profilesChanged();
    }

    /** The grids, for tests. */
    public Grid<String> getProfiles() {
        return profiles;
    }

    public Grid<String> getNodes() {
        return nodes;
    }
}
