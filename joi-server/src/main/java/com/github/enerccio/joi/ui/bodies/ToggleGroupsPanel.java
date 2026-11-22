package com.github.enerccio.joi.ui.bodies;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ToggleGroup;
import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The user's groups of toggles of a model ({@link ToggleGroup}), a section of the model dialog: each group has a name,
 * its toggles and its named states, each state a box per toggle and a button that shows it on the preview. A group is
 * described as a whole, a sentence per state; a toggle is in one group at most.
 */
@Configurable
public class ToggleGroupsPanel {

    @Autowired
    private Localization loc;

    private final Supplier<Model> currentModel;
    private final Consumer<List<ToggleGroup>> changed;
    private final Consumer<Map<String, Boolean>> tryState;

    private VerticalLayout layout;
    private VerticalLayout groupsLayout;
    private List<ToggleGroup> groups = new ArrayList<>();
    /** The choice of toggles of each group, by its position. */
    private final List<MultiSelectComboBox<String>> toggleBoxes = new ArrayList<>();

    /**
     * @param currentModel the model of the dialog
     * @param changed      stores the groups (the dialog stores the model and shows the parts anew)
     * @param tryState     shows a state on the preview: the toggle's id → shown
     */
    public ToggleGroupsPanel(Supplier<Model> currentModel, Consumer<List<ToggleGroup>> changed, Consumer<Map<String, Boolean>> tryState) {
        this.currentModel = currentModel;
        this.changed = changed;
        this.tryState = tryState;
    }

    public Component create() {
        Span title = new Span(loc.getValue(J_L.LABEL_TOGGLE_GROUPS));
        Span hint = new Span(loc.getValue(J_L.MSG_TOGGLE_GROUPS_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        groupsLayout = new VerticalLayout();
        groupsLayout.setPadding(false);
        Button add = new Button(loc.getValue(J_L.LABEL_ADD_GROUP), Solid.PLUS.create(), e -> addGroup());
        add.addThemeVariants(ButtonVariant.LUMO_SMALL);
        layout = new VerticalLayout(title, hint, groupsLayout, add);
        layout.setPadding(false);
        layout.setSpacing(false);
        return layout;
    }

    /** Shows the model's groups. */
    public void show() {
        Model model = currentModel.get();
        ModelCapabilities capabilities = model == null ? null : ModelCapabilities.fromJson(model.getCapabilities());
        layout.setVisible(capabilities != null && !capabilities.toggles().isEmpty());
        groups = model == null ? new ArrayList<>() : new ArrayList<>(model.toggleGroups());
        groupsLayout.removeAll();
        toggleBoxes.clear();
        if (capabilities == null) {
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            groupsLayout.add(group(model, capabilities, i));
        }
    }

    private Component group(Model model, ModelCapabilities capabilities, int index) {
        ToggleGroup group = groups.get(index);
        Map<String, ModelCapabilities.Toggle> byId = new LinkedHashMap<>();
        capabilities.toggles().forEach(toggle -> byId.put(toggle.id(), toggle));

        TextField name = new TextField();
        name.addThemeVariants(TextFieldVariant.LUMO_SMALL);
        name.setPlaceholder(loc.getValue(J_L.LABEL_GROUP_NAME));
        name.setValue(StringUtils.defaultString(group.name()));
        name.addValueChangeListener(e -> {
            ToggleGroup now = groups.get(index);
            replace(index, now.withName(StringUtils.strip(e.getValue())), false);
        });
        Button delete = new Button(Solid.TRASH.create(), e -> {
            groups.remove(index);
            save(true);
        });
        delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_ICON);
        delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
        HorizontalLayout header = new HorizontalLayout(name, delete);
        header.setWidthFull();
        header.setFlexGrow(1, name);

        MultiSelectComboBox<String> toggles = new MultiSelectComboBox<>(loc.getValue(J_L.LABEL_GROUP_TOGGLES));
        toggles.setWidthFull();
        toggles.setItemLabelGenerator(id -> byId.containsKey(id) ? model.toggleName(byId.get(id)) : id);
        toggleBoxes.add(toggles);
        offer(toggles, index, byId);
        VerticalLayout statesBox = new VerticalLayout();
        statesBox.setPadding(false);
        statesBox.setSpacing(false);
        toggles.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            List<String> ids = byId.keySet().stream().filter(e.getValue()::contains).toList();
            ToggleGroup now = groups.get(index);
            // the states follow; the box stays open to pick more
            replace(index, now.withToggles(new ArrayList<>(ids)), false);
            fillStates(statesBox, model, byId, index);
            // the other groups no longer offer the toggles taken here, and offer again the ones let go
            for (int other = 0; other < toggleBoxes.size(); other++) {
                if (other != index) {
                    offer(toggleBoxes.get(other), other, byId);
                }
            }
        });
        fillStates(statesBox, model, byId, index);

        VerticalLayout box = new VerticalLayout(header, toggles, statesBox);
        box.setPadding(false);
        box.setSpacing(false);
        box.addClassName(JoiCssStyles.TOGGLE_GROUP);
        return box;
    }

    /** The toggles the group may take (a toggle is in one group at most), its own chosen. */
    private void offer(MultiSelectComboBox<String> box, int index, Map<String, ModelCapabilities.Toggle> byId) {
        Set<String> elsewhere = new HashSet<>();
        for (int other = 0; other < groups.size(); other++) {
            if (other != index) {
                elsewhere.addAll(groups.get(other).toggles());
            }
        }
        box.setItems(byId.keySet().stream().filter(id -> !elsewhere.contains(id)).toList());
        box.setValue(new LinkedHashSet<>(groups.get(index).toggles().stream().filter(byId::containsKey).filter(id -> !elsewhere.contains(id)).toList()));
    }

    /** The states of a group, each a row of boxes for its toggles, and the button that adds one. */
    private void fillStates(VerticalLayout statesBox, Model model, Map<String, ModelCapabilities.Toggle> byId, int index) {
        statesBox.removeAll();
        ToggleGroup group = groups.get(index);
        Div states = new Div();
        states.addClassName(JoiCssStyles.TOGGLE_GROUP_STATES);
        List<ModelCapabilities.Toggle> members = group.toggles().stream().filter(byId::containsKey).map(byId::get).toList();
        for (int s = 0; s < group.states().size(); s++) {
            states.add(state(model, group, index, s, members));
        }
        Button addState = new Button(loc.getValue(J_L.LABEL_ADD_STATE), Solid.PLUS.create(), e -> {
            Map<String, Boolean> shown = new LinkedHashMap<>();
            members.forEach(toggle -> shown.put(toggle.id(), model.shownByDefault(toggle)));
            ToggleGroup now = groups.get(index);
            List<ToggleGroup.State> list = new ArrayList<>(now.states());
            list.add(new ToggleGroup.State(ToggleGroup.newId(), loc.getValue(J_L.LABEL_STATE_NAME) + " " + (list.size() + 1), shown));
            replace(index, now.withStates(list), true);
        });
        addState.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        addState.setEnabled(!members.isEmpty());
        statesBox.add(states, addState);
        if (!group.states().isEmpty() && group.defaultStateOf() == null) {
            Span missing = new Span(loc.getValue(J_L.MSG_NO_DEFAULT_STATE));
            missing.addClassName(CssStyles.HINT_TEXT);
            statesBox.add(missing);
        }
    }

    private Component state(Model model, ToggleGroup group, int index, int stateIndex, List<ModelCapabilities.Toggle> members) {
        ToggleGroup.State state = group.states().get(stateIndex);
        TextField name = new TextField();
        name.addThemeVariants(TextFieldVariant.LUMO_SMALL);
        name.setPlaceholder(loc.getValue(J_L.LABEL_STATE_NAME));
        name.setValue(StringUtils.defaultString(state.name()));
        name.addValueChangeListener(e -> {
            ToggleGroup.State now = groups.get(index).states().get(stateIndex);
            setState(index, stateIndex, new ToggleGroup.State(now.id(), StringUtils.strip(e.getValue()), now.shown()), false);
        });
        HorizontalLayout row = new HorizontalLayout(name);
        row.setAlignItems(FlexComponent.Alignment.BASELINE);
        for (ModelCapabilities.Toggle toggle : members) {
            Checkbox shown = new Checkbox(model.toggleName(toggle), state.shown().getOrDefault(toggle.id(), model.shownByDefault(toggle)));
            shown.addValueChangeListener(e -> {
                ToggleGroup.State now = groups.get(index).states().get(stateIndex);
                Map<String, Boolean> values = new LinkedHashMap<>(now.shown());
                values.put(toggle.id(), e.getValue());
                setState(index, stateIndex, new ToggleGroup.State(now.id(), now.name(), values), false);
                tryState.accept(stateOf(groups.get(index), stateIndex, members, model));
            });
            row.add(shown);
        }
        Checkbox byDefault = new Checkbox(loc.getValue(J_L.LABEL_DEFAULT_STATE), state.id().equals(group.defaultState()));
        byDefault.setTooltipText(loc.getValue(J_L.MSG_DEFAULT_STATE_HINT));
        byDefault.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                ToggleGroup now = groups.get(index);
                replace(index, now.withDefaultState(e.getValue() ? state.id() : null), true);
            }
        });
        row.add(byDefault);
        Button show = new Button(Solid.EYE.create(), e -> tryState.accept(stateOf(groups.get(index), stateIndex, members, model)));
        show.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY);
        show.setTooltipText(loc.getValue(J_L.LABEL_TRY_STATE));
        Button remove = new Button(Solid.TIMES.create(), e -> {
            List<ToggleGroup.State> list = new ArrayList<>(groups.get(index).states());
            list.remove(stateIndex);
            ToggleGroup now = groups.get(index);
            replace(index, now.withStates(list), true);
        });
        remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        remove.setTooltipText(loc.getValue(L.LABEL_DELETE));
        row.add(show, remove);
        row.addClassName(JoiCssStyles.TOGGLE_GROUP_STATE);
        return row;
    }

    /** The state's toggles, the ones it doesn't set as by default. */
    private static Map<String, Boolean> stateOf(ToggleGroup group, int stateIndex, List<ModelCapabilities.Toggle> members, Model model) {
        ToggleGroup.State state = group.states().get(stateIndex);
        Map<String, Boolean> shown = new LinkedHashMap<>();
        members.forEach(toggle -> shown.put(toggle.id(), state.shown().getOrDefault(toggle.id(), model.shownByDefault(toggle))));
        return shown;
    }

    private void setState(int index, int stateIndex, ToggleGroup.State state, boolean redraw) {
        ToggleGroup group = groups.get(index);
        List<ToggleGroup.State> list = new ArrayList<>(group.states());
        list.set(stateIndex, state);
        replace(index, group.withStates(list), redraw);
    }

    private void replace(int index, ToggleGroup group, boolean redraw) {
        groups.set(index, group);
        save(redraw);
    }

    private void addGroup() {
        groups.add(new ToggleGroup(ToggleGroup.newId(), null, new ArrayList<>(), new ArrayList<>()));
        save(true);
    }

    private void save(boolean redraw) {
        changed.accept(List.copyOf(groups));
        if (redraw) {
            show();
        }
    }

    /** The choice of toggles of the group at the position, for tests. */
    public MultiSelectComboBox<String> getToggleBox(int index) {
        return toggleBoxes.get(index);
    }

    /** The groups as edited, for tests. */
    public List<ToggleGroup> getGroups() {
        return groups;
    }

    /** Sets the groups as if edited, for tests. */
    public void setGroupsForTest(List<ToggleGroup> groups) {
        this.groups = new ArrayList<>(groups);
        save(true);
    }
}
