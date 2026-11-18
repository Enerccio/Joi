package com.github.enerccio.joi.ui.widgets;

import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.components.TextModelTypes;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.vaadin.flow.component.combobox.ComboBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The box of the model of a role: the text models marked for the role ({@link TextLLMType}).
 */
public final class RoleModels {

    private RoleModels() {
    }

    /**
     * Fills the box with the models marked for the role and selects the chosen one; a model chosen before it lost the
     * mark stays listed.
     *
     * @param chosenId the id of the chosen model, null for none
     */
    public static void fill(ComboBox<AI> box, List<AI> ais, TextLLMType type, Long chosenId) {
        List<AI> items = new ArrayList<>(ais.stream().filter(ai -> TextModelTypes.get(ai).contains(type)).toList());
        AI chosen = find(ais, chosenId);
        if (chosen != null && !items.contains(chosen)) {
            items.add(chosen);
        }
        box.setItems(items);
        box.setValue(chosen);
    }

    /** The model with the id, null when there is none (or the id is null). */
    public static AI find(List<AI> ais, Long id) {
        return id == null ? null : ais.stream().filter(ai -> id.equals(ai.getId())).findFirst().orElse(null);
    }
}
