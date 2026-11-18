package com.github.enerccio.joi.ui.dialogs;

import com.github.enerccio.joi.domain.collections.TextLLMType;
import com.github.enerccio.joi.domain.components.TextModelTypes;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.ui.dialogs.AIDialog;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * The platform's {@link AIDialog} for a text model, with what the model is used for ({@link TextLLMType}, several
 * allowed) under the name and the type. The types go into the AI's attributes ({@link TextModelTypes}) in the
 * dialog's own save.
 */
@Configurable
public class JoiAIDialog extends AIDialog {

    private MultiSelectComboBox<TextLLMType> typesBox;

    public JoiAIDialog() {
        this(null);
    }

    public JoiAIDialog(AI ai) {
        super(ai);
    }

    @Override
    protected List<Component> createApplicationFields(AI ai) {
        typesBox = new MultiSelectComboBox<>(loc.getValue(J_L.LABEL_USED_FOR));
        typesBox.setItems(TextLLMType.values());
        typesBox.setItemLabelGenerator(type -> loc.getValue(type.getLabel()));
        typesBox.setHelperText(loc.getValue(J_L.HELP_TEXT_LLM_TYPES));
        typesBox.setWidthFull();
        typesBox.setValue(TextModelTypes.get(ai));
        return List.of(typesBox);
    }

    @Override
    protected void applyApplicationFields(AI ai) {
        TextModelTypes.set(ai, typesBox.getValue());
    }

    /**
     * The multi-select of the types, for tests.
     */
    public MultiSelectComboBox<TextLLMType> getTypesBox() {
        return typesBox;
    }

    /**
     * Selects the types, for tests.
     */
    public void selectTypes(TextLLMType... types) {
        typesBox.setValue(types.length == 0 ? EnumSet.noneOf(TextLLMType.class) : EnumSet.copyOf(Arrays.asList(types)));
    }
}
