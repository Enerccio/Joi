package com.github.enerccio.joi.ui.dialogs.joi;

import com.github.enerccio.joi.domain.animations.AnimationRoles;
import com.github.enerccio.joi.domain.model.impl.AnimationPack;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.AnimationPackService;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.joi.ui.dialogs.JoiDialog;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The Animations tab of the Joi dialog: the animations of the user's library she plays in her states: asleep, falling
 * asleep, waking up, the idle ones while she waits and the talking ones while she speaks (a node picks one of a list
 * at a time). Stored on her ({@link AnimationRoles}) as they are chosen. A node plays them from her body's
 * {@code .joia}, so a body that plays only some animations needs these among them.
 */
@Configurable
public class JoiAnimationsPart implements JoiDialogPart {

    @Autowired
    private Localization loc;

    @Autowired
    private AnimationPackService packService;

    private final JoiDialog dialog;
    private final Map<String, AnimationPack> library = new LinkedHashMap<>();
    private ComboBox<String> sleeping;
    private ComboBox<String> fallingAsleep;
    private ComboBox<String> wakingUp;
    private MultiSelectComboBox<String> idle;
    private MultiSelectComboBox<String> talking;
    private Span empty;
    /** Set while the fields are filled, so nothing is stored. */
    private boolean filling;

    public JoiAnimationsPart(JoiDialog dialog) {
        this.dialog = dialog;
    }

    @Override
    public J_L caption() {
        return J_L.LABEL_JOI_ANIMATIONS;
    }

    @Override
    public Component create() {
        Span hint = new Span(loc.getValue(J_L.MSG_JOI_ANIMATIONS_HINT));
        hint.addClassName(CssStyles.HINT_TEXT);
        empty = new Span(loc.getValue(J_L.MSG_NO_ANIMATIONS_YET));
        empty.addClassName(CssStyles.HINT_TEXT);
        sleeping = single(J_L.LABEL_ANIMATION_SLEEPING, AnimationRoles::withSleeping);
        fallingAsleep = single(J_L.LABEL_ANIMATION_FALLING_ASLEEP, AnimationRoles::withFallingAsleep);
        wakingUp = single(J_L.LABEL_ANIMATION_WAKING_UP, AnimationRoles::withWakingUp);
        idle = several(J_L.LABEL_ANIMATION_IDLE, J_L.MSG_ANIMATION_IDLE_HINT, AnimationRoles::withIdle);
        talking = several(J_L.LABEL_ANIMATION_TALKING, J_L.MSG_ANIMATION_TALKING_HINT, AnimationRoles::withTalking);
        VerticalLayout layout = new VerticalLayout(hint, empty, sleeping, fallingAsleep, wakingUp, idle, talking);
        layout.addClassName(JoiCssStyles.JOI_ANIMATIONS);
        return layout;
    }

    private ComboBox<String> single(J_L label, BiFunction<AnimationRoles, String, AnimationRoles> set) {
        ComboBox<String> field = new ComboBox<>(loc.getValue(label));
        field.setItemLabelGenerator(this::name);
        field.setClearButtonVisible(true);
        field.setWidthFull();
        field.addValueChangeListener(e -> store(roles -> set.apply(roles, e.getValue())));
        return field;
    }

    private MultiSelectComboBox<String> several(J_L label, J_L hint, BiFunction<AnimationRoles, List<String>, AnimationRoles> set) {
        MultiSelectComboBox<String> field = new MultiSelectComboBox<>(loc.getValue(label));
        field.setItemLabelGenerator(this::name);
        field.setClearButtonVisible(true);
        field.setHelperText(loc.getValue(hint));
        field.setWidthFull();
        field.addValueChangeListener(e -> store(roles -> set.apply(roles, ordered(e.getValue()))));
        return field;
    }

    /** The chosen ones in the order of the library (by name). */
    private List<String> ordered(Set<String> chosen) {
        return library.keySet().stream().filter(chosen::contains).toList();
    }

    private String name(String uuid) {
        AnimationPack pack = uuid == null ? null : library.get(uuid);
        return pack == null ? "" : StringUtils.defaultIfBlank(pack.getName(), "?");
    }

    private void store(java.util.function.UnaryOperator<AnimationRoles> change) {
        if (filling) {
            return;
        }
        Joi joi = dialog.getJoi();
        joi.setAnimationRoleSet(change.apply(joi.animationRoleSet().existing(library.keySet())));
        dialog.save();
    }

    @Override
    public void load(Joi joi) throws Exception {
        library.clear();
        packService.findAllByName().stream()
                .sorted(Comparator.comparing(pack -> StringUtils.defaultString(pack.getName()), String.CASE_INSENSITIVE_ORDER))
                .forEach(pack -> library.put(pack.getUuid(), pack));
        AnimationRoles roles = joi.animationRoleSet().existing(library.keySet());
        filling = true;
        try {
            List<String> uuids = List.copyOf(library.keySet());
            for (ComboBox<String> field : List.of(sleeping, fallingAsleep, wakingUp)) {
                field.setItems(uuids);
                field.setEnabled(!uuids.isEmpty());
            }
            idle.setItems(uuids);
            talking.setItems(uuids);
            idle.setEnabled(!uuids.isEmpty());
            talking.setEnabled(!uuids.isEmpty());
            sleeping.setValue(roles.sleeping());
            fallingAsleep.setValue(roles.fallingAsleep());
            wakingUp.setValue(roles.wakingUp());
            idle.setValue(Set.copyOf(roles.idle()));
            talking.setValue(Set.copyOf(roles.talking()));
            empty.setVisible(uuids.isEmpty());
        } finally {
            filling = false;
        }
    }

    @Override
    public void shown() throws Exception {
        // animations may have been made since the dialog opened
        load(dialog.getJoi());
    }

    /** The fields, for tests: asleep, falling asleep, waking up. */
    public List<ComboBox<String>> getSingleFields() {
        return List.of(sleeping, fallingAsleep, wakingUp);
    }

    /** The idle and talking fields, for tests. */
    public List<MultiSelectComboBox<String>> getListFields() {
        return List.of(idle, talking);
    }
}
